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
TOTAL_RESERVATIONS="${2:-12000}"
RATE="${3:-400}"
AMOUNT="${4:-10}"
PRE_ALLOCATED_VUS="${5:-100}"
MAX_VUS="${6:-200}"
RESERVATION_PREFIX="aws-reservation-$(date -u '+%Y%m%d%H%M%S')"

: "${PACING_API_BASE_URL:?PACING_API_BASE_URL is required}"
: "${PACING_HMAC_AUCTION_SERVER_CURRENT_SECRET:?auction-server secret is required}"

curl -fsS "${PACING_API_BASE_URL}/actuator/health" >/dev/null

echo "AWS budget reservation load test"
echo "API:          ${PACING_API_BASE_URL}"
echo "campaign:     ${CAMPAIGN_ID}"
echo "reservations: ${TOTAL_RESERVATIONS}"
echo "rate:         ${RATE} req/s"
echo "amount:       ${AMOUNT}"
echo "prefix:       ${RESERVATION_PREFIX}"

docker run --rm \
  --network bridge \
  -e "BASE_URL=${PACING_API_BASE_URL}" \
  -e "CLIENT_ID=auction-server" \
  -e "SECRET=${PACING_HMAC_AUCTION_SERVER_CURRENT_SECRET}" \
  -e "CAMPAIGN_ID=${CAMPAIGN_ID}" \
  -e "RESERVATION_PREFIX=${RESERVATION_PREFIX}" \
  -e "AMOUNT=${AMOUNT}" \
  -e "TOTAL_RESERVATIONS=${TOTAL_RESERVATIONS}" \
  -e "RATE=${RATE}" \
  -e "PRE_ALLOCATED_VUS=${PRE_ALLOCATED_VUS}" \
  -e "MAX_VUS=${MAX_VUS}" \
  -v "${PROJECT_ROOT}/scripts/load:/scripts:ro" \
  grafana/k6:latest \
  run /scripts/full-system-reservation-setup.js

echo "Reservation load test succeeded."
echo "RESERVATION_PREFIX=${RESERVATION_PREFIX}"
