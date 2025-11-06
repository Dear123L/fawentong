package com.example.service;

import com.example.dto.UserUpdateDTO;
import com.example.util.JsonResponse;
import org.springframework.stereotype.Service;

/**
 * @author lhh
 */
@Service
public interface UserService {

    JsonResponse getUserInfo();

    JsonResponse updateUserInfo(UserUpdateDTO userUpdateDTO);

    boolean toggleFavorite(Long userId,Long postId);
}
