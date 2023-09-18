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
import com.hmdp.utils.RegexUtils;
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

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("无效的手机号");
        }
        String code = RandomUtil.randomNumbers(6);

//        session.setAttribute("code", code);
        //生成的验证码使用String形式存储到redis中,设置2分钟过期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //模拟发送验证码
        log.debug("发送验证码成功,{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("无效的手机号");
        }
//        Object cachecode = session.getAttribute("code");
        //从Redis中获取验证码并校验
        Object cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if (cachecode == null || !cachecode.toString().equals(code))
            return Result.fail("验证码错误");

        //查询该手机号是否第一次使用
        User user = query().eq("phone", phone).one();

        if (user == null) user = createUserWithPhone(phone);

        //保存用户信息到Redis中
        //1.生成随机token作为登录令牌
        String token = UUID.randomUUID(false).toString();

        //2.将用户信息转为hashMap类型存储到Redis中,token作为key
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
        //设置token有效期30min
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //       session.setAttribute("user", user);
        //3.最后将token返回前端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands
                                .create()
                                .get(BitFieldSubCommands
                                        .BitFieldType
                                        .unsigned(dayOfMonth))
                                .valueAt(0)
                );
        if (result == null || result.isEmpty()) {
            return Result.ok();
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 定义计数器
        int count = 0;
        //遍历result
        while (true) {
//            将每一位与1进行与运算 得到最后一个数字的bit位
            if ((num & 1) == 0) {
                //为0代表 未签到
                break;
            } else {
                //不为0 说明已经签到，计数器+1
                count++;
            }
            //将数字右移一位数，即抛弃最后一位，进行前一个bit位的运算
            num >>>= 1;
        }

        return Result.ok(count);
    }


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(4));
        //保存用户
        save(user);
        return user;
    }
}
