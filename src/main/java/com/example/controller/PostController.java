package com.example.controller;

import com.example.service.PostService;
import com.example.util.JsonResponse;
import com.example.util.SecurityUtils;
import com.example.util.UserContext;
import com.example.vo.PostDetailVO;
import com.example.vo.PostListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author lhh
 */
@RestController
@RequestMapping("/api/post")
public class PostController {
    @Autowired
    private PostService postService;

    /**
     * 根据分类ID获取帖子
     * @return
     */
    @GetMapping("/getCategoryPosts")
    public JsonResponse getCategoryPosts(@RequestParam("categoryId") Long categoryId) {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null|| !SecurityUtils.isLogin()) return JsonResponse.authError("用户未登录");
            List<PostListVO> posts = postService.getCategoryPosts(userId,categoryId);
            return JsonResponse.success("获取分类下的帖子成功", posts);
        }catch (Exception e){
            return JsonResponse.fail(400,"获取分类下的帖子失败");
        }
    }

    /**
     * 获取帖子详情
     * @return
     */
    @GetMapping("/getPostDetail")
    public JsonResponse getPostDetail(@RequestParam("postId") Long postId) {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null|| !SecurityUtils.isLogin()) return JsonResponse.authError("用户未登录");
            PostDetailVO postDetail = postService.getPostDetail(userId, postId);
            return JsonResponse.success("获取帖子详情成功",postDetail);
        }catch (Exception e){
            return JsonResponse.fail(400,"获取帖子详情失败");
        }
    }
}
