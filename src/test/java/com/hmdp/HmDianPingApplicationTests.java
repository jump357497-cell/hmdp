package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        //调用原来写到service层的缓存预热方法
        //shopService.saveShop2Redis(1L,10L);

        //现在缓存预热方法写到了工具类中，所以需要修改
        Shop shop = shopService.getById(2L); //查询数据
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 2L, shop, 10L, TimeUnit.SECONDS);//提前把数据插入到缓存

    }
    @Test
    void testIdWorker() throws InterruptedException {
        //线程同步信号枪，每一个分支线程执行完，信号量-1
        CountDownLatch latch = new CountDownLatch(300);
        //每个线程创建100个订单号
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        //开启300个线程
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        //等待分支线程执行完
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }


}
