import crypto from "k6/crypto";
import http from "k6/http";
import exec from "k6/execution";
import { check, sleep } from "k6";

const baseUrl = __ENV.BASE_URL || "http://pacing-api:8080";
const clientId = __ENV.CLIENT_ID || "auction-server";
const secret = __ENV.SECRET;

const campaignId = __ENV.CAMPAIGN_ID;
const reservationPrefix =
    __ENV.RESERVATION_PREFIX || "full-load-reservation";

const amount = Number(__ENV.AMOUNT || 10);

const totalReservations =
    Number(__ENV.TOTAL_RESERVATIONS || 12000);

const targetRate =
    Number(__ENV.RATE || 500);

const reservationVUs =
    Number(__ENV.PRE_ALLOCATED_VUS || 100);

const reservationMaxVUs =
    Number(__ENV.MAX_VUS || 200);

const maxRequestAttempts = 4;
const retryableStatuses =
    new Set([0, 429, 500, 502, 503, 504]);

let nextRequestAt;

if (!secret) {
    throw new Error("SECRET environment variable is required.");
}

if (!campaignId) {
    throw new Error("CAMPAIGN_ID environment variable is required.");
}

if (!Number.isFinite(amount) || amount <= 0) {
    throw new Error(`Invalid AMOUNT: ${__ENV.AMOUNT}`);
}

if (
    !Number.isInteger(totalReservations) ||
    totalReservations <= 0
) {
    throw new Error(
        `Invalid TOTAL_RESERVATIONS: ${__ENV.TOTAL_RESERVATIONS}`
    );
}

if (!Number.isFinite(targetRate) || targetRate <= 0) {
    throw new Error(`Invalid RATE: ${__ENV.RATE}`);
}

if (
    !Number.isInteger(reservationVUs) ||
    reservationVUs <= 0
) {
    throw new Error(
        `Invalid PRE_ALLOCATED_VUS: ${__ENV.PRE_ALLOCATED_VUS}`
    );
}

if (
    !Number.isInteger(reservationMaxVUs) ||
    reservationMaxVUs < reservationVUs
) {
    throw new Error(
        `Invalid MAX_VUS: ${__ENV.MAX_VUS}`
    );
}

const durationSeconds =
    Math.ceil(totalReservations / targetRate);

const maxDurationSeconds =
    Math.max(
        durationSeconds * 2,
        durationSeconds + 300
    );

export const options = {
    scenarios: {
        reservationSetup: {
            /*
             * 예약은 부하 측정용 요청이 아니라 테스트 데이터다.
             * 모든 예약 ID가 정확히 한 번 실행되도록 총 반복 횟수를 고정한다.
             */
            executor: "shared-iterations",
            vus: reservationVUs,
            iterations: totalReservations,
            maxDuration: `${maxDurationSeconds}s`,
        },
    },

    thresholds: {
        checks: [
            "rate==1",
        ],
    },
};

function sha256Hex(text) {
    return crypto.sha256(text, "hex");
}

function createSignature({
    method,
    path,
    timestamp,
    nonce,
    body,
}) {
    const bodyHash = sha256Hex(body);

    const canonical = [
        method,
        path,
        clientId,
        timestamp,
        nonce,
        bodyHash,
    ].join("\n");

    return crypto.hmac(
        "sha256",
        secret,
        canonical,
        "hex"
    );
}

function waitForRateSlot() {
    const intervalMilliseconds =
        (reservationVUs / targetRate) * 1000;

    if (nextRequestAt === undefined) {
        const scenarioStartedAt =
            new Date(exec.scenario.startTime).getTime();

        const vuOffsetMilliseconds =
            ((exec.vu.idInTest - 1) / targetRate) * 1000;

        nextRequestAt =
            scenarioStartedAt + vuOffsetMilliseconds;
    } else {
        /*
         * Always leave one full per-VU interval after a completed request.
         * Do not catch up missed slots with a burst.
         */
        nextRequestAt =
            Math.max(
                nextRequestAt + intervalMilliseconds,
                Date.now() + intervalMilliseconds
            );
    }

    const delayMilliseconds =
        nextRequestAt - Date.now();

    if (delayMilliseconds > 0) {
        sleep(delayMilliseconds / 1000);
    }
}

function sendReservation({
    index,
    reservationId,
    path,
    body,
}) {
    let response;

    for (
        let attempt = 1;
        attempt <= maxRequestAttempts;
        attempt++
    ) {
        const timestamp =
            Math.floor(Date.now() / 1000).toString();

        const nonce =
            `${reservationPrefix}-nonce-${index}-${attempt}-${Date.now()}`;

        const signature = createSignature({
            method: "POST",
            path,
            timestamp,
            nonce,
            body,
        });

        response = http.post(
            `${baseUrl}${path}`,
            body,
            {
                headers: {
                    "Content-Type": "application/json",

                    "X-Client-Id": clientId,
                    "X-Timestamp": timestamp,
                    "X-Nonce": nonce,
                    "X-Signature": signature,
                },

                timeout: "5s",

                tags: {
                    endpoint: "budget-reservation",
                },
            }
        );

        if (
            response.status === 201 ||
            response.status === 200
        ) {
            return response;
        }

        const canRetry =
            retryableStatuses.has(response.status) &&
            attempt < maxRequestAttempts;

        if (!canRetry) {
            return response;
        }

        sleep(0.1 * Math.pow(2, attempt - 1));
    }

    return response;
}

export default function () {
    const index =
        exec.scenario.iterationInTest;

    /*
     * shared-iterations는 정확한 예약 개수를 보장한다.
     * 각 VU의 요청 간격을 유지해 밀린 요청이 burst로 몰리지 않게 한다.
     */
    waitForRateSlot();

    const reservationId =
        `${reservationPrefix}-${index}`;

    const path =
        "/internal/v1/budget-reservations";

    const body = JSON.stringify({
        reservationId,
        campaignId,
        amount,
    });

    const response = sendReservation({
        index,
        reservationId,
        path,
        body,
    });

    const success = check(
        response,
        {
            "reservation persisted": (res) =>
                res.status === 201 ||
                res.status === 200,
        }
    );

    if (!success) {
        console.error(
            JSON.stringify({
                index,
                reservationId,
                status: response.status,
                body: response.body,
            })
        );
    }
}
