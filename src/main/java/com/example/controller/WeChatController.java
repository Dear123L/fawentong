package com.example.controller;

import com.example.entity.LoginRequest;
import com.example.entity.LoginResponse;
import com.example.service.WeChatService;
import com.example.util.JsonResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wechat")
public class WeChatController {
    @Autowired
    private WeChatService weChatService;

    @PostMapping("/login")
    public Object login(@RequestBody LoginRequest req) {
        try {
            String token = weChatService.login(req.getCode(), req.getNickName(), req.getAvatarUrl());
            return new LoginResponse(token);
        }catch (Exception e){
            return JsonResponse.fail(400,"登录失败");
        }

    }
}