local totalKey = KEYS[1]
local dailyKey = KEYS[2]

local totalExists = redis.call('EXISTS', totalKey)
local dailyExists = redis.call('EXISTS', dailyKey)

if totalExists == 0 and dailyExists == 0 then
    return {'MISSING_BOTH'}
end

if totalExists == 0 then
    return {'MISSING_TOTAL'}
end

if dailyExists == 0 then
    return {'MISSING_DAILY'}
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

return {
    'FOUND',
    totalValues[1],
    totalValues[2],
    totalValues[3],
    dailyValues[1],
    dailyValues[2],
    dailyValues[3],
    totalValues[4],
    dailyValues[4]
}
