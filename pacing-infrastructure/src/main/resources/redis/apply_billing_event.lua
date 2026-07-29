local totalKey = KEYS[1]
local dailyKey = KEYS[2]
local reservationKey = KEYS[3]
local expiryKey = KEYS[4]
local eventKey = KEYS[5]

local function allFieldsMatch(key, fields, expected)
    local values = redis.call(
        'HMGET',
        key,
        unpack(fields)
    )

    for index, value in ipairs(values) do
        if not value or value ~= expected[index] then
            return false
        end
    end

    return true
end

local function eventResult(status)
    local values = redis.call(
        'HMGET',
        eventKey,
        'eventId',
        'reservationId',
        'reservationStatus',
        'appliedAmount',
        'reservationVersion',
        'totalOverageAmount',
        'dailyOverageAmount'
    )

    for _, value in ipairs(values) do
        if not value then
            return {'CORRUPTED'}
        end
    end

    return {
        status,
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        values[6],
        values[7]
    }
end

local function requiredNumber(key, field)
    local value = redis.call(
        'HGET',
        key,
        field
    )

    if not value then
        return nil
    end

    return tonumber(value)
end


-- =========================================================
-- 1. 동일 Billing Event 중복 처리
-- =========================================================

if redis.call('EXISTS', eventKey) == 1 then
    return eventResult('ALREADY_APPLIED')
end


-- =========================================================
-- 2. 필요한 상태 존재 확인
-- =========================================================

if redis.call('EXISTS', totalKey) == 0
        or redis.call('EXISTS', dailyKey) == 0
        or redis.call('EXISTS', reservationKey) == 0 then
    return {'STATE_MISSING'}
end


-- =========================================================
-- 3. Reservation CAS
--
-- BudgetState 전체를 비교하지 않는다.
-- 서로 다른 reservation이 같은 campaign의 budget을
-- 변경하는 것은 정상적인 병렬 처리이기 때문이다.
--
-- 대신 동일 reservation이 처리 도중 변경된 경우에는
-- 기존처럼 STATE_CONFLICT로 retry한다.
-- =========================================================

local reservationFields = {
    'reservationId',
    'campaignId',
    'budgetDate',
    'amount',
    'appliedAmount',
    'status',
    'reservedAtEpochMillis',
    'expiresAtEpochMillis',
    'version'
}

local expectedReservation = {
    ARGV[2],
    ARGV[3],
    ARGV[4],
    ARGV[5],
    ARGV[6],
    ARGV[7],
    ARGV[8],
    ARGV[9],
    ARGV[10]
}

if not allFieldsMatch(
        reservationKey,
        reservationFields,
        expectedReservation
) then
    return {'STATE_CONFLICT'}
end


-- =========================================================
-- 4. 현재 BudgetState를 Redis 안에서 읽는다.
--
-- Java가 읽었던 예전 절대값을 사용하지 않는다.
-- Lua 실행 시점의 최신 상태를 사용한다.
-- =========================================================

local totalBudget =
    requiredNumber(totalKey, 'totalBudget')

local totalSpent =
    requiredNumber(totalKey, 'totalSpentAmount')

local totalReserved =
    requiredNumber(totalKey, 'totalReservedAmount')

local dailyBudgetLimit =
    requiredNumber(dailyKey, 'dailyBudgetLimit')

local dailySpent =
    requiredNumber(dailyKey, 'dailySpentAmount')

local dailyReserved =
    requiredNumber(dailyKey, 'dailyReservedAmount')

if not totalBudget
        or not totalSpent
        or not totalReserved
        or not dailyBudgetLimit
        or not dailySpent
        or not dailyReserved then
    return {'INVALID_STATE'}
end


-- =========================================================
-- 5. Java에서 계산한 변화량
-- =========================================================

local totalSpentDelta = tonumber(ARGV[11])
local totalReservedDelta = tonumber(ARGV[12])
local dailySpentDelta = tonumber(ARGV[13])
local dailyReservedDelta = tonumber(ARGV[14])

if not totalSpentDelta
        or not totalReservedDelta
        or not dailySpentDelta
        or not dailyReservedDelta then
    return {'INVALID_STATE'}
end


-- =========================================================
-- 6. 최신 Redis 값 기준 다음 상태 계산
-- =========================================================

local nextTotalSpent =
    totalSpent + totalSpentDelta

local nextTotalReserved =
    totalReserved + totalReservedDelta

local nextDailySpent =
    dailySpent + dailySpentDelta

local nextDailyReserved =
    dailyReserved + dailyReservedDelta


-- 음수 상태는 절대 허용하지 않는다.
if nextTotalSpent < 0
        or nextTotalReserved < 0
        or nextDailySpent < 0
        or nextDailyReserved < 0 then
    return {'INVALID_STATE'}
end


-- BudgetState의 기본 invariant 유지
if nextDailySpent > nextTotalSpent
        or nextDailyReserved > nextTotalReserved then
    return {'INVALID_STATE'}
end


-- =========================================================
-- 7. 공유 BudgetState를 delta로 원자 반영
-- =========================================================

redis.call(
    'HINCRBY',
    totalKey,
    'totalSpentAmount',
    ARGV[11]
)

redis.call(
    'HINCRBY',
    totalKey,
    'totalReservedAmount',
    ARGV[12]
)

redis.call(
    'HINCRBY',
    totalKey,
    'version',
    1
)

redis.call(
    'HINCRBY',
    dailyKey,
    'dailySpentAmount',
    ARGV[13]
)

redis.call(
    'HINCRBY',
    dailyKey,
    'dailyReservedAmount',
    ARGV[14]
)

redis.call(
    'HINCRBY',
    dailyKey,
    'version',
    1
)


-- =========================================================
-- 8. Reservation 상태 변경
-- =========================================================

redis.call(
    'HSET',
    reservationKey,
    'status', ARGV[15],
    'appliedAmount', ARGV[16]
)

local nextReservationVersion = tostring(
    redis.call(
        'HINCRBY',
        reservationKey,
        'version',
        1
    )
)

redis.call(
    'ZREM',
    expiryKey,
    ARGV[2]
)


-- =========================================================
-- 9. 실제 반영 후 상태 기준 overage 계산
--
-- Java에서 읽었던 stale BudgetState가 아니라
-- 이 Lua가 실제 반영한 최신 상태로 계산한다.
-- =========================================================

local totalEffectiveSpend =
    nextTotalSpent + nextTotalReserved

local dailyEffectiveSpend =
    nextDailySpent + nextDailyReserved

local totalOverage = 0
local dailyOverage = 0

if totalEffectiveSpend > totalBudget then
    totalOverage =
        totalEffectiveSpend - totalBudget
end

if dailyEffectiveSpend > dailyBudgetLimit then
    dailyOverage =
        dailyEffectiveSpend - dailyBudgetLimit
end


-- =========================================================
-- 10. 처리 Event 저장
-- =========================================================

redis.call(
    'HSET',
    eventKey,
    'eventId', ARGV[1],
    'reservationId', ARGV[2],
    'reservationStatus', ARGV[15],
    'appliedAmount', ARGV[16],
    'reservationVersion', nextReservationVersion,
    'totalOverageAmount', tostring(totalOverage),
    'dailyOverageAmount', tostring(dailyOverage)
)

redis.call(
    'PEXPIRE',
    eventKey,
    ARGV[17]
)

redis.call(
    'PEXPIRE',
    reservationKey,
    ARGV[18]
)

return eventResult('APPLIED')
