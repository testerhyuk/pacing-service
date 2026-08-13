local processingKey = KEYS[1]
local campaignPendingKey = KEYS[2]

local claimedMember = ARGV[1]
local pendingMember = ARGV[2]

if not claimedMember
        or claimedMember == ''
        or not pendingMember
        or pendingMember == '' then
    return redis.error_reply('INVALID_ARGUMENT')
end

if redis.call('ZREM', processingKey, claimedMember) == 0 then
    return 0
end

redis.call('ZREM', campaignPendingKey, pendingMember)
return 1
