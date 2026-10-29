-- Counts one request in a fixed window that starts with the first request. Runs atomically, so the key can never
-- be left without a TTL, which would keep its user limited for good.
-- KEYS[1]: the counter; ARGV[1]: length of the window in seconds. Returns {count in the window, seconds left}.
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return {count, redis.call('TTL', KEYS[1])}
