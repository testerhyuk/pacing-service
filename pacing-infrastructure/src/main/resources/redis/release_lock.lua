local key = KEYS[1]
local token = ARGV[1]

if redis.call('GET', key) == token then
    return redis.call('DEL', key)
end

return 0
