package com.example.service;

import com.example.dto.UserUpdateDTO;
import com.example.util.JsonResponse;
import com.example.vo.PostListVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author lhh
 */
@Service
public interface UserService {

    JsonResponse getUserInfo();

    JsonResponse updateUserInfo(UserUpdateDTO userUpdateDTO);

    boolean toggleFavorite(Long userId,Long postId);

    List<PostListVO> getUserFavorites(Long userId);
}
