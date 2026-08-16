#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${PACING_ENV_FILE:-${PROJECT_ROOT}/.env.aws-load}"
DATA_ENV_FILE="${PACING_DATA_ENV_FILE:-${PROJECT_ROOT}/.env.aws-data-query}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Environment file not found: ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

# PostgreSQL/Redis 최종 정합성 검증에 필요한 데이터 서버 접속 정보다.
if [[ -f "${DATA_ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${DATA_ENV_FILE}"
  set +a
fi

DECISION_RATE="${1:-1000}"
RESERVATION_RATE="${2:-400}"
BILLING_RATE="${3:-400}"
DURATION_SECONDS="${4:-120}"
AMOUNT="${5:-10}"
TIMEOUT_SECONDS="${6:-300}"

: "${PACING_API_BASE_URL:?PACING_API_BASE_URL is required}"
: "${PACING_WORKER_BASE_URL:?PACING_WORKER_BASE_URL is required}"
: "${PACING_KAFKA_BOOTSTRAP_SERVERS:?PACING_KAFKA_BOOTSTRAP_SERVERS is required}"
: "${PACING_DATA_PRIVATE_IP:?PACING_DATA_PRIVATE_IP is required}"
: "${PACING_POSTGRES_DATABASE:?PACING_POSTGRES_DATABASE is required}"
: "${PACING_POSTGRES_USERNAME:?PACING_POSTGRES_USERNAME is required}"
: "${PACING_POSTGRES_PASSWORD:?PACING_POSTGRES_PASSWORD is required}"
: "${PACING_REDIS_PASSWORD:?PACING_REDIS_PASSWORD is required}"
: "${PACING_HMAC_AD_SERVER_CURRENT_SECRET:?ad-server secret is required}"
: "${PACING_HMAC_AUCTION_SERVER_CURRENT_SECRET:?auction-server secret is required}"
: "${PACING_HMAC_OPERATION_SERVER_CURRENT_SECRET:?operation-server secret is required}"

for value in \
  "${DECISION_RATE}" \
  "${RESERVATION_RATE}" \
  "${BILLING_RATE}" \
  "${DURATION_SECONDS}" \
  "${AMOUNT}" \
  "${TIMEOUT_SECONDS}"; do
  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "All numeric arguments must be positive integers: ${value}" >&2
    exit 1
  fi
done

if (( RESERVATION_RATE < BILLING_RATE )); then
  echo "Reservation rate must be greater than or equal to billing rate." >&2
  exit 1
fi

RUN_ID="$(date -u '+%Y%m%d%H%M%S')-$(printf '%04x' "$((RANDOM % 65536))")"
CAMPAIGN_ID="aws-full-campaign-${RUN_ID}"
RESERVATION_PREFIX="aws-full-reservation-${RUN_ID}"
EVENT_PREFIX="aws-full-billing-${RUN_ID}"
TOTAL_EVENTS=$((BILLING_RATE * DURATION_SECONDS))
EXPECTED_SPEND=$((TOTAL_EVENTS * AMOUNT))
TEST_BUDGET=$((EXPECTED_SPEND * 4))
RESERVATION_LEAD_SECONDS=5

DECISION_CONTAINER="pacing-aws-decision-${RUN_ID}"
RESERVATION_CONTAINER="pacing-aws-reservation-${RUN_ID}"
REPORT_DIR="${PROJECT_ROOT}/build/reports/aws-full-system/${RUN_ID}"
DECISION_LOG="${REPORT_DIR}/decision.log"
RESERVATION_LOG="${REPORT_DIR}/reservation.log"

mkdir -p "${REPORT_DIR}"

cleanup() {
  docker rm -f "${DECISION_CONTAINER}" "${RESERVATION_CONTAINER}" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

postgres_scalar() {
  local sql="$1"
  docker run --rm --network bridge \
    -e "PGPASSWORD=${PACING_POSTGRES_PASSWORD}" \
    postgres:16.14-alpine \
    psql \
    -h "${PACING_DATA_PRIVATE_IP}" \
    -U "${PACING_POSTGRES_USERNAME}" \
    -d "${PACING_POSTGRES_DATABASE}" \
    -Atq \
    -c "${sql}" | tr -d '\r' | tail -n 1
}

redis_scalar() {
  docker run --rm --network bridge \
    redis:7.4.9-alpine \
    redis-cli \
    --no-auth-warning \
    -h "${PACING_DATA_PRIVATE_IP}" \
    -a "${PACING_REDIS_PASSWORD}" \
    "$@" | tr -d '\r' | tail -n 1
}

kafka_lag() {
  docker run --rm --network bridge \
    apache/kafka:4.3.1 \
    /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "${PACING_KAFKA_BOOTSTRAP_SERVERS}" \
    --describe \
    --group pacing-worker-billing-v1 2>/dev/null | awk '
      $1 == "pacing-worker-billing-v1" && $6 ~ /^[0-9]+$/ { total += $6 }
      END { print total + 0 }
    '
}

assert_equal() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "${expected}" != "${actual}" ]]; then
    echo "${message}: expected=${expected}, actual=${actual}" >&2
    exit 1
  fi
}

