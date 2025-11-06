package com.example.service.impl;

import com.example.dto.UserInfoDTO;
import com.example.dto.UserUpdateDTO;
import com.example.entity.Favorite;
import com.example.mapper.FavoriteMapper;
import com.example.mapper.UserMapper;
import com.example.service.UserService;
import com.example.util.JsonResponse;
import com.example.util.SecurityUtils;
import com.example.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author lhh
 */
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FavoriteMapper favoriteMapper;

    @Override
    public JsonResponse getUserInfo() {
        try {
            if (!SecurityUtils.isLogin()) return JsonResponse.authError("用户未登录");
            Long userId =UserContext.getCurrentUserId();
            if (userId == null) {
                return JsonResponse.fail(400, "无法获取用户信息");
            }
            UserInfoDTO userInfoDTO = userMapper.findById(userId);
            if (userInfoDTO==null) return JsonResponse.fail(400,"用户不存在");
            return JsonResponse.success("获取用户信息成功",userInfoDTO);
        }catch (Exception e){
            System.out.println(e.getMessage());
            return JsonResponse.fail(400,e.getMessage());
        }
    }

    @Override
    public JsonResponse updateUserInfo(UserUpdateDTO userUpdateDTO) {
        try {
            if (userUpdateDTO==null) return JsonResponse.fail(400,"参数不能为空");
            if (userUpdateDTO.getNickName() == null && userUpdateDTO.getAvatarUrl() == null) {
                return JsonResponse.paramError("昵称和头像不能同时为空");
            }
            if (!SecurityUtils.isLogin()) return JsonResponse.authError("用户未登录");
            Long userId =UserContext.getCurrentUserId();
            userMapper.updateUserInfo(userUpdateDTO.getNickName(),userUpdateDTO.getAvatarUrl(),userId);
            return JsonResponse.success("更新用户信息成功");
        }catch (Exception e){
            return JsonResponse.fail(400,"更新用户信息失败");
        }
    }

    @Override
    public boolean toggleFavorite(Long userId,Long postId) {
        Favorite favorite = favoriteMapper.findByUserAndPost(userId, postId);
        if (favorite == null) {
            favoriteMapper.insert(userId, postId);
            return true;
        } else {
            favoriteMapper.delete(favorite.getId());
            return false;
        }
    }
}
