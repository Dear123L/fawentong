package com.example.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.config.WeChatConfig;
import com.example.entity.User;
import com.example.mapper.UserMapper;
import com.example.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * @author lhh
 */
@Service
public class WeChatService {

    @Autowired
    private WeChatConfig weChatConfig;

    @Autowired
    private UserMapper userMapper;

    public String login(String code, String nickName, String avatarUrl) {
        // 1. 请求微信服务器获取 openid/session_key
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                weChatConfig.getAppId(), weChatConfig.getAppSecret(), code);

        RestTemplate restTemplate = new RestTemplate();
        String response = restTemplate.getForObject(url, String.class);

        JSONObject json = JSONObject.parseObject(response); // 更推荐的方式解析 JSON 字符串
        String openId = json.getString("openid");
        if (openId == null || openId.isEmpty()) {
            throw new RuntimeException("微信登录失败:" + response);
        }

        // 2. 查找或创建用户
        User user = userMapper.findByOpenId(openId);
        if (user == null) {
            user = new User();
            user.setOpenId(openId);
            user.setNickName(nickName);
            user.setAvatarUrl(avatarUrl);
            userMapper.insert(user);
        }

        // 3. 生成自己的 token
        String token = JwtUtil.generateToken(openId, user.getId());
        // 实际项目可以将 token 与用户信息存入 Redis，实现登录态管理

        return token;
    }
}