wait_for_value() {
  local description="$1"
  local expected="$2"
  local command="$3"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  local actual=0

  while (( SECONDS < deadline )); do
    actual="$(eval "${command}")"
    echo "${description}: ${actual} / ${expected}"
    if [[ "${actual}" == "${expected}" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for ${description}: ${actual}/${expected}" >&2
  return 1
}

echo "============================================"
echo " AWS Full-System Load Test"
echo "============================================"
echo "campaign:          ${CAMPAIGN_ID}"
echo "decision:          ${DECISION_RATE} req/s"
echo "reservation:       ${RESERVATION_RATE} req/s"
echo "billing:           ${BILLING_RATE} events/s"
echo "duration:          ${DURATION_SECONDS} sec"
echo "billing events:    ${TOTAL_EVENTS}"
echo "expected spend:    ${EXPECTED_SPEND}"
echo "report directory:  ${REPORT_DIR}"

curl -fsS "${PACING_API_BASE_URL}/actuator/health" >/dev/null
curl -fsS "${PACING_WORKER_BASE_URL}/actuator/health" >/dev/null

INITIAL_LAG="$(kafka_lag)"
assert_equal 0 "${INITIAL_LAG}" \
  "Kafka lag exists before the test"

echo
echo "==> 1. Create campaign"
"${SCRIPT_DIR}/upsert-campaign.sh" \
  "${CAMPAIGN_ID}" ASAP "${TEST_BUDGET}" "${TEST_BUDGET}" >/dev/null

echo
echo "==> 2. Start reservation load"
docker run --rm \
  --name "${RESERVATION_CONTAINER}" \
  --network bridge \
  -e "BASE_URL=${PACING_API_BASE_URL}" \
  -e "CLIENT_ID=auction-server" \
  -e "SECRET=${PACING_HMAC_AUCTION_SERVER_CURRENT_SECRET}" \
  -e "CAMPAIGN_ID=${CAMPAIGN_ID}" \
  -e "RESERVATION_PREFIX=${RESERVATION_PREFIX}" \
  -e "AMOUNT=${AMOUNT}" \
  -e "TOTAL_RESERVATIONS=${TOTAL_EVENTS}" \
  -e "RATE=${RESERVATION_RATE}" \
  -e "PRE_ALLOCATED_VUS=100" \
  -e "MAX_VUS=300" \
  -v "${PROJECT_ROOT}/scripts/load:/scripts:ro" \
  grafana/k6:latest \
  run /scripts/full-system-reservation-setup.js \
  >"${RESERVATION_LOG}" 2>&1 &
RESERVATION_PID=$!

sleep "${RESERVATION_LEAD_SECONDS}"

echo
echo "==> 3. Start decision load"
docker run --rm \
  --name "${DECISION_CONTAINER}" \
  --network bridge \
  -e "BASE_URL=${PACING_API_BASE_URL}" \
  -e "CLIENT_ID=ad-server" \
  -e "SECRET=${PACING_HMAC_AD_SERVER_CURRENT_SECRET}" \
  -e "CAMPAIGN_ID=${CAMPAIGN_ID}" \
  -e "RATE=${DECISION_RATE}" \
  -e "DURATION=${DURATION_SECONDS}s" \
  -e "PRE_ALLOCATED_VUS=200" \
  -e "MAX_VUS=400" \
  -v "${PROJECT_ROOT}/scripts/load:/scripts:ro" \
  grafana/k6:latest \
  run /scripts/pacing-decision.js \
  >"${DECISION_LOG}" 2>&1 &
DECISION_PID=$!

echo
echo "==> 4. Publish billing load while both API loads are running"
python3 "${SCRIPT_DIR}/publish_billing_load.py" \
  --bootstrap-server "${PACING_KAFKA_BOOTSTRAP_SERVERS}" \
  --reservation-prefix "${RESERVATION_PREFIX}" \
  --event-prefix "${EVENT_PREFIX}" \
  --event-count "${TOTAL_EVENTS}" \
  --rate "${BILLING_RATE}" \
  --amount "${AMOUNT}" \
  --redis-host "${PACING_DATA_PRIVATE_IP}" \
  --redis-password "${PACING_REDIS_PASSWORD}" \
  --campaign-id "${CAMPAIGN_ID}" \
  --reservation-wait-timeout "${TIMEOUT_SECONDS}"

echo
echo "==> 5. Wait for k6 tests"
DECISION_EXIT=0
RESERVATION_EXIT=0
wait "${DECISION_PID}" || DECISION_EXIT=$?
wait "${RESERVATION_PID}" || RESERVATION_EXIT=$?

cat "${DECISION_LOG}"
cat "${RESERVATION_LOG}"

if (( RESERVATION_EXIT != 0 )); then
  echo "Reservation load failed: exit=${RESERVATION_EXIT}" >&2
  exit 1
fi

echo
echo "==> 6. Wait for worker and persistence"
EVENT_PATTERN="${EVENT_PREFIX}-%"
COMPLETED_SQL="SELECT COUNT(*) FROM billing_event WHERE event_id LIKE '${EVENT_PATTERN}' AND processing_status = 'COMPLETED';"
wait_for_value \
  "worker completed" \
  "${TOTAL_EVENTS}" \
  "postgres_scalar \"${COMPLETED_SQL}\""

echo
echo "==> 7. Wait for Kafka lag"
DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
FINAL_LAG=-1
while (( SECONDS < DEADLINE )); do
  FINAL_LAG="$(kafka_lag)"
  echo "Kafka lag: ${FINAL_LAG}"
  if [[ "${FINAL_LAG}" == "0" ]]; then
    break
  fi
  sleep 2
done
assert_equal 0 "${FINAL_LAG}" "Kafka lag did not reach zero"

echo
echo "==> 8. Verify PostgreSQL"
RESERVATION_COUNT="$(postgres_scalar "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = '${CAMPAIGN_ID}';")"
CONFIRMED_COUNT="$(postgres_scalar "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = '${CAMPAIGN_ID}' AND status = 'CONFIRMED';")"
RESERVED_COUNT="$(postgres_scalar "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = '${CAMPAIGN_ID}' AND status = 'RESERVED';")"
APPLIED_AMOUNT="$(postgres_scalar "SELECT COALESCE(SUM(applied_amount), 0) FROM budget_reservation WHERE campaign_id = '${CAMPAIGN_ID}';")"
BILLING_COUNT="$(postgres_scalar "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE '${EVENT_PATTERN}';")"
DEAD_LETTER_COUNT="$(postgres_scalar "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE '${EVENT_PATTERN}' AND processing_status = 'DEAD_LETTER';")"
CONFIRMED_RESULT_COUNT="$(postgres_scalar "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE '${EVENT_PATTERN}' AND processing_status = 'COMPLETED' AND result_status = 'CONFIRMED';")"
TOTAL_OVERAGE="$(postgres_scalar "SELECT COALESCE(SUM(total_overage_amount), 0) FROM billing_event WHERE event_id LIKE '${EVENT_PATTERN}';")"
DAILY_OVERAGE="$(postgres_scalar "SELECT COALESCE(SUM(daily_overage_amount), 0) FROM billing_event WHERE event_id LIKE '${EVENT_PATTERN}';")"
BUDGET_DATE="$(postgres_scalar "SELECT MIN(budget_date)::text FROM budget_reservation WHERE campaign_id = '${CAMPAIGN_ID}';")"

assert_equal "${TOTAL_EVENTS}" "${RESERVATION_COUNT}" "Reservation count mismatch"
assert_equal "${TOTAL_EVENTS}" "${CONFIRMED_COUNT}" "Confirmed reservation count mismatch"
assert_equal 0 "${RESERVED_COUNT}" "Reserved rows remain"
assert_equal "${EXPECTED_SPEND}" "${APPLIED_AMOUNT}" "PostgreSQL applied amount mismatch"
assert_equal "${TOTAL_EVENTS}" "${BILLING_COUNT}" "Billing event count mismatch"
assert_equal 0 "${DEAD_LETTER_COUNT}" "Dead-letter events exist"
assert_equal "${TOTAL_EVENTS}" "${CONFIRMED_RESULT_COUNT}" "Confirmed billing result count mismatch"
assert_equal 0 "${TOTAL_OVERAGE}" "Total budget overage detected"
assert_equal 0 "${DAILY_OVERAGE}" "Daily budget overage detected"

echo
echo "==> 9. Verify Redis"
ENCODED_CAMPAIGN_ID="$(printf '%s' "${CAMPAIGN_ID}" | base64 | tr '+/' '-_' | tr -d '=\n\r')"
TOTAL_BUDGET_KEY="pacing:budget:total:{${ENCODED_CAMPAIGN_ID}}"
DAILY_BUDGET_KEY="pacing:budget:daily:{${ENCODED_CAMPAIGN_ID}}:${BUDGET_DATE}"
REDIS_TOTAL_SPENT="$(redis_scalar HGET "${TOTAL_BUDGET_KEY}" totalSpentAmount)"
REDIS_TOTAL_RESERVED="$(redis_scalar HGET "${TOTAL_BUDGET_KEY}" totalReservedAmount)"
REDIS_DAILY_SPENT="$(redis_scalar HGET "${DAILY_BUDGET_KEY}" dailySpentAmount)"
REDIS_DAILY_RESERVED="$(redis_scalar HGET "${DAILY_BUDGET_KEY}" dailyReservedAmount)"

assert_equal "${EXPECTED_SPEND}" "${REDIS_TOTAL_SPENT}" "Redis total spent mismatch"
assert_equal 0 "${REDIS_TOTAL_RESERVED}" "Redis total reserved must be zero"
assert_equal "${EXPECTED_SPEND}" "${REDIS_DAILY_SPENT}" "Redis daily spent mismatch"
assert_equal 0 "${REDIS_DAILY_RESERVED}" "Redis daily reserved must be zero"
assert_equal "${APPLIED_AMOUNT}" "${REDIS_TOTAL_SPENT}" "PostgreSQL and Redis total spend differ"

# 성능 임계치가 실패하더라도 위의 정합성 검증은 모두 수행한다.
# 최종 결과에서 성능 실패를 별도로 보고한다.
if (( DECISION_EXIT != 0 )); then
  echo "Decision load failed its performance thresholds: exit=${DECISION_EXIT}" >&2
  exit 1
fi

echo
echo "============================================"
echo " Full-System Load Test SUCCESS"
echo "============================================"
echo "campaign:             ${CAMPAIGN_ID}"
echo "decision rate:        ${DECISION_RATE} req/s"
echo "reservation rate:     ${RESERVATION_RATE} req/s"
echo "billing rate:         ${BILLING_RATE} events/s"
echo "billing events:       ${BILLING_COUNT}"
echo "confirmed:            ${CONFIRMED_COUNT}"
echo "dead letter:          ${DEAD_LETTER_COUNT}"
echo "Kafka lag:            ${FINAL_LAG}"
echo "PostgreSQL applied:   ${APPLIED_AMOUNT}"
echo "Redis total spent:    ${REDIS_TOTAL_SPENT}"
echo "Redis reserved:       ${REDIS_TOTAL_RESERVED}"
echo "expected spend:       ${EXPECTED_SPEND}"
echo "reports:              ${REPORT_DIR}"
