local reservationKey = KEYS[1]
local expiryKey = KEYS[2]

if redis.call('EXISTS', reservationKey) == 0 then
    redis.call(
        'HSET',
        reservationKey,
        'reservationId', ARGV[1],
        'campaignId', ARGV[2],
        'budgetDate', ARGV[3],
        'amount', ARGV[4],
        'appliedAmount', ARGV[5],
        'status', ARGV[6],
        'reservedAtEpochMillis', ARGV[7],
        'expiresAtEpochMillis', ARGV[8],
        'version', ARGV[9]
    )

    if ARGV[6] == 'RESERVED' then
        redis.call(
            'ZADD',
            expiryKey,
            ARGV[8],
            ARGV[1]
        )
    else
        redis.call('ZREM', expiryKey, ARGV[1])
    end

    return {'CREATED'}
end

return {'EXISTS'}
