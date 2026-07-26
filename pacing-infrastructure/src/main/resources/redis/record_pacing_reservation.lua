local statsKey = KEYS[1]
local currentReservationIdsKey = KEYS[2]

local reservationId = ARGV[1]
local amount = ARGV[2]
local ttlSeconds = tonumber(ARGV[3])

for index = 2, #KEYS do
    if redis.call(
            'SISMEMBER',
            KEYS[index],
            reservationId
    ) == 1 then
        return 0
    end
end

redis.call(
        'SADD',
        currentReservationIdsKey,
        reservationId
)
redis.call(
        'HINCRBY',
        statsKey,
        'reservationCount',
        1
)
redis.call(
        'HINCRBY',
        statsKey,
        'reservedAmount',
        amount
)

redis.call('EXPIRE', statsKey, ttlSeconds)
redis.call(
    'EXPIRE',
    currentReservationIdsKey,
    ttlSeconds
)

return 1
