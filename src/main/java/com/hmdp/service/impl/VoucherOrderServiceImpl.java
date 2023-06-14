package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redisson;
    //获取阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //获取单个线程线程池
    private static final ExecutorService secKillOrder = Executors.newSingleThreadExecutor();

//    //该注解在该类初始化完成后执行
//    @PostConstruct
//    private void init() {
//        secKillOrder.submit(new dealWithOrderHandler());
//    }
//
//    //创建多线程任务
//    private class dealWithOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //获取订单
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //减库存
//
//
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//
//        }
//    }

    //获取lua脚本
    private static final DefaultRedisScript<Long> SECKILL_STATUS;

    static {
        SECKILL_STATUS = new DefaultRedisScript<>();
        SECKILL_STATUS.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_STATUS.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠卷
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始！");
        }
        //判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();

        //userId.toString().intern() 保证高并发情况下每次userId.toString后是同一个值
        //多服务器还会出现并发安全问题
//        synchronized (userId.toString().intern()){
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//            return proxy.getResult(voucherId);
//        }

//        //基于自定义分布式锁实现并发安全问题
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
//
//        boolean success = simpleRedisLock.tryLock(300);
//        if (!success){
//            return Result.fail("每个用户只能下一单，不能重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//            return proxy.getResult(voucherId);
//        } finally {
//            simpleRedisLock.unLock();
//        }

        //基于Redisson实现可重入锁
        RLock anyLock = redisson.getLock("order:"+userId);
        boolean success = anyLock.tryLock();
        if (!success){
            return Result.fail("每个用户只能下一单，不能重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

            return proxy.getResult(voucherId);
        } finally {
            anyLock.unlock();
        }

    }

    @Override
    @Transactional
    public Result getResult(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //判断该用户是否已经下过一单
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId);
        queryWrapper.eq(VoucherOrder::getVoucherId, voucherId);
        int count = this.count(queryWrapper);
        if (count > 0) {
            return Result.fail("用户已经下过一单");

        }
        //扣减库存
        /* 原符号       <       <=      >       >=      <>
           对应函数    lt()     le()    gt()    ge()    ne()
        */
        boolean success = iSeckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        //给秒杀券订单表添加数据
        VoucherOrder voucherOrder = new VoucherOrder();
        long voucherOrderId = redisIdWorker.nextId("voucherOrder");
        voucherOrder.setId(voucherOrderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        this.save(voucherOrder);
        return Result.ok(voucherOrderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        Long userId = UserHolder.getUser().getId();
//
//        Long result = stringRedisTemplate.execute(SECKILL_STATUS, Collections.emptyList(), voucherId.toString(), userId.toString());
//
//        int value = result.intValue();
//        if (value != 0) {
//            return Result.fail(value == 1 ? "库存不足" : "不能重复下单");
//        }
//
//        long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//
//        //扣减库存放到阻塞队列中处理
//        orderTasks.add(voucherOrder);
//
//        return Result.ok(orderId);
//    }
}
