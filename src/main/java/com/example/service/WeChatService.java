package com.example.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.config.WeChatConfig;
import com.example.entity.User;
import com.example.mapper.UserMapper;
import com.example.util.JsonResponse;
import com.example.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * @author lhh
 */
@Service
public interface WeChatService {
    public JsonResponse login(String code, String nickName, String avatarUrl);
}
