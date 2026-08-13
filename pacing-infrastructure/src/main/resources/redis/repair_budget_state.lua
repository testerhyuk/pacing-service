local totalKey = KEYS[1]
local dailyKey = KEYS[2]

if redis.call('EXISTS', totalKey) == 0
        or redis.call('EXISTS', dailyKey) == 0 then
    return {'MISSING'}
end

local totalValues = redis.call(
        'HMGET',
        totalKey,
        'totalBudget',
        'totalSpentAmount',
        'totalReservedAmount',
        'version'
)

local dailyValues = redis.call(
        'HMGET',
        dailyKey,
        'dailyBudgetLimit',
        'dailySpentAmount',
        'dailyReservedAmount',
        'version'
)

for _, value in ipairs(totalValues) do
    if not value then
        return {'CORRUPTED'}
    end
end

for _, value in ipairs(dailyValues) do
    if not value then
        return {'CORRUPTED'}
    end
end

if totalValues[4] ~= ARGV[1]
        or dailyValues[4] ~= ARGV[2] then
    return {
        'VERSION_MISMATCH',
        totalValues[4],
        dailyValues[4]
    }
end

local totalSpentAmount = tonumber(ARGV[3])
local totalReservedAmount = tonumber(ARGV[4])
local dailySpentAmount = tonumber(ARGV[5])
local dailyReservedAmount = tonumber(ARGV[6])

if not totalSpentAmount
        or not totalReservedAmount
        or not dailySpentAmount
        or not dailyReservedAmount
        or totalSpentAmount < 0
        or totalReservedAmount < 0
        or dailySpentAmount < 0
        or dailyReservedAmount < 0
        or dailySpentAmount > totalSpentAmount
        or dailyReservedAmount > totalReservedAmount then
    return {'INVALID_STATE'}
end

redis.call(
        'HSET',
        totalKey,
        'totalSpentAmount', ARGV[3],
        'totalReservedAmount', ARGV[4]
)

redis.call(
        'HSET',
        dailyKey,
        'dailySpentAmount', ARGV[5],
        'dailyReservedAmount', ARGV[6]
)

local nextTotalVersion = tostring(
        redis.call('HINCRBY', totalKey, 'version', 1)
)
local nextDailyVersion = tostring(
        redis.call('HINCRBY', dailyKey, 'version', 1)
)

return {
    'UPDATED',
    nextTotalVersion,
    nextDailyVersion
}
