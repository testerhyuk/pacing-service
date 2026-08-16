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

CAMPAIGN_ID="${1:-aws-load-campaign-1}"
PACING_STRATEGY="${2:-ASAP}"
TOTAL_BUDGET="${3:-1000000000}"
DAILY_BUDGET_LIMIT="${4:-1000000000}"

: "${PACING_API_BASE_URL:?PACING_API_BASE_URL is required}"
: "${PACING_HMAC_OPERATION_SERVER_CURRENT_SECRET:?operation-server secret is required}"

METHOD="PUT"
PATH_VALUE="/internal/admin/v1/campaigns/${CAMPAIGN_ID}"
CLIENT_ID="operation-server"
TIMESTAMP="$(date +%s)"
NONCE="$(cat /proc/sys/kernel/random/uuid | tr -d '-')"
START_AT="$(date -u -d '-5 minutes' '+%Y-%m-%dT%H:%M:%SZ')"
END_AT="$(date -u -d '+1 day' '+%Y-%m-%dT%H:%M:%SZ')"

BODY=$(printf \
  '{"status":"ACTIVE","startAt":"%s","endAt":"%s","pacingStrategy":"%s","totalBudget":%s,"dailyBudgetLimit":%s}' \
  "${START_AT}" \
  "${END_AT}" \
  "${PACING_STRATEGY}" \
  "${TOTAL_BUDGET}" \
  "${DAILY_BUDGET_LIMIT}")

BODY_HASH="$(printf '%s' "${BODY}" | sha256sum | awk '{print $1}')"
CANONICAL=$(printf '%s\n%s\n%s\n%s\n%s\n%s' \
  "${METHOD}" \
  "${PATH_VALUE}" \
  "${CLIENT_ID}" \
  "${TIMESTAMP}" \
  "${NONCE}" \
  "${BODY_HASH}")
SIGNATURE="$(printf '%s' "${CANONICAL}" | openssl dgst -sha256 -hmac "${PACING_HMAC_OPERATION_SERVER_CURRENT_SECRET}" -hex | awk '{print $2}')"

RESPONSE_FILE="$(mktemp)"
trap 'rm -f "${RESPONSE_FILE}"' EXIT

STATUS_CODE=$(curl -sS \
  -o "${RESPONSE_FILE}" \
  -w '%{http_code}' \
  -X "${METHOD}" \
  "${PACING_API_BASE_URL}${PATH_VALUE}" \
  -H 'Content-Type: application/json' \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Timestamp: ${TIMESTAMP}" \
  -H "X-Nonce: ${NONCE}" \
  -H "X-Signature: ${SIGNATURE}" \
  --data-binary "${BODY}")

cat "${RESPONSE_FILE}"
echo

if [[ "${STATUS_CODE}" != "200" ]]; then
  echo "Campaign upsert failed. HTTP ${STATUS_CODE}" >&2
  exit 1
fi

echo "Campaign upsert succeeded: ${CAMPAIGN_ID}"
