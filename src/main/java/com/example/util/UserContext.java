package com.example.util;

import com.example.entity.User;
import com.example.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UserContext {
    
    private static UserMapper userMapper;
    
    @Autowired
    public void setUserMapper(UserMapper userMapper) {
        UserContext.userMapper = userMapper;
    }

    public static Long getCurrentUserId() {
        String openId = SecurityUtils.getCurrentUserOpenId();
        System.out.println("当前用户openId: " + openId); // 添加日志

        User user = userMapper.findByOpenId(openId);
        System.out.println("查询到的用户: " + user); // 添加日志

        if (user == null) {
            throw new RuntimeException("用户不存在, openId: " + openId);
        }
        return user.getId();
    }
    
    /**
     * 获取当前用户对象
     */
    public static User getCurrentUser() {
        String openId = SecurityUtils.getCurrentUserOpenId();
        User user = userMapper.findByOpenId(openId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }
}