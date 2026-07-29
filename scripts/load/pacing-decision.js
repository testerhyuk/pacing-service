import crypto from "k6/crypto";
import http from "k6/http";
import { check } from "k6";

const errorRateThreshold =
  __ENV.ERROR_RATE_THRESHOLD || "rate<0.01";

export const options = {
  scenarios: {
    pacingDecision: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 100),
      timeUnit: "1s",
      duration: __ENV.DURATION || "30s",
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 200),
      gracefulStop: "10s",
    },
  },
  thresholds: {
    http_req_failed: [errorRateThreshold],
    http_req_duration: ["p(95)<100", "p(99)<200"],
  },
};

const baseUrl = __ENV.BASE_URL || "http://host.docker.internal:8080";
const clientId = __ENV.CLIENT_ID || "ad-server";
const secret = __ENV.SECRET;
const campaignId = __ENV.CAMPAIGN_ID;
const path = "/internal/v1/pacing/decisions/decide";

function signedHeaders(method, requestPath, body) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = `${__VU}-${__ITER}-${Date.now()}`;
  const bodyHash = crypto.sha256(body, "hex");
  const canonical = [
    method,
    requestPath,
    clientId,
    timestamp,
    nonce,
    bodyHash,
  ].join("\n");

  return {
    "Content-Type": "application/json",
    "X-Client-Id": clientId,
    "X-Timestamp": timestamp,
    "X-Nonce": nonce,
    "X-Signature": crypto.hmac(
      "sha256",
      secret,
      canonical,
      "hex",
    ),
  };
}

export default function () {
  if (!secret || !campaignId) {
    throw new Error("SECRET and CAMPAIGN_ID are required");
  }

  const body = JSON.stringify({
    requestId: `${__VU}-${__ITER}-${Date.now()}`,
    campaignId,
    requestedAt: new Date().toISOString(),
  });

  const response = http.post(
    `${baseUrl}${path}`,
    body,
    {
      headers: signedHeaders("POST", path, body),
      timeout: "5s",
    },
  );

  const success = check(response, {
    "decision returns 200": (value) =>
      value.status === 200,
  });

  if (!success) {
    console.error(
      JSON.stringify({
        status: response.status,
        error: response.error,
        errorCode: response.error_code,
        duration: response.timings.duration,
        blocked: response.timings.blocked,
        connecting: response.timings.connecting,
        sending: response.timings.sending,
        waiting: response.timings.waiting,
        receiving: response.timings.receiving,
        body:
          response.body == null
            ? null
            : String(response.body).slice(0, 300),
      }),
    );
  }
}
