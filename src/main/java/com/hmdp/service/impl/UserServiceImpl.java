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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
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
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserMapper userMapper;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        RegexUtils.isCodeInvalid(phone);
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
//        // 3. 保存验证码到session
//        session.setAttribute("code", code);
        // 3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // TODO 发送验证码
        log.debug("发送验证码成功， 验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        RegexUtils.isCodeInvalid(phone);
        // 2. 校验验证码
        if(!loginForm.getCode().equals(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone))){
            return Result.fail("验证码有误");
        }
        // 3. 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if(user == null) {
            // 创建新用户
            user = createUserWithPhone(phone);
            // 保存到数据库
            save(user);
        }
//        // 保存用户到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 保存用户到redis
        String token = UUID.randomUUID().toString(true); // 登陆令牌
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString())); // 把用户信息转为HashMap
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES); // 设置有效期

        return Result.ok(token);
    }


    @Override
    public UserDTO getUser() {
        return UserHolder.getUser();
    }

    public User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
