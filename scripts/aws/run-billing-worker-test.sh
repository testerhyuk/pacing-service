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

RESERVATION_PREFIX="${1:?reservationPrefix is required}"
EVENT_COUNT="${2:-12000}"
RATE="${3:-400}"
AMOUNT="${4:-10}"
TIMEOUT_SECONDS="${5:-300}"
EVENT_PREFIX="aws-billing-$(date -u '+%Y%m%d%H%M%S')"

: "${PACING_WORKER_BASE_URL:?PACING_WORKER_BASE_URL is required}"
: "${PACING_KAFKA_BOOTSTRAP_SERVERS:?PACING_KAFKA_BOOTSTRAP_SERVERS is required}"

get_applied_count() {
  curl -fsS "${PACING_WORKER_BASE_URL}/actuator/prometheus" | awk '
    /^pacing_worker_billing_seconds_count\{/ &&
    /eventType="CHARGED"/ && /status="APPLIED"/ { total += $2 }
    END { printf "%.0f", total + 0 }
  '
}

get_dead_letter_count() {
  curl -fsS "${PACING_WORKER_BASE_URL}/actuator/prometheus" | awk '
    /^pacing_worker_billing_dlt_total\{/ { total += $2 }
    END { printf "%.0f", total + 0 }
  '
}

curl -fsS "${PACING_WORKER_BASE_URL}/actuator/health" >/dev/null

BEFORE_APPLIED="$(get_applied_count)"
BEFORE_DLT="$(get_dead_letter_count)"

echo "AWS Kafka billing and worker test"
echo "worker:       ${PACING_WORKER_BASE_URL}"
echo "kafka:        ${PACING_KAFKA_BOOTSTRAP_SERVERS}"
echo "reservations: ${RESERVATION_PREFIX}"
echo "events:       ${EVENT_COUNT}"
echo "rate:         ${RATE} events/s"

python3 "${SCRIPT_DIR}/publish_billing_load.py" \
  --bootstrap-server "${PACING_KAFKA_BOOTSTRAP_SERVERS}" \
  --reservation-prefix "${RESERVATION_PREFIX}" \
  --event-prefix "${EVENT_PREFIX}" \
  --event-count "${EVENT_COUNT}" \
  --rate "${RATE}" \
  --amount "${AMOUNT}"

DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < DEADLINE )); do
  CURRENT_APPLIED="$(get_applied_count)"
  APPLIED_DELTA=$((CURRENT_APPLIED - BEFORE_APPLIED))
  echo "worker applied: ${APPLIED_DELTA} / ${EVENT_COUNT}"

  if (( APPLIED_DELTA >= EVENT_COUNT )); then
    break
  fi
  sleep 2
done

CURRENT_APPLIED="$(get_applied_count)"
CURRENT_DLT="$(get_dead_letter_count)"
APPLIED_DELTA=$((CURRENT_APPLIED - BEFORE_APPLIED))
DLT_DELTA=$((CURRENT_DLT - BEFORE_DLT))

if (( APPLIED_DELTA < EVENT_COUNT )); then
  echo "Worker did not apply all billing events: ${APPLIED_DELTA}/${EVENT_COUNT}" >&2
  exit 1
fi

if (( DLT_DELTA != 0 )); then
  echo "Dead-letter events detected: ${DLT_DELTA}" >&2
  exit 1
fi

echo "Billing worker test succeeded."
echo "EVENT_PREFIX=${EVENT_PREFIX}"
echo "APPLIED=${APPLIED_DELTA}"
echo "DEAD_LETTER=${DLT_DELTA}"
