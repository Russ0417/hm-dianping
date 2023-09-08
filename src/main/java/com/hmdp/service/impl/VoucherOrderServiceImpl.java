package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


// 基于Redis的Stream结构作为消息队列，实现异步秒杀下单
//需求:
// 1创建一个stream类型的消息队列，名为stream.orders
// 2修改之前的秒杀下单Lua脚本，在认定有抢购资格后，直接向stream.orders中添加消息，内容包含voucherld、userld、orderld
// 3项目启动时，开启一个线程任务，尝试获取stream.orders中的消息，完成下单

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService seckillVoucherService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedissonClient redissonClient;
    @Resource
    RedisIdWorker redisIdWorker;
    //读取lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 类被加载时就初始化线程，用于执行任务
     */
//    @PostConstruct
//    private void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }

    /**
     * 创建线程读取消息队列，并且不断往数据库写入订单信息
     */
//    private class VoucherOrderHandler implements Runnable {
//        /**
//         * 队列名称
//         */
//        String queueName = "stream.orders";
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //  1.获取stream消息队列订单信息 XREADGROUP GROUP g1 C1 COUNT 1 BLOCK 2000 STREAMS streams.order >
//                    VoucherOrder voucherOrder = null;
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2000)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())//从最后一个消费的消息开始
//                    );
//                    //  2.判断消息获取是否成功
//                    if (list == null || list.isEmpty()) {
//                        //  2.1 获取失败，进行下次循环
//                        continue;
//                    }
//                    //解析消息中的数据
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    BeanUtil.fillBeanWithMap(values, voucherOrder, true);
//                    //  3.获取成功，下单
//                    handleVoucherOrder(voucherOrder);
//                    //  4.ACK确认 即从PendingList中移除已经消费的消息
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//                } catch (Exception e) {
//                    handlePendingList();
//                    log.error("处理订单异常");
//                }
//            }
//        }
//
//        private void handlePendingList() {
//            while (true) {
//                try {
//                    //  1.获取pending-list中订单信息 XREADGROUP GROUP g1 C1 COUNT 1  STREAMS streams.order 0
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamOffset.create(queueName, ReadOffset.from("0"))//表达从头开始
//                    );
//                    //  2.判断消息获取是否成功
//                    if (list == null || list.isEmpty()) {
//                        //  2.1 获取失败，说明pending-list中没有为确认消息，结束循环
//                        break;
//                    }
//                    //解析消息中的数据
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //  3.获取成功，下单
//                    handleVoucherOrder(voucherOrder);
//                    //  4.ACK确认 即从PendingList中移除已经消费的消息
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//                } catch (Exception e) {
//                    log.error("处理订单异常 Pending-list错误");
//                    try {
//                        Thread.sleep(20);
//                    } catch (InterruptedException ex) {
//                        throw new RuntimeException(ex);
//                    }
//                }
//            }
//        }
//
//    }

    /**
     * 创建订单 使用Redisson提供的简单分布式锁
     *
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:voucherOrder:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //手动释放锁
            lock.unlock();
        }
    }

    //初始化代理对象
    private IVoucherOrderService proxy;

    /**
     * 秒杀券下单
     *
     * @param voucherId
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户Id
        Long userId = UserHolder.getUser().getId();
        //生成订单Id
        Long orderId = redisIdWorker.nextId("order");
        //执行lua脚本，实现对库存，超卖的判断，同时将生成订单写入Redis的阻塞队列中，等待下一步写入数据库
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString(),
//                String.valueOf(orderId));
//        if (result.intValue() != 0) {
//            return Result.fail(result.intValue() == 1 ? "库存不足" : "不可以重复下单");
//        }
        //返回用户订单信息
        return Result.ok(orderId);
    }


    /**
     * 创建优惠券订单（创建单独线程，读取Redis中的堵塞队列，异步执行写入数据库）
     *
     * @param voucherOrder 优惠券对象
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //判断一人一单
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("当前用户只能抢购一张优惠券");
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//更新时添加对库存前后的校验
                .update();
        if (!success) {
            log.error("当前库存不足");
        }
        //写入订单信息
        save(voucherOrder);
    }


}
