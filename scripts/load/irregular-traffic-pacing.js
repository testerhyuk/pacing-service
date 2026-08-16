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
  __ENV.RESERVATION_PREFIX || "irregular-traffic-reservation";

const trafficStages = [
  stage("baseline_1", 50, 40, 0),
  stage("short_spike_1", 300, 10, 40),
  stage("baseline_2", 50, 25, 50),
  stage("sustained_spike", 250, 30, 75),
  stage("partial_recovery", 80, 15, 105),
  stage("short_spike_2", 400, 8, 120),
  stage("recovery", 50, 60, 128),
];

if (!secret || !campaignId) {
  throw new Error("SECRET and CAMPAIGN_ID are required");
}

if (!Number.isFinite(reservationAmount) || reservationAmount <= 0) {
  throw new Error("RESERVATION_AMOUNT must be greater than zero");
}

export const options = {
  scenarios: Object.fromEntries(
    trafficStages.map((trafficStage) => [
      trafficStage.name,
      arrivalScenario(trafficStage),
    ]),
  ),
  thresholds: {
    http_req_failed: [__ENV.ERROR_RATE_THRESHOLD || "rate<0.01"],
    http_req_duration: ["p(95)<100", "p(99)<200"],
    checks: ["rate>0.99"],
  },
};

function stage(name, rate, durationSeconds, startSeconds) {
  return { name, rate, durationSeconds, startSeconds };
}

function arrivalScenario(trafficStage) {
  const preAllocatedVUs = Math.max(
    10,
    Math.ceil(trafficStage.rate / 4),
  );

  return {
    executor: "constant-arrival-rate",
    exec: "sendRequest",
    rate: trafficStage.rate,
    timeUnit: "1s",
    duration: `${trafficStage.durationSeconds}s`,
    startTime: `${trafficStage.startSeconds}s`,
    preAllocatedVUs,
    maxVUs: preAllocatedVUs * 3,
    gracefulStop: "5s",
    tags: { traffic_stage: trafficStage.name },
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

export function sendRequest() {
  const requestId = [
    "irregular-traffic-decision",
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
      tags: {
        endpoint: "pacing-decision",
        traffic_stage: exec.scenario.name,
      },
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
      tags: {
        endpoint: "budget-reservation",
        traffic_stage: exec.scenario.name,
      },
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
      trafficStage: exec.scenario.name,
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
