package com.hmdp.service.impl;

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
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Resource
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //初始化阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 类被加载时就初始化线程，用于执行任务
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 创建线程读取阻塞队列，并且不断往数据库写入订单信息
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //   1 获取队列订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //   2 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }

        }


    }

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
//            手动释放锁
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
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本，实现对库存，超卖的判断，同时将生成订单写入Redis的阻塞队列中，等待下一步写入数据库
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        if (result.intValue() != 0) {
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不可以重复下单");
        }
        //将下单信息保存阻塞队列
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        // 7.2.设置用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //获取当前代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
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


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        //先获取用户信息,然后对整个创建订单的方法加锁,使用事务提交数据后释放锁,从而确保线程安全,这里createOrder在事务中
//        Long userId = UserHolder.getUser().getId();
//
////        方案1 使用synchronized关键字加锁
////        synchronized (userId.toString().intern()) {
////            //获取当前AOP代理对象（事务相关） 这样调用createOrder方法的事务才可以生效
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
////        方案2 使用自定义Redis分布式锁 设置key为订单+用户Id避免同一用户重复下单,设置超时时间，避免锁为释放造成死锁
//        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        boolean success = lock.tryLock(1200); //获取锁
//        if (!success) {
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            //获取当前AOP代理对象（事务相关） 这样调用createOrder方法的事务才可以生效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
////            手动释放锁
//            lock.unLock();
//        }
//    }

//    /**
//     * 创建优惠券订单
//     *
//     * @param voucherId 优惠券id
//     * @return
//     */
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        //5.一人一单
//        Long userId = UserHolder.getUser().getId();
//        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0) {
//            return Result.fail("当前用户只能抢购一张优惠券");
//        }
//        //6，扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)     //更新时添加对库存前后的校验
//                .update();
//        if (!success) {
//            return Result.fail("当前库存不足！");
//        }
//
//        //7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 7.1.设置订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 7.2.设置用户id
//        voucherOrder.setUserId(userId);
//        // 7.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        //最后返回订单id
//        return Result.ok(orderId);
//    }

}
