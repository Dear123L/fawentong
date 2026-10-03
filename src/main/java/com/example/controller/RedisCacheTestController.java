package com.example.controller;

import com.example.entity.TestPost;
import com.example.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
@RestController
@RequestMapping("/api/redis-test")
@RequiredArgsConstructor
public class RedisCacheTestController {

    private final RedisCacheService redisCacheService;
    private final Random random = new Random();

    /**
     * 创建测试帖子
     */
    @PostMapping("/post")
    public ResponseEntity<TestPost> createPost(@RequestBody TestPost post) {
        TestPost created = redisCacheService.createPost(post);
        return ResponseEntity.ok(created);
    }

    /**
     * 获取帖子（带Redis缓存）
     */
    @GetMapping("/post/with-cache/{postId}")
    public ResponseEntity<TestPost> getPostWithCache(@PathVariable Long postId) {
        TestPost post = redisCacheService.getPostWithCache(postId);
        if (post == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(post);
    }

    /**
     * 获取帖子（不带缓存，直接查DB）
     */
    @GetMapping("/post/without-cache/{postId}")
    public ResponseEntity<TestPost> getPostWithoutCache(@PathVariable Long postId) {
        TestPost post = redisCacheService.getPostWithoutCache(postId);
        if (post == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(post);
    }

    /**
     * 点赞（带Redis缓存）
     */
    @PostMapping("/like/with-cache/{postId}")
    public ResponseEntity<Map<String, Object>> likeWithCache(@PathVariable Long postId, 
                                                             @RequestParam(defaultValue = "1") Long userId) {
        boolean success = redisCacheService.likePostWithCache(postId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "点赞成功" : "已点赞");
        return ResponseEntity.ok(result);
    }

    /**
     * 点赞（不带缓存，直接写DB）
     */
    @PostMapping("/like/without-cache/{postId}")
    public ResponseEntity<Map<String, Object>> likeWithoutCache(@PathVariable Long postId) {
        boolean success = redisCacheService.likePostWithoutCache(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "点赞成功" : "操作失败");
        return ResponseEntity.ok(result);
    }

    /**
     * 取消点赞
     */
    @DeleteMapping("/like/{postId}")
    public ResponseEntity<Map<String, Object>> unlike(@PathVariable Long postId, 
                                                      @RequestParam(defaultValue = "1") Long userId) {
        boolean success = redisCacheService.unlikePostWithCache(postId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "取消点赞成功" : "未点赞");
        return ResponseEntity.ok(result);
    }

    /**
     * 收藏（带Redis缓存）
     */
    @PostMapping("/favorite/with-cache/{postId}")
    public ResponseEntity<Map<String, Object>> favoriteWithCache(@PathVariable Long postId,
                                                                @RequestParam(defaultValue = "1") Long userId) {
        boolean success = redisCacheService.favoritePostWithCache(postId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "收藏成功" : "已收藏");
        return ResponseEntity.ok(result);
    }

    /**
     * 收藏（不带缓存，直接写DB）
     */
    @PostMapping("/favorite/without-cache/{postId}")
    public ResponseEntity<Map<String, Object>> favoriteWithoutCache(@PathVariable Long postId) {
        boolean success = redisCacheService.favoritePostWithoutCache(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "收藏成功" : "操作失败");
        return ResponseEntity.ok(result);
    }

    /**
     * 取消收藏
     */
    @DeleteMapping("/favorite/{postId}")
    public ResponseEntity<Map<String, Object>> unfavorite(@PathVariable Long postId,
                                                         @RequestParam(defaultValue = "1") Long userId) {
        boolean success = redisCacheService.unfavoritePostWithCache(postId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "取消收藏成功" : "未收藏");
        return ResponseEntity.ok(result);
    }

    /**
     * 增加浏览量（带Redis缓存）
     */
    @PostMapping("/view/with-cache/{postId}")
    public ResponseEntity<Map<String, Object>> viewWithCache(@PathVariable Long postId) {
        redisCacheService.incrementViewWithCache(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "浏览量+1");
        return ResponseEntity.ok(result);
    }

    /**
     * 增加浏览量（不带缓存）
     */
    @PostMapping("/view/without-cache/{postId}")
    public ResponseEntity<Map<String, Object>> viewWithoutCache(@PathVariable Long postId) {
        redisCacheService.incrementViewWithoutCache(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "浏览量+1");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取帖子统计（带缓存）
     */
    @GetMapping("/stats/with-cache/{postId}")
    public ResponseEntity<RedisCacheService.PostStats> getStatsWithCache(@PathVariable Long postId) {
        RedisCacheService.PostStats stats = redisCacheService.getPostStatsWithCache(postId);
        if (stats == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取帖子统计（不带缓存）
     */
    @GetMapping("/stats/without-cache/{postId}")
    public ResponseEntity<RedisCacheService.PostStats> getStatsWithoutCache(@PathVariable Long postId) {
        RedisCacheService.PostStats stats = redisCacheService.getPostStatsWithoutCache(postId);
        if (stats == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stats);
    }

    /**
     * 刷新缓存
     */
    @PostMapping("/cache/refresh/{postId}")
    public ResponseEntity<Map<String, Object>> refreshCache(@PathVariable Long postId) {
        redisCacheService.refreshCache(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "缓存已刷新");
        return ResponseEntity.ok(result);
    }

    /**
     * 清空缓存
     */
    @DeleteMapping("/cache/{postId}")
    public ResponseEntity<Map<String, Object>> clearCache(@PathVariable Long postId) {
        redisCacheService.clearCache(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "缓存已清空");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除帖子
     */
    @DeleteMapping("/post/{postId}")
    public ResponseEntity<Map<String, Object>> deletePost(@PathVariable Long postId) {
        boolean success = redisCacheService.deletePost(postId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "删除成功" : "删除失败");
        return ResponseEntity.ok(result);
    }

    /**
     * 性能测试接口 - 模拟高并发场景
     * 用于JMeter或wrk压测
     */
    @GetMapping("/perf/test")
    public ResponseEntity<Map<String, Object>> performanceTest(
            @RequestParam(defaultValue = "1") Long postId,
            @RequestParam(defaultValue = "with-cache") String mode,
            @RequestParam(defaultValue = "1") Long userId) {
        
        long startTime = System.currentTimeMillis();
        
        // 根据模式执行不同操作
        switch (mode) {
            case "with-cache":
                redisCacheService.getPostWithCache(postId);
                break;
            case "without-cache":
                redisCacheService.getPostWithoutCache(postId);
                break;
            case "like-cache":
                redisCacheService.likePostWithCache(postId, userId);
                break;
            case "like-db":
                redisCacheService.likePostWithoutCache(postId);
                break;
            case "view-cache":
                redisCacheService.incrementViewWithCache(postId);
                break;
            case "view-db":
                redisCacheService.incrementViewWithoutCache(postId);
                break;
            default:
                redisCacheService.getPostWithCache(postId);
        }
        
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("mode", mode);
        result.put("postId", postId);
        result.put("latencyMs", endTime - startTime);
        result.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 批量创建测试数据
     */
    @PostMapping("/post/batch")
    public ResponseEntity<Map<String, Object>> createBatchPosts(@RequestParam(defaultValue = "10") int count) {
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < count; i++) {
            TestPost post = new TestPost();
            post.setTitle("测试帖子 " + (System.currentTimeMillis() + i));
            post.setContent("这是一篇用于Redis缓存性能测试的帖子内容。\n\n帖子编号：" + i);
            post.setCoverImage("https://example.com/image.jpg");
            post.setViewCount(0);
            post.setLikeCount(0);
            post.setFavoriteCount(0);
            redisCacheService.createPost(post);
        }
        
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", count);
        result.put("timeMs", endTime - startTime);
        return ResponseEntity.ok(result);
    }
}