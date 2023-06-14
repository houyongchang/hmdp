package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate redisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头中的token
        String token = request.getHeader("authorization");
        //判断是否存在
        if (StrUtil.isBlank(token)){
            return  true;
        }
        //基于token获取redis中的用户
        Map<Object, Object> UserMap = redisTemplate.opsForHash().entries("login:token:" + token);
        if (UserMap.isEmpty()){
            return  true;
        }

        UserDTO userDTO = BeanUtil.fillBeanWithMap(UserMap, new UserDTO(), false);
        //保存到ThreadLocal中去
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        redisTemplate.expire("login:token:" + token,30, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
