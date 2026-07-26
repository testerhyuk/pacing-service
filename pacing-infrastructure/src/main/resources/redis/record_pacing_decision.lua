local statsKey = KEYS[1]
local currentRequestIdsKey = KEYS[2]

local requestId = ARGV[1]
local passed = ARGV[2]
local ttlSeconds = tonumber(ARGV[3])

for index = 2, #KEYS do
    if redis.call('SISMEMBER', KEYS[index], requestId) == 1 then
        return 0
    end
end

redis.call('SADD', currentRequestIdsKey, requestId)
redis.call('HINCRBY', statsKey, 'decisionCount', 1)

if passed == '1' then
    redis.call('HINCRBY', statsKey, 'passCount', 1)
end

redis.call('EXPIRE', statsKey, ttlSeconds)
redis.call('EXPIRE', currentRequestIdsKey, ttlSeconds)

return 1
