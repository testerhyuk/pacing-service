local pendingKey = KEYS[1]
local processingKey = KEYS[2]

local claimedMember = ARGV[1]
local pendingMember = ARGV[2]
local retryAt = tonumber(ARGV[3])

if not claimedMember
        or claimedMember == ''
        or not pendingMember
        or pendingMember == ''
        or not retryAt then
    return redis.error_reply('INVALID_ARGUMENT')
end

if redis.call('ZREM', processingKey, claimedMember) == 0 then
    return 0
end

redis.call('ZADD', pendingKey, retryAt, pendingMember)
return 1
