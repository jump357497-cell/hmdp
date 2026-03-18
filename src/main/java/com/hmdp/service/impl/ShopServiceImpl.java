package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    @Override
    public Result queryById(Long id) {
        //调用缓存穿透方法
        //Shop shop = queryWithPassThrough(id);//方法一
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);//方法二


//        互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);//方法一

         Shop shop = cacheClient
                 .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);//方法二


        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);//方法一

//        Shop shop = cacheClient
//                 .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);//方法二
        //因为queryWithMutex方法获取到的值有可能是null值，所以可以做一个判断反馈的更加友好一点。
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        //返回
        return Result.ok(shop);
    }

   /* private Shop queryWithLogicalExpire(Long id) {
        String key=RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商品缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isBlank(shopJson)){
            //3.未命中，直接返回空
            return null;
        }

        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //取出来的对象是JSONObj 还需要转化成shop
//        Shop shop=JSONUtil.toBean((JSONObject)redisData.getData(), Shop.class);
        // 先把 data 转回 JSON 字符串，再反序列化为 Shop 对象
        Shop shop = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期，直接返回店铺信息
            return shop;
        }

        //5.2 已过期，需要缓存重建
        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        //6.2 判断是否获得锁成功
        if (isLock){
            //6.3 获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    //重建缓存
                    this.saveShop2Redis(id,30L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //6.4 未获取锁，返回过期的商铺信息
        return shop;
    }

    //抽取互斥锁缓存击穿的解决方案:在缓存穿透的基础上修改
    public Shop queryWithMutex(Long id){
        //CACHE_SHOP_KEY：常量类方式
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存   对象类型的数据既可以使用String也可以使用hash，之前演示的hash，这里使用String方式存储
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在   isNotBlank: 判断不为空    胡图工具包提供的
        //isNotBlank:里面存的只有是真正的字符串才为true，剩下的null  ""  \t\n换行   都是false
        if(StrUtil.isNotBlank((shopJson))){
            //3.存在直接返回  需要转化为java对象在返回（字符串方便展示，对象可以用到里面的数据）
            //   JSONUtil.toBean() ：java类库提供的一个方法，将json字符串转化为java对象
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return  shop;
        }

        //判断命中的是否是空值
        //   问题：把空值写入到redis中了，当我们从redis中命中的时候，那么你命中的就不一定是
        //        商铺信息了 还有可能是空值，所以你命中以后还要对结果做一个判断，判断命中的是否
        //        为空，命中的不是空代表是商铺 就可以返回商铺信息了，命中的是空则直接返回空就可以了。
        //   因为前面已经判断过有值的情况了，下面其实就2种情况，要么死null要么死空字符""，所以不等于null，
        //       一定是空字符串""。
        if(shopJson !=null){
            //返回一个错误信息
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁: 这个key不要用前面缓存的key，这个锁的key和缓存的key不是同一个。
//        String lockKey = "lock:shop" + id;  //前缀区分不同的业务，拼接上id保证每个店铺都有自己的一个锁。
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;  //前缀区分不同的业务，拼接上id保证每个店铺都有自己的一个锁。
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3失败者休眠重试
                Thread.sleep(50);
                //递归：重新执行这个查询的动作，直到获取到缓存中的数据或者获取到锁
                return queryWithMutex(id);  //return是递归出口。
            }

            //注意获取锁成功应该再次检测redis缓存是否存在，做doubleCheck,如果存在则无需重建缓存
            //从redis查询商铺缓存   对象类型的数据既可以使用String也可以使用hash，之前演示的hash，这里使用String方式存储
            String shopJson1 = stringRedisTemplate.opsForValue().get(key);

            //判断是否存在   isNotBlank: 判断不为空    胡图工具包提供的
            if(StrUtil.isNotBlank((shopJson1))){
                //存在直接返回  需要转化为java对象在返回（字符串方便展示，对象可以用到里面的数据）
                //   JSONUtil.toBean() ：java类库提供的一个方法，将json字符串转化为java对象
                shop = JSONUtil.toBean(shopJson1,Shop.class);
                return  shop;
            }
            // 补上下面这个判断：如果是空字符串 ""，也直接返回，不要去查数据库
            if(shopJson1 != null){
                return null;
            }

            //4.4锁获取成功并且缓存未命中，则根据id查询数据库  getById()是mybatis-plus中的IShopService接口提供的查询方法，是根据数据库查询的。
            shop = getById(id);

            //说明：热点key要满足2个条件，一个是高并发 一个是缓存重建的时间比较久，重建时间越久
            //     发生线程安全问题概率越高，现在因为查询数据库是在本地所以重建的时间一瞬间就结束了。
            //为了让它更容易发生高并发的冲突，可以设置休眠时间，模拟重建的延时。
            Thread.sleep(200);

            //5.不存在，返回错误
            if (shop == null) { //快捷键：shop.null
                //将空值写入到redis   2分钟
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在，写入redis 因为这里选择的是String方式存数数据，所以需要把shop对象在转化为字符串
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放锁
            unlock(lockKey);
        }

        //8.返回
        return shop;
    }

    //抽取的缓存穿透解决方案
    public Shop queryWithPassThrough(Long id){
        //CACHE_SHOP_KEY：常量类方式
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存   对象类型的数据既可以使用String也可以使用hash，之前演示的hash，这里使用String方式存储
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在   isNotBlank: 判断不为空    胡图工具包提供的
        if(StrUtil.isNotBlank((shopJson))){
            //3.存在直接返回  需要转化为java对象在返回（字符串方便展示，对象可以用到里面的数据）
            //   JSONUtil.toBean() ：java类库提供的一个方法，将json字符串转化为java对象
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return  shop;
        }

        //判断命中的是否是空值
        //   问题：把空值写入到redis中了，当我们从redis中命中的时候，那么你命中的就不一定是
        //        商铺信息了 还有可能是空值，所以你命中以后还要对结果做一个判断，判断命中的是否
        //        为空，命中的不是空代表是商铺 就可以返回商铺信息了，命中的是空则直接返回空就可以了。
        //
        if(shopJson !=null){
            //返回一个错误信息
            return null;
        }

        //4.不存在，根据id查询数据库  getById()是mybatis-plus中的IShopService接口提供的查询方法，是根据数据库查询的。
        Shop shop = getById(id);
        //5.不存在，返回错误
        if (shop == null) { //快捷键：shop.null
            //将空值写入到redis   2分钟
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis 因为这里选择的是String方式存数数据，所以需要把shop对象在转化为字符串
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    //获取锁：锁其实就是redis中存储的一个key,所以这个key有使用者传递给我们，我们就不写死了
    private boolean tryLock(String key) {
        //setnx---对应setIfAbsent方法，参数：k   v   超时时间   超时单位
        //这个结果在命令行中返回 0 和 1，在这spring给我们转化为了true 和 false
        //   所以我们要给他转化为基本类型再返回。
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //注意：不要直接return flag，自动拆箱的过程有可能会出现空指针
        //解决：使用hutool工具包的的工具类，帮你判断返回
        return BooleanUtil.isTrue(flag);
    }

    //热点数据预热
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop=getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        //逻辑过期时间为当前时间+传入的毫秒数
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis(此处不需要设置过期时间了，相当于永久保存)
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    //释放锁
    private void unlock(String key) {
        //不需要返回值
        stringRedisTemplate.delete(key);
    }*/


    @Override
    @Transactional //保证更新和删除一致性
    public Result update(Shop shop) {
        //先更新数据库，再删除缓存
        Long id= shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }

}
