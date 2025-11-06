package com.example.mapper;

import com.example.entity.Favorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author lhh
 */
@Mapper
public interface FavoriteMapper {

    @Select("SELECT * FROM favorite WHERE user_id = #{userId} AND post_id = #{postId}")
    Favorite findByUserAndPost(Long userId, Long postId);

    @Insert("INSERT INTO favorite (user_id, post_id) VALUES (#{userId}, #{postId})")
    void insert(Long userId, Long postId);

    @Delete("DELETE FROM favorite WHERE id = #{id}")
    void delete(Long id);
}
