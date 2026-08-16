#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${PACING_ENV_FILE:-${PROJECT_ROOT}/.env.aws-load}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Environment file not found: ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

CAMPAIGN_ID="${1:?campaignId is required}"
RATE="${2:-100}"
DURATION="${3:-30s}"
PRE_ALLOCATED_VUS="${4:-50}"
MAX_VUS="${5:-200}"

: "${PACING_API_BASE_URL:?PACING_API_BASE_URL is required}"
: "${PACING_HMAC_AD_SERVER_CURRENT_SECRET:?ad-server secret is required}"

curl -fsS "${PACING_API_BASE_URL}/actuator/health" >/dev/null

echo "AWS pacing decision load test"
echo "API:      ${PACING_API_BASE_URL}"
echo "campaign: ${CAMPAIGN_ID}"
echo "rate:     ${RATE} req/s"
echo "duration: ${DURATION}"

docker run --rm \
  --network bridge \
  -e "BASE_URL=${PACING_API_BASE_URL}" \
  -e "CLIENT_ID=ad-server" \
  -e "SECRET=${PACING_HMAC_AD_SERVER_CURRENT_SECRET}" \
  -e "CAMPAIGN_ID=${CAMPAIGN_ID}" \
  -e "RATE=${RATE}" \
  -e "DURATION=${DURATION}" \
  -e "PRE_ALLOCATED_VUS=${PRE_ALLOCATED_VUS}" \
  -e "MAX_VUS=${MAX_VUS}" \
  -v "${PROJECT_ROOT}/scripts/load:/scripts:ro" \
  grafana/k6:latest \
  run /scripts/pacing-decision.js
