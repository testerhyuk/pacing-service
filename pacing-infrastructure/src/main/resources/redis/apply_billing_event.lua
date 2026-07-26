local totalKey = KEYS[1]
local dailyKey = KEYS[2]
local reservationKey = KEYS[3]
local expiryKey = KEYS[4]
local eventKey = KEYS[5]

local function allFieldsMatch(key, fields, expected)
    local values = redis.call('HMGET', key, unpack(fields))

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

if redis.call('EXISTS', eventKey) == 1 then
    return eventResult('ALREADY_APPLIED')
end

if redis.call('EXISTS', totalKey) == 0
        or redis.call('EXISTS', dailyKey) == 0
        or redis.call('EXISTS', reservationKey) == 0 then
    return {'STATE_MISSING'}
end

local totalFields = {
    'totalBudget',
    'totalSpentAmount',
    'totalReservedAmount'
}
local dailyFields = {
    'dailyBudgetLimit',
    'dailySpentAmount',
    'dailyReservedAmount'
}
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

local expectedTotal = {
    ARGV[6],
    ARGV[7],
    ARGV[8]
}
local expectedDaily = {
    ARGV[9],
    ARGV[10],
    ARGV[11]
}
local expectedReservation = {
    ARGV[2],
    ARGV[3],
    ARGV[4],
    ARGV[5],
    ARGV[12],
    ARGV[13],
    ARGV[14],
    ARGV[15],
    ARGV[16]
}

if not allFieldsMatch(totalKey, totalFields, expectedTotal)
        or not allFieldsMatch(dailyKey, dailyFields, expectedDaily)
        or not allFieldsMatch(
            reservationKey,
            reservationFields,
            expectedReservation
        ) then
    return {'STATE_CONFLICT'}
end

redis.call(
    'HSET',
    totalKey,
    'totalSpentAmount', ARGV[17],
    'totalReservedAmount', ARGV[18]
)
redis.call('HINCRBY', totalKey, 'version', 1)

redis.call(
    'HSET',
    dailyKey,
    'dailySpentAmount', ARGV[19],
    'dailyReservedAmount', ARGV[20]
)
redis.call('HINCRBY', dailyKey, 'version', 1)

redis.call(
    'HSET',
    reservationKey,
    'status', ARGV[21],
    'appliedAmount', ARGV[22]
)
local nextReservationVersion = tostring(
    redis.call('HINCRBY', reservationKey, 'version', 1)
)
redis.call('ZREM', expiryKey, ARGV[2])

redis.call(
    'HSET',
    eventKey,
    'eventId', ARGV[1],
    'reservationId', ARGV[2],
    'reservationStatus', ARGV[21],
    'appliedAmount', ARGV[22],
    'reservationVersion', nextReservationVersion,
    'totalOverageAmount', ARGV[23],
    'dailyOverageAmount', ARGV[24]
)
redis.call('PEXPIRE', eventKey, ARGV[25])

return eventResult('APPLIED')
