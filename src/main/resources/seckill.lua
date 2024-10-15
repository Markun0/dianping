-- 判断库存是否充足
if tonumber(redis.call('get', KEYS[1])) > 0 then
    -- 判断用户是否已经购买过
    if redis.call('sismember', KEYS[2], ARGV[1]) == 0 then
        -- 库存充足且用户未购买过，执行购买操作
        redis.call('decr', KEYS[1])
        redis.call('sadd', KEYS[2], ARGV[1])
        return 0
    else
        -- 用户已经购买过，返回错误信息
        return 2
    end
else
    -- 库存不足，返回错误信息
    return 1
end