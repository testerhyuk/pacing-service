import crypto from "k6/crypto";
import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";

const baseUrl = __ENV.BASE_URL || "http://pacing-api:8080";
const clientId = __ENV.CLIENT_ID || "ad-server";
const secret = __ENV.SECRET;
const campaignId = __ENV.CAMPAIGN_ID;
const reservationAmount = Number(__ENV.RESERVATION_AMOUNT || 100);
const reservationPrefix =
  __ENV.RESERVATION_PREFIX || "traffic-response-reservation";

const warmupRate = Number(__ENV.WARMUP_RATE || 50);
const spikeRate = Number(__ENV.SPIKE_RATE || 300);
const recoveryRate = Number(__ENV.RECOVERY_RATE || 50);
const warmupDuration = __ENV.WARMUP_DURATION || "90s";
const spikeDuration = __ENV.SPIKE_DURATION || "90s";
const recoveryDuration = __ENV.RECOVERY_DURATION || "120s";
const spikeStart = __ENV.SPIKE_START || "90s";
const recoveryStart = __ENV.RECOVERY_START || "180s";

if (!secret || !campaignId) {
  throw new Error("SECRET and CAMPAIGN_ID are required");
}

if (!Number.isFinite(reservationAmount) || reservationAmount <= 0) {
  throw new Error("RESERVATION_AMOUNT must be greater than zero");
}

for (const [name, rate] of Object.entries({
  WARMUP_RATE: warmupRate,
  SPIKE_RATE: spikeRate,
  RECOVERY_RATE: recoveryRate,
})) {
  if (!Number.isFinite(rate) || rate <= 0) {
    throw new Error(`${name} must be greater than zero`);
  }
}

export const options = {
  scenarios: {
    warmup: arrivalScenario(warmupRate, warmupDuration, "0s", "warmup"),
    spike: arrivalScenario(spikeRate, spikeDuration, spikeStart, "spike"),
    recovery: arrivalScenario(recoveryRate, recoveryDuration, recoveryStart, "recovery"),
  },
  thresholds: {
    http_req_failed: [__ENV.ERROR_RATE_THRESHOLD || "rate<0.01"],
    http_req_duration: ["p(95)<100", "p(99)<200"],
    checks: ["rate>0.99"],
  },
};

function arrivalScenario(rate, duration, startTime, exec) {
  return {
    executor: "constant-arrival-rate",
    exec,
    rate,
    timeUnit: "1s",
    duration,
    startTime,
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 100),
    maxVUs: Number(__ENV.MAX_VUS || 300),
    gracefulStop: "10s",
  };
}

function signedHeaders(method, path, body, suffix) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = [
    exec.scenario.name,
    __VU,
    __ITER,
    suffix,
    Date.now(),
  ].join("-");
  const canonical = [
    method,
    path,
    clientId,
    timestamp,
    nonce,
    crypto.sha256(body, "hex"),
  ].join("\n");

  return {
    "Content-Type": "application/json",
    "X-Client-Id": clientId,
    "X-Timestamp": timestamp,
    "X-Nonce": nonce,
    "X-Signature": crypto.hmac("sha256", secret, canonical, "hex"),
  };
}

export function warmup() {
  decideAndReserve();
}

export function spike() {
  decideAndReserve();
}

export function recovery() {
  decideAndReserve();
}

function decideAndReserve() {
  const requestId = [
    "traffic-response-decision",
    exec.scenario.name,
    __VU,
    __ITER,
    Date.now(),
  ].join("-");
  const decisionPath = "/internal/v1/pacing/decisions/decide";
  const decisionBody = JSON.stringify({
    requestId,
    campaignId,
    requestedAt: new Date().toISOString(),
  });

  const decisionResponse = http.post(
    `${baseUrl}${decisionPath}`,
    decisionBody,
    {
      headers: signedHeaders(
        "POST",
        decisionPath,
        decisionBody,
        "decision",
      ),
      timeout: "5s",
      tags: { endpoint: "pacing-decision" },
    },
  );

  const decisionSucceeded = check(decisionResponse, {
    "decision returns 200": (response) => response.status === 200,
  });

  if (!decisionSucceeded) {
    logFailure("decision", decisionResponse);
    return;
  }

  const decision = decisionResponse.json();
  if (decision.decision !== "PASS") {
    return;
  }

  const reservationId = [
    reservationPrefix,
    exec.scenario.name,
    __VU,
    __ITER,
    Date.now(),
  ].join("-");
  const reservationPath = "/internal/v1/budget-reservations";
  const reservationBody = JSON.stringify({
    reservationId,
    campaignId,
    amount: reservationAmount,
  });

  const reservationResponse = http.post(
    `${baseUrl}${reservationPath}`,
    reservationBody,
    {
      headers: signedHeaders(
        "POST",
        reservationPath,
        reservationBody,
        "reservation",
      ),
      timeout: "5s",
      tags: { endpoint: "budget-reservation" },
    },
  );

  const reservationSucceeded = check(reservationResponse, {
    "PASS reservation returns 201 or 200": (response) =>
      response.status === 201 || response.status === 200,
  });

  if (!reservationSucceeded) {
    logFailure("reservation", reservationResponse);
  }
}

function logFailure(operation, response) {
  console.error(
    JSON.stringify({
      operation,
      status: response.status,
      error: response.error,
      duration: response.timings.duration,
      body:
        response.body == null
          ? null
          : String(response.body).slice(0, 300),
    }),
  );
}
