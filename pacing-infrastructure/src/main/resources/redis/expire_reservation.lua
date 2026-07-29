local totalKey = KEYS[1]
local dailyKey = KEYS[2]
local reservationKey = KEYS[3]
local expiryKey = KEYS[4]

local function allFieldsMatch(key, fields, expected)
    local values = redis.call('HMGET', key, unpack(fields))

    for index, value in ipairs(values) do
        if not value or value ~= expected[index] then
            return false
        end
    end

    return true
end

if redis.call('EXISTS', totalKey) == 0
        or redis.call('EXISTS', dailyKey) == 0
        or redis.call('EXISTS', reservationKey) == 0 then
    return {'STATE_MISSING'}
end

local reservationStatus = redis.call(
    'HGET',
    reservationKey,
    'status'
)

if reservationStatus ~= 'RESERVED' then
    return {'SKIPPED'}
end

local expiresAt = redis.call(
    'HGET',
    reservationKey,
    'expiresAtEpochMillis'
)

if not expiresAt
        or tonumber(expiresAt) > tonumber(ARGV[1]) then
    return {'NOT_DUE'}
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

if not allFieldsMatch(
        totalKey,
        totalFields,
        {ARGV[6], ARGV[7], ARGV[8]}
    ) or not allFieldsMatch(
        dailyKey,
        dailyFields,
        {ARGV[9], ARGV[10], ARGV[11]}
    ) or not allFieldsMatch(
        reservationKey,
        reservationFields,
        {
            ARGV[2],
            ARGV[3],
            ARGV[4],
            ARGV[5],
            ARGV[12],
            'RESERVED',
            ARGV[13],
            ARGV[14],
            ARGV[15]
        }
    ) then
    return {'STATE_CONFLICT'}
end

redis.call(
    'HSET',
    totalKey,
    'totalReservedAmount', ARGV[16]
)
redis.call('HINCRBY', totalKey, 'version', 1)

redis.call(
    'HSET',
    dailyKey,
    'dailyReservedAmount', ARGV[17]
)
redis.call('HINCRBY', dailyKey, 'version', 1)

redis.call(
    'HSET',
    reservationKey,
    'status', 'EXPIRED'
)
local nextVersion = tostring(
    redis.call('HINCRBY', reservationKey, 'version', 1)
)
redis.call('ZREM', expiryKey, ARGV[2])
redis.call('PEXPIRE', reservationKey, ARGV[18])

return {
    'EXPIRED',
    ARGV[2],
    nextVersion
}
