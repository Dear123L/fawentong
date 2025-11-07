package com.example.controller;

import com.alibaba.fastjson2.JSONObject;
import com.example.dto.UserUpdateDTO;
import com.example.service.UserService;
import com.example.util.JsonResponse;
import com.example.util.SecurityUtils;
import com.example.util.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * @author lhh
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
public class UserController {
    @Autowired
    private UserService userService;

    /**
     * 获取用户信息
     */
    @GetMapping("/getUserInfo")
    public JsonResponse getUserInfo() {
        return userService.getUserInfo();
    }

    /**
     * 更新个人信息
     */
    @PostMapping("/updateUserInfo")
    public JsonResponse updateUserInfo(@RequestBody UserUpdateDTO userUpdateDTO) {
        try {
            return userService.updateUserInfo(userUpdateDTO);
        }catch (Exception e){
            return JsonResponse.fail(400,"更新个人信息失败");
        }
    }

    /**
     * 收藏/取消收藏
     */
    @PostMapping("/favorite/toggle")
    public JsonResponse toggleFavorite(@RequestParam("postId") Long postId) {
        try {
            if (!SecurityUtils.isLogin()) return JsonResponse.authError("用户未登录");
            Long userId =UserContext.getCurrentUserId();
            boolean currentStatus = userService.toggleFavorite(userId,postId);
            String message = currentStatus ? "添加收藏成功" : "取消收藏成功";
            JSONObject data = new JSONObject();
            data.put("currentStatus", currentStatus);
            return JsonResponse.success(message, data);
        }catch (Exception e){
            log.error("收藏/取消收藏操作失败, postId: {}, userId: {}", postId, UserContext.getCurrentUserId(), e);
            if (e.getMessage().contains("帖子不存在")) {
                return JsonResponse.fail(404, "帖子不存在");
            }
            return JsonResponse.fail(400, "操作失败，请重试");        }
    }

    /**
     * 获取用户收藏的帖子列表
     */
    @GetMapping("/favorite/list")
    public JsonResponse getUserFavorites() {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (!SecurityUtils.isLogin()||userId==null) return JsonResponse.authError("用户未登录");
            return JsonResponse.success("获取用户收藏的帖子列表成功", userService.getUserFavorites(userId));
        } catch (Exception e) {
            e.printStackTrace();
            return JsonResponse.fail(400, "获取用户收藏的帖子列表失败");
        }
    }
}
