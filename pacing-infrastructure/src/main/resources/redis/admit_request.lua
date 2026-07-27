local nonceKey = KEYS[1]
local rateLimitKey = KEYS[2]

local nonceTtlMillis = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refillPerSecond = tonumber(ARGV[3])
local rateLimitIdleTtlMillis = tonumber(ARGV[4])

if not nonceTtlMillis or nonceTtlMillis <= 0
        or not capacity or capacity <= 0
        or not refillPerSecond or refillPerSecond <= 0
        or not rateLimitIdleTtlMillis
        or rateLimitIdleTtlMillis <= 0 then
    return redis.error_reply(
        'invalid request admission configuration'
    )
end

-- nonce를 처음 사용한 요청만 저장한다.
local nonceSaved = redis.call(
    'SET',
    nonceKey,
    '1',
    'PX',
    nonceTtlMillis,
    'NX'
)

if not nonceSaved then
    return 'NONCE_REUSED'
end

-- Redis 서버 시각을 기준으로 토큰을 보충한다.
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)

local tokens = tonumber(
    redis.call('HGET', rateLimitKey, 'tokens')
)

local lastRefillMillis = tonumber(
    redis.call(
        'HGET',
        rateLimitKey,
        'lastRefillMillis'
    )
)

if not tokens or not lastRefillMillis then
    tokens = capacity
    lastRefillMillis = nowMillis
end

local elapsedMillis = math.max(
    0,
    nowMillis - lastRefillMillis
)

tokens = math.min(
    capacity,
    tokens + elapsedMillis / 1000 * refillPerSecond
)

local result

if tokens >= 1 then
    tokens = tokens - 1
    result = 'ALLOWED'
else
    result = 'RATE_LIMITED'
end

redis.call(
    'HSET',
    rateLimitKey,
    'tokens',
    tostring(tokens),
    'lastRefillMillis',
    tostring(nowMillis)
)

redis.call(
    'PEXPIRE',
    rateLimitKey,
    rateLimitIdleTtlMillis
)

return result