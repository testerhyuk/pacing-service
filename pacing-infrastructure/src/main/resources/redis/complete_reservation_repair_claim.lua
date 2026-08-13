local processingKey = KEYS[1]

local claimedMember = ARGV[1]

if not claimedMember
        or claimedMember == '' then
    return redis.error_reply('INVALID_ARGUMENT')
end

return redis.call('ZREM', processingKey, claimedMember)
