local totalKey = KEYS[1]
local dailyKey = KEYS[2]
local newTotalBudget = tonumber(ARGV[1])
local newDailyBudgetLimit = tonumber(ARGV[2])

if not newTotalBudget or not newDailyBudgetLimit
        or newTotalBudget < 0
        or newDailyBudgetLimit < 0
        or newDailyBudgetLimit > newTotalBudget then
    return {'INVALID_LIMIT'}
end

local totalExists = redis.call('EXISTS', totalKey)
local dailyExists = redis.call('EXISTS', dailyKey)

if totalExists == 0 and dailyExists == 0 then
    return {'MISSING'}
end

if totalExists == 0 then
    return {'CORRUPTED'}
end

local totalValues = redis.call(
        'HMGET',
        totalKey,
        'totalBudget',
        'totalSpentAmount',
        'totalReservedAmount',
        'version'
)

for _, value in ipairs(totalValues) do
    if not value then
        return {'CORRUPTED'}
    end
end

local oldTotalBudget = tonumber(totalValues[1])
local totalEffective = tonumber(totalValues[2])
        + tonumber(totalValues[3])

if totalEffective > newTotalBudget then
    return {'INSUFFICIENT_TOTAL'}
end

local oldDailyBudgetLimit = '-1'
if dailyExists == 1 then
    local dailyValues = redis.call(
            'HMGET',
            dailyKey,
            'dailyBudgetLimit',
            'dailySpentAmount',
            'dailyReservedAmount',
            'version'
    )

    for _, value in ipairs(dailyValues) do
        if not value then
            return {'CORRUPTED'}
        end
    end

    oldDailyBudgetLimit = dailyValues[1]
    local dailyEffective = tonumber(dailyValues[2])
            + tonumber(dailyValues[3])

    if dailyEffective > newDailyBudgetLimit then
        return {'INSUFFICIENT_DAILY'}
    end
end

redis.call(
        'HSET',
        totalKey,
        'totalBudget',
        tostring(newTotalBudget)
)
redis.call('HINCRBY', totalKey, 'version', 1)

if dailyExists == 1 then
    redis.call(
            'HSET',
            dailyKey,
            'dailyBudgetLimit',
            tostring(newDailyBudgetLimit)
    )
    redis.call('HINCRBY', dailyKey, 'version', 1)
end

return {
    'UPDATED',
    tostring(oldTotalBudget),
    tostring(oldDailyBudgetLimit)
}
