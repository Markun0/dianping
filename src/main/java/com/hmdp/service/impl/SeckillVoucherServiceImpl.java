package com.hmdp.service.impl;

import com.google.common.util.concurrent.RateLimiter;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

     @Resource
     private IVoucherOrderService voucherOrderService;
     @Resource
     private RedisWorker redisWorker;
//    @Autowired
//    private RedissonClient redissonClient;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private RateLimiter rateLimiter=RateLimiter.create(10);
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    @Override
    public Result seckillVoucher(Long voucherId){
        // 令牌桶算法， 限流
        if(!rateLimiter.tryAcquire(1000, TimeUnit.MICROSECONDS)){
            return Result.fail("网络繁忙，请重试");
        }
        // 1. 执行lua脚本
        Long result = redisTemplate.execute(SECKILL_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                UserHolder.getUser().getId().toString());
        // 2. 判断结果是为0
        // 2.1 不为0,代表没有购买资格
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1? "库存不足" : "一人一单");
        }
        // 2.1 为0, 有购买资格, 把订单信息保存到redis的阻塞队列中
        Long orderId = redisWorker.nextId("order");
        // 保存订单到消息队列中
        rabbitTemplate.convertAndSend("seckill", orderId);
        // 3. 返回订单id
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠卷
//        SeckillVoucher voucher = getById(voucherId);
//        // 2. 判断优惠卷的秒杀是否开始或结束、库存是否充足
//        Integer stock = voucher.getStock();
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || stock < 1 || voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            if(voucher.getBeginTime().isAfter(LocalDateTime.now()))
//                return Result.fail("优惠卷尚未开始");
//            else if(voucher.getEndTime().isBefore(LocalDateTime.now()))
//                return Result.fail("优惠卷已结束");
//            else
//                return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, redisTemplate);
//        // 3. 获取锁
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("一人一单");
//        }
//        try {
//            // 获取代理对象（事务）
//            ISeckillVoucherService proxy = (ISeckillVoucherService) AopContext.currentProxy();
//            return Result.ok(proxy.createVoucherOrder(voucherId));
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }
//
    @RabbitListener(bindings = @QueueBinding(value = @Queue(name = "fanout.queue"), exchange = @Exchange(name = "dianping.fanout"), key = "seckill"))
    @Transactional
    public void createVoucherOrder(Long voucherId) {
        // 3. 根据用户id和优惠卷id查询订单
        Long userId = UserHolder.getUser().getId();
        int count = voucherOrderService.query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0)
            return ;
        // 4.扣减库存, 乐观锁解决超卖
        boolean success = update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        // 5. 生成订单
        VoucherOrder order = new VoucherOrder();
        // 5.1 订单id
        long orderId = redisWorker.nextId("order");
        order.setId(orderId);
        // 5.2 代金卷id
        order.setVoucherId(voucherId);
        // 5.3 用户id
        order.setUserId(userId);
        voucherOrderService.save(order);
        return ;
    }
}
