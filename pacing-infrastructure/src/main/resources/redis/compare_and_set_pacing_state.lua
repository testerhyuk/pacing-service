local key = KEYS[1]

if redis.call('EXISTS', key) == 0 then
    return {'KEY_MISSING'}
end

local currentVersion = redis.call('HGET', key, 'version')
if not currentVersion then
    return {'CORRUPTED'}
end

if currentVersion ~= ARGV[1] then
    return {'VERSION_MISMATCH', currentVersion}
end

redis.call(
    'HSET',
    key,
    'pacingRate', ARGV[2],
    'updatedAtEpochMillis', ARGV[3]
)
redis.call('HINCRBY', key, 'version', 1)

local newVersion = redis.call('HGET', key, 'version')
return {'UPDATED', newVersion}
