package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.TestPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TestPostMapper extends BaseMapper<TestPost> {

    @Update("UPDATE test_post SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id);

    @Update("UPDATE test_post SET like_count = like_count + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    @Update("UPDATE test_post SET favorite_count = favorite_count + 1 WHERE id = #{id}")
    int incrementFavoriteCount(@Param("id") Long id);

    @Update("UPDATE test_post SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decrementLikeCount(@Param("id") Long id);

    @Update("UPDATE test_post SET favorite_count = favorite_count - 1 WHERE id = #{id} AND favorite_count > 0")
    int decrementFavoriteCount(@Param("id") Long id);
}