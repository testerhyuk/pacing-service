import crypto from "k6/crypto";
import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";
import { Counter } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://pacing-api:8080";
const campaignId = __ENV.CAMPAIGN_ID;
const reservationPrefix = __ENV.RESERVATION_PREFIX;
const adServerSecret = __ENV.AD_SERVER_SECRET;
const auctionServerSecret = __ENV.AUCTION_SERVER_SECRET;
const amount = Number(__ENV.AMOUNT || 100);
const attempts = Number(__ENV.ATTEMPTS || 1000);
const expectedCreated = Number(__ENV.EXPECTED_CREATED || 500);
const vus = Number(__ENV.VUS || 400);

const createdCounter =
    new Counter("boundary_reservation_created");
const insufficientCounter =
    new Counter("boundary_reservation_insufficient");
const unexpectedCounter =
    new Counter("boundary_reservation_unexpected");

if (!campaignId || !reservationPrefix) {
    throw new Error(
        "CAMPAIGN_ID and RESERVATION_PREFIX are required."
    );
}

if (!adServerSecret || !auctionServerSecret) {
    throw new Error(
        "AD_SERVER_SECRET and AUCTION_SERVER_SECRET are required."
    );
}

if (!Number.isInteger(amount) || amount <= 1) {
    throw new Error(`Invalid AMOUNT: ${__ENV.AMOUNT}`);
}

if (
    !Number.isInteger(attempts) ||
    !Number.isInteger(expectedCreated) ||
    attempts <= expectedCreated ||
    expectedCreated <= 0
) {
    throw new Error(
        "ATTEMPTS must be greater than EXPECTED_CREATED."
    );
}

if (!Number.isInteger(vus) || vus <= 0) {
    throw new Error(`Invalid VUS: ${__ENV.VUS}`);
}

const expectedInsufficient = attempts - expectedCreated;

export const options = {
    scenarios: {
        budgetBoundary: {
            executor: "shared-iterations",
            vus,
            iterations: attempts,
            maxDuration: "5m",
        },
    },

    thresholds: {
        checks: ["rate==1"],
        boundary_reservation_created: [
            `count==${expectedCreated}`,
        ],
        boundary_reservation_insufficient: [
            `count==${expectedInsufficient}`,
        ],
        boundary_reservation_unexpected: [
            "count==0",
        ],
    },
};

http.setResponseCallback(
    http.expectedStatuses(201, 409)
);

function sha256Hex(text) {
    return crypto.sha256(text, "hex");
}

function createSignature({
    clientId,
    secret,
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

function parseBody(response) {
    try {
        return response.json();
    } catch (error) {
        return null;
    }
}

export default function () {
    const index = exec.scenario.iterationInTest;
    const useAdServer = (index % 2) === 0;
    const clientId = useAdServer
        ? "ad-server"
        : "auction-server";
    const secret = useAdServer
        ? adServerSecret
        : auctionServerSecret;
    const reservationId = `${reservationPrefix}-${index}`;
    const path = "/internal/v1/budget-reservations";
    const body = JSON.stringify({
        reservationId,
        campaignId,
        amount,
    });
    const timestamp =
        Math.floor(Date.now() / 1000).toString();
    const nonce =
        `${reservationPrefix}-nonce-${index}-${Date.now()}`;
    const signature = createSignature({
        clientId,
        secret,
        method: "POST",
        path,
        timestamp,
        nonce,
        body,
    });

    unexpectedCounter.add(0);

    const response = http.post(
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
            timeout: "10s",
            tags: {
                endpoint: "budget-boundary",
            },
        }
    );

    const responseBody = parseBody(response);
    const created =
        response.status === 201 &&
        responseBody !== null &&
        responseBody.created === true;
    const insufficient =
        response.status === 409 &&
        responseBody !== null &&
        responseBody.code === "INSUFFICIENT_BUDGET";

    if (created) {
        createdCounter.add(1);
    } else if (insufficient) {
        insufficientCounter.add(1);
    } else {
        unexpectedCounter.add(1);
        console.error(
            JSON.stringify({
                index,
                reservationId,
                status: response.status,
                body: response.body,
            })
        );
    }

    check(response, {
        "created or insufficient budget": () =>
            created || insufficient,
    });
}
