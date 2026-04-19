package com.example.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.example.config.WeChatConfig;
import com.example.entity.User;
import com.example.mapper.UserMapper;
import com.example.service.WeChatService;
import com.example.util.JsonResponse;
import com.example.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Date;

/**
 * @author lhh
 */
@Service
@Slf4j
public class WeChatServiceImpl implements WeChatService {

    @Autowired
    private WeChatConfig weChatConfig;

    @Autowired
    private UserMapper userMapper;

    @Override
    public JsonResponse login(String code, String nickName, String avatarUrl) {
        // TODO: 上线前恢复微信真实接口调用
        // 请求微信服务器获取 openid/session_key
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                weChatConfig.getAppId(), weChatConfig.getAppSecret(), code);

        System.out.println("微信接口调用: " + url);
        log.info("微信接口调用URL: {}", url);
        log.debug("参数: appid={}, code={}", weChatConfig.getAppId(), code);
        RestTemplate restTemplate = new RestTemplate();
        String response = restTemplate.getForObject(url, String.class);

        JSONObject json = JSONObject.parseObject(response); // 解析 JSON 字符串
        String openId = json.getString("openid");
        String sessionKey = json.getString("session_key");
        if (openId == null || openId.isEmpty()) {
            throw new RuntimeException("微信登录失败:" + response);
        }

        // 查找或创建用户
        User user = userMapper.findByOpenId(openId);
        boolean isNewUser=false;
        if (user == null) {
            if (nickName == null || nickName.isEmpty()) return JsonResponse.paramError("首次登录昵称不能为空");
            if (avatarUrl == null || avatarUrl.isEmpty()) return JsonResponse.paramError("首次登录头像不能为空");
            user = new User();
            user.setOpenId(openId);
            user.setNickName(nickName);
            user.setAvatarUrl(avatarUrl);
            userMapper.insert(user);
            isNewUser=true;
        }

        // 生成token
        String token = JwtUtil.generateToken(openId, user.getId());
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("token", token);
        jsonObject.put("userId", user.getId());
        jsonObject.put("nickName", user.getNickName());
        jsonObject.put("avatarUrl", user.getAvatarUrl());
        jsonObject.put("isNewUser", isNewUser);

        return JsonResponse.success("登录成功", jsonObject);
    }
}
