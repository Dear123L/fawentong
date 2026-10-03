package com.example.service;

import com.example.entity.TestPost;

public interface RedisCacheService {

    /**
     * 获取帖子详情（使用Redis缓存）
     */
    TestPost getPostWithCache(Long postId);

    /**
     * 获取帖子详情（不使用缓存，直接查DB）
     */
    TestPost getPostWithoutCache(Long postId);

    /**
     * 点赞（使用Redis）
     */
    boolean likePostWithCache(Long postId, Long userId);

    /**
     * 点赞（不使用缓存，直接写DB）
     */
    boolean likePostWithoutCache(Long postId);

    /**
     * 取消点赞（使用Redis）
     */
    boolean unlikePostWithCache(Long postId, Long userId);

    /**
     * 收藏（使用Redis）
     */
    boolean favoritePostWithCache(Long postId, Long userId);

    /**
     * 收藏（不使用缓存，直接写DB）
     */
    boolean favoritePostWithoutCache(Long postId);

    /**
     * 取消收藏（使用Redis）
     */
    boolean unfavoritePostWithCache(Long postId, Long userId);

    /**
     * 增加浏览量（使用Redis）
     */
    void incrementViewWithCache(Long postId);

    /**
     * 增加浏览量（不使用缓存）
     */
    void incrementViewWithoutCache(Long postId);

    /**
     * 获取帖子统计（从缓存）
     */
    PostStats getPostStatsWithCache(Long postId);

    /**
     * 获取帖子统计（从DB）
     */
    PostStats getPostStatsWithoutCache(Long postId);

    /**
     * 手动刷新缓存
     */
    void refreshCache(Long postId);

    /**
     * 清空缓存
     */
    void clearCache(Long postId);

    /**
     * 创建帖子
     */
    TestPost createPost(TestPost post);

    /**
     * 更新帖子
     */
    boolean updatePost(TestPost post);

    /**
     * 删除帖子
     */
    boolean deletePost(Long postId);

    /**
     * 帖子统计信息
     */
    record PostStats(Long postId, Integer viewCount, Integer likeCount, Integer favoriteCount) {}
}