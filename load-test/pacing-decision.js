import crypto from "k6/crypto";
import http from "k6/http";
import { check } from "k6";

export const options = {
  scenarios: {
    pacing_decision: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 1000),
      timeUnit: "1s",
      duration: __ENV.DURATION || "2m",
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 500),
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<50", "p(99)<100"],
  },
};

const baseUrl = __ENV.BASE_URL || "http://localhost:8080";
const clientId = __ENV.CLIENT_ID || "ad-server";
const secret = __ENV.SECRET;
const campaignId = __ENV.CAMPAIGN_ID;

function signedHeaders(method, path, body) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = `${__VU}-${__ITER}-${Date.now()}`;
  const bodyHash = crypto.sha256(body, "hex");
  const canonical = [
    method,
    path,
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
    "X-Signature": crypto.hmac("sha256", secret, canonical, "hex"),
  };
}

export default function () {
  if (!secret || !campaignId) {
    throw new Error("SECRET and CAMPAIGN_ID are required");
  }

  const path = "/internal/v1/pacing/decisions";
  const body = JSON.stringify({
    requestId: `${__VU}-${__ITER}-${Date.now()}`,
    campaignId,
    requestedAt: new Date().toISOString(),
  });
  const response = http.post(
    `${baseUrl}${path}`,
    body,
    { headers: signedHeaders("POST", path, body) },
  );

  check(response, {
    "decision returns 200": (value) => value.status === 200,
  });
}
