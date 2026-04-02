package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            //手机号不合法
            return Result.fail("手机号不合法");
        }
        //手机号合法
        String code = RandomUtil.randomNumbers(6);
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功:{}",code);
        return Result.ok();
    }


    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号不合法
            return Result.fail("手机号不合法");
        }
        String code = loginForm.getCode();
//        Object cacheCode = session.getAttribute(code);
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //检验验证码与手机号是否相符
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //查询用户
        User user = query().eq("phone", phone).one();
        if(user == null){
            //新用户
            user = createUserWithPhone(phone);
        }
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }



    @Override
    public Result sign() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now=LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key= RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //4.获取今天是本月的第几天(这个方法返回的是1-31  而我们redis中存取打卡的是从0-31)
        int dayOfMonth = now.getDayOfMonth();
        //5.x写入redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1, true);
        return Result.ok();
    }



    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        //BITFIELD KEY TYPE OFFSET  这里u14表示获取前14位 0表示位移，从第一天开始
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create() //子命令集合
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        //获取数据
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;//无符号右移
        }
        return Result.ok(count);
    }


}
