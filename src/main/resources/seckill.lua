--优惠券Id
local voucherId = ARGV[1]
--用户Id
local userId = ARGV[2]
--订单Id
local orderId =ARGV[3]

--库存key
local stockKey = 'seckill:stock:' .. voucherId
--订单key
local orderKey = 'seckill:order:' .. voucherId

--判断库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
--判断是否二次下单
if (redis.call('sismember', orderKey, userId)) then
    return 2
end

--库存-1，并将生成订单添加到Redis队列等待写入数据库
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

--发送消息到Redis的Stream队列中 XADD Stream.orders * key1 value1
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0