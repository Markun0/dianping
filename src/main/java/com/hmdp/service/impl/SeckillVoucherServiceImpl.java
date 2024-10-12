package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠卷
        SeckillVoucher voucher = getById(voucherId);
        // 2. 判断优惠卷是否存在
        if (voucher == null) {
            return Result.fail("优惠卷不存在");
        }
        // 3. 判断优惠卷的秒杀是否开始或结束、库存是否充足
        Integer stock = voucher.getStock();
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || stock < 1 || voucher.getEndTime().isBefore(LocalDateTime.now())) {
            if(voucher.getBeginTime().isAfter(LocalDateTime.now()))
                return Result.fail("优惠卷尚未开始");
            else if(voucher.getEndTime().isBefore(LocalDateTime.now()))
                return Result.fail("优惠卷已结束");
            else
                return Result.fail("库存不足");
        }
        // 4.扣减库存, 乐观锁解决超卖
        boolean success = update().setSql("stock = stock - 1").eq("voucher_id", voucherId).eq("stock", stock).update();
        if(!success){
            // 扣减失败
            return Result.fail("下单失败");
        }
        // 5. 生成订单
        VoucherOrder order = new VoucherOrder();
        // 5.1 订单id
        long orderId = redisWorker.nextId("order");
        order.setId(orderId);
        // 5.2 代金卷id
        order.setVoucherId(voucherId);
        // 5.3 用户id
        order.setUserId(UserHolder.getUser().getId());
        voucherOrderService.save(order);
        // 6. 返回订单id
        return Result.ok(orderId);
    }
}
