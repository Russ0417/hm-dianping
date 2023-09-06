package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService seckillVoucherService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedisIdWorker redisIdWorker;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        //先获取用户信息,然后对整个创建订单的方法加锁,使用事务提交数据后释放锁,从而确保线程安全,这里createOrder在事务中
        Long userId = UserHolder.getUser().getId();

//        方案1 使用synchronized关键字加锁
//        synchronized (userId.toString().intern()) {
//            //获取当前AOP代理对象（事务相关） 这样调用createOrder方法的事务才可以生效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        }

//        方案2 使用自定义Redis分布式锁 设置key为订单+用户Id避免同一用户重复下单,设置超时时间，避免锁为释放造成死锁
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        boolean success = lock.tryLock(1200); //获取锁
        if (!success) {
            return Result.fail("请勿重复下单");
        }
        try {
            //获取当前AOP代理对象（事务相关） 这样调用createOrder方法的事务才可以生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(voucherId);
        } finally {
//            手动释放锁
            lock.unLock();
        }
    }

    /**
     * 创建优惠券订单
     *
     * @param voucherId 优惠券id
     * @return
     */
    @Transactional
    public Result createOrder(Long voucherId) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("当前用户只能抢购一张优惠券");
        }
        //6，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)     //更新时添加对库存前后的校验
                .update();
        if (!success) {
            return Result.fail("当前库存不足！");
        }

        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.设置订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.设置用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //最后返回订单id
        return Result.ok(orderId);
    }

}
