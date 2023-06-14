package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.format.DataFormatMatcher;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone) {
        //验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合
            return Result.fail("输入的手机号格式错误");
        }
        //生成6为随机验证码
        String code = RandomUtil.randomNumbers(6);

        log.info(code);
        //将验证码存到redis中去
        redisTemplate.opsForValue().set("login:code:" + phone, code, 2, TimeUnit.MINUTES);
        //将手机号也保存到redis中一份给登录时做校验
        redisTemplate.opsForValue().set("login:phone:" + phone, phone, 2, TimeUnit.MINUTES);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //验证手机号是否格式正确
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone) || !redisTemplate.opsForValue().get("login:phone:" + phone).equals(phone)) {
            return Result.fail("手机号格式错误");
        }
        //验证验证码
        String code = loginForm.getCode();
        if (code == null || !redisTemplate.opsForValue().get("login:code:" + phone).equals(code)) {
            return Result.fail("验证码不正确，请重新输入");
        }
        //判断用户是否存在，不存在自动注册
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        User user = this.getOne(queryWrapper);
        if (user == null) {
            //注册用户
            User newUser = create(phone);
        }

        //把用户信息存到redis中去
        //随机生成token作为登录令牌
        String token = UUID.randomUUID(true).toString();
        //将user转为UserDto
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将userDto转map存到redis中去
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        redisTemplate.opsForHash().putAll("login:token:" + token, userMap);
        redisTemplate.expire("login:token:" + token, 30, TimeUnit.MINUTES);

        //将token返回给前端做登录校验
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String yearAndMonth = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key="sign:"+userId+yearAndMonth;
        int day = now.getDayOfMonth();

        redisTemplate.opsForValue().setBit(key,day,true);

        return Result.ok("签到成功！！！");
    }

    private User create(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        this.save(user);
        return user;
    }
}
