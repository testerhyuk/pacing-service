local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local idleTtlMillis = tonumber(ARGV[3])

if not capacity or capacity <= 0
        or not refillPerSecond or refillPerSecond <= 0
        or not idleTtlMillis or idleTtlMillis <= 0 then
    return redis.error_reply('invalid token bucket configuration')
end

local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)

local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local lastRefillMillis = tonumber(
        redis.call('HGET', key, 'lastRefillMillis')
)

if not tokens or not lastRefillMillis then
    tokens = capacity
    lastRefillMillis = nowMillis
end

local elapsedMillis = math.max(0, nowMillis - lastRefillMillis)
local refilledTokens =
        elapsedMillis / 1000 * refillPerSecond
tokens = math.min(capacity, tokens + refilledTokens)

local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end

redis.call(
    'HSET',
    key,
    'tokens', tostring(tokens),
    'lastRefillMillis', tostring(nowMillis)
)
redis.call('PEXPIRE', key, idleTtlMillis)

return allowed
