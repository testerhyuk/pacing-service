local totalKey = KEYS[1]
local dailyKey = KEYS[2]

local created = 0

if redis.call('EXISTS', totalKey) == 0 then
    redis.call(
        'HSET',
        totalKey,
        'totalBudget', ARGV[1],
        'totalSpentAmount', ARGV[2],
        'totalReservedAmount', ARGV[3],
        'version', '0'
    )
    created = created + 1
end

if redis.call('EXISTS', dailyKey) == 0 then
    redis.call(
        'HSET',
        dailyKey,
        'dailyBudgetLimit', ARGV[4],
        'dailySpentAmount', ARGV[5],
        'dailyReservedAmount', ARGV[6],
        'version', '0'
    )
    created = created + 1
end

return created
