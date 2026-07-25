local key = KEYS[1]

if redis.call('EXISTS', key) == 1 then
    local values = redis.call(
        'HMGET',
        key,
        'pacingRate',
        'updatedAtEpochMillis',
        'version'
    )

    if not values[1] or not values[2] or not values[3] then
        return {'CORRUPTED'}
    end

    return {'OK', values[1], values[2], values[3]}
end

redis.call(
    'HSET',
    key,
    'pacingRate', ARGV[1],
    'updatedAtEpochMillis', ARGV[2],
    'version', ARGV[3]
)

return {'OK', ARGV[1], ARGV[2], ARGV[3]}
