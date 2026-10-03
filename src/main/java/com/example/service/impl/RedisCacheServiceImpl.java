package com.example.service.impl;

import com.example.entity.TestPost;
import com.example.mapper.TestPostMapper;
import com.example.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheServiceImpl implements RedisCacheService {

    private final TestPostMapper testPostMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // Redis Key 前缀
    private static final String POST_KEY_PREFIX = "post:";
    private static final String POST_STATS_PREFIX = "post:stats:";
    private static final String USER_LIKE_PREFIX = "user:like:";
    private static final String USER_FAVORITE_PREFIX = "user:favorite:";

    // 缓存过期时间（5分钟）
    private static final long CACHE_EXPIRE_MINUTES = 5;

    public TestPost getPostWithCache(Long postId) {
        String key = POST_KEY_PREFIX + postId;
        Object cachedPost = redisTemplate.opsForValue().get(key);
        if (cachedPost != null) {
            log.info("✅ Redis命中: postId={}", postId);  // 加这行
            return (TestPost) cachedPost;
        }
        log.info("❌ Redis未命中，查询DB: postId={}", postId);  // 加这行
        TestPost post = testPostMapper.selectById(postId);
        if (post != null) {
            redisTemplate.opsForValue().set(key, post, CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        }
        return post;
    }

    @Override
    public TestPost getPostWithoutCache(Long postId) {
        // 直接从DB查询
        return testPostMapper.selectById(postId);
    }

    @Override
    public boolean likePostWithCache(Long postId, Long userId) {
        String likeKey = USER_LIKE_PREFIX + userId + ":" + postId;
        String statsKey = POST_STATS_PREFIX + postId;

        Boolean exists = redisTemplate.hasKey(likeKey);
        if (Boolean.TRUE.equals(exists)) {
            return false; // 已点赞
        }

        // 标记用户已点赞
        redisTemplate.opsForValue().set(likeKey, "1", 24, TimeUnit.HOURS);
        
        // 增加点赞数（原子操作）
        redisTemplate.opsForHash().increment(statsKey, "likeCount", 1);
        
        // 异步更新DB（可选，演示用同步）
        testPostMapper.incrementLikeCount(postId);
        
        // 刷新帖子详情缓存
        refreshCache(postId);
        
        return true;
    }

    @Override
    @Transactional
    public boolean likePostWithoutCache(Long postId) {
        // 直接更新DB
        return testPostMapper.incrementLikeCount(postId) > 0;
    }

    @Override
    public boolean unlikePostWithCache(Long postId, Long userId) {
        String likeKey = USER_LIKE_PREFIX + userId + ":" + postId;
        String statsKey = POST_STATS_PREFIX + postId;

        Boolean exists = redisTemplate.hasKey(likeKey);
        if (Boolean.FALSE.equals(exists)) {
            return false; // 未点赞，无法取消
        }

        // 删除点赞标记
        redisTemplate.delete(likeKey);
        
        // 减少点赞数
        redisTemplate.opsForHash().increment(statsKey, "likeCount", -1);
        
        // 更新DB
        testPostMapper.decrementLikeCount(postId);
        
        // 刷新缓存
        refreshCache(postId);
        
        return true;
    }

    @Override
    public boolean favoritePostWithCache(Long postId, Long userId) {
        String favoriteKey = USER_FAVORITE_PREFIX + userId + ":" + postId;
        String statsKey = POST_STATS_PREFIX + postId;

        Boolean exists = redisTemplate.hasKey(favoriteKey);
        if (Boolean.TRUE.equals(exists)) {
            return false; // 已收藏
        }

        // 标记用户已收藏
        redisTemplate.opsForValue().set(favoriteKey, "1", 24, TimeUnit.HOURS);
        
        // 增加收藏数
        redisTemplate.opsForHash().increment(statsKey, "favoriteCount", 1);
        
        // 更新DB
        testPostMapper.incrementFavoriteCount(postId);
        
        // 刷新缓存
        refreshCache(postId);
        
        return true;
    }

    @Override
    @Transactional
    public boolean favoritePostWithoutCache(Long postId) {
        return testPostMapper.incrementFavoriteCount(postId) > 0;
    }

    @Override
    public boolean unfavoritePostWithCache(Long postId, Long userId) {
        String favoriteKey = USER_FAVORITE_PREFIX + userId + ":" + postId;
        String statsKey = POST_STATS_PREFIX + postId;

        Boolean exists = redisTemplate.hasKey(favoriteKey);
        if (Boolean.FALSE.equals(exists)) {
            return false; // 未收藏，无法取消
        }

        // 删除收藏标记
        redisTemplate.delete(favoriteKey);
        
        // 减少收藏数
        redisTemplate.opsForHash().increment(statsKey, "favoriteCount", -1);
        
        // 更新DB
        testPostMapper.decrementFavoriteCount(postId);
        
        // 刷新缓存
        refreshCache(postId);
        
        return true;
    }

    @Override
    public void incrementViewWithCache(Long postId) {
        String statsKey = POST_STATS_PREFIX + postId;
        
        // 使用Redis原子操作增加浏览量
        redisTemplate.opsForHash().increment(statsKey, "viewCount", 1);
        
        // 异步更新DB（这里为了演示用同步）
        testPostMapper.incrementViewCount(postId);
    }

    @Override
    public void incrementViewWithoutCache(Long postId) {
        // 直接更新DB
        testPostMapper.incrementViewCount(postId);
    }

    @Override
    public PostStats getPostStatsWithCache(Long postId) {
        String statsKey = POST_STATS_PREFIX + postId;
        
        // 从缓存获取统计
        Integer viewCount = (Integer) redisTemplate.opsForHash().get(statsKey, "viewCount");
        Integer likeCount = (Integer) redisTemplate.opsForHash().get(statsKey, "likeCount");
        Integer favoriteCount = (Integer) redisTemplate.opsForHash().get(statsKey, "favoriteCount");
        
        // 如果缓存中没有，从DB获取并初始化
        if (viewCount == null) {
            TestPost post = testPostMapper.selectById(postId);
            if (post != null) {
                viewCount = post.getViewCount();
                likeCount = post.getLikeCount();
                favoriteCount = post.getFavoriteCount();
                
                redisTemplate.opsForHash().put(statsKey, "viewCount", viewCount);
                redisTemplate.opsForHash().put(statsKey, "likeCount", likeCount);
                redisTemplate.opsForHash().put(statsKey, "favoriteCount", favoriteCount);
            }
        }
        
        return new PostStats(postId, viewCount, likeCount, favoriteCount);
    }

    @Override
    public PostStats getPostStatsWithoutCache(Long postId) {
        TestPost post = testPostMapper.selectById(postId);
        if (post == null) {
            return null;
        }
        return new PostStats(post.getId(), post.getViewCount(), post.getLikeCount(), post.getFavoriteCount());
    }

    @Override
    public void refreshCache(Long postId) {
        String key = POST_KEY_PREFIX + postId;
        TestPost post = testPostMapper.selectById(postId);
        if (post != null) {
            redisTemplate.opsForValue().set(key, post, CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        }
    }

    @Override
    public void clearCache(Long postId) {
        redisTemplate.delete(POST_KEY_PREFIX + postId);
        redisTemplate.delete(POST_STATS_PREFIX + postId);
    }

    @Override
    @Transactional
    public TestPost createPost(TestPost post) {
        testPostMapper.insert(post);
        String key = POST_KEY_PREFIX + post.getId();
        redisTemplate.opsForValue().set(key, post, CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return post;
    }

    @Override
    @Transactional
    public boolean updatePost(TestPost post) {
        int result = testPostMapper.updateById(post);
        if (result > 0) {
            // 更新后刷新缓存
            refreshCache(post.getId());
        }
        return result > 0;
    }

    @Override
    @Transactional
    public boolean deletePost(Long postId) {
        int result = testPostMapper.deleteById(postId);
        if (result > 0) {
            // 删除缓存
            clearCache(postId);
        }
        return result > 0;
    }
}