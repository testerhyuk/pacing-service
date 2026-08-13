local pendingKey = KEYS[1]
local processingKey = KEYS[2]

local eligibleBefore = tonumber(ARGV[1])
local now = tonumber(ARGV[2])
local leaseUntil = tonumber(ARGV[3])
local batchSize = tonumber(ARGV[4])
local token = ARGV[5]

if not eligibleBefore
        or not now
        or not leaseUntil
        or not batchSize
        or batchSize <= 0
        or not token
        or token == '' then
    return redis.error_reply('INVALID_ARGUMENT')
end

local expiredClaims = redis.call(
    'ZRANGEBYSCORE',
    processingKey,
    '-inf',
    now,
    'LIMIT',
    0,
    batchSize
)

for _, claimedMember in ipairs(expiredClaims) do
    local delimiter = string.find(claimedMember, '|', 1, true)

    if delimiter then
        local pendingMember = string.sub(
            claimedMember,
            delimiter + 1
        )

        if redis.call(
            'ZREM',
            processingKey,
            claimedMember
        ) == 1 then
            redis.call(
                'ZADD',
                pendingKey,
                0,
                pendingMember
            )
        end
    else
        redis.call('ZREM', processingKey, claimedMember)
    end
end

local candidates = redis.call(
    'ZRANGEBYSCORE',
    pendingKey,
    '-inf',
    eligibleBefore,
    'LIMIT',
    0,
    batchSize
)

local claimed = {}

for _, pendingMember in ipairs(candidates) do
    if redis.call('ZREM', pendingKey, pendingMember) == 1 then
        redis.call(
            'ZADD',
            processingKey,
            leaseUntil,
            token .. '|' .. pendingMember
        )
        table.insert(claimed, pendingMember)
    end
end

return claimed
