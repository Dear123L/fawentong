package com.example.controller;

import com.example.dto.LoginRequest;
import com.example.service.WeChatService;
import com.example.util.JsonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wechat")
@Slf4j
public class WeChatController {
    @Autowired
    private WeChatService weChatService;

    /**
     * 微信登录
     * @param req
     * @return
     */
    @PostMapping("/login")
    public JsonResponse login(@RequestBody LoginRequest req) {
        try {
            if (req==null) return JsonResponse.paramError("参数不能为空");
            if (req.getCode()==null || req.getCode().isEmpty()) return JsonResponse.paramError("code不能为空");
            return weChatService.login(req.getCode(), req.getNickName(), req.getAvatarUrl());
        }catch (Exception e){
            log.error("微信登录失败: ", e.getMessage());
            return JsonResponse.fail(500,"登录失败");
        }
    }
}