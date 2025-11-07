package com.example.mapper;

import com.example.vo.PostDetailVO;
import com.example.vo.PostListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author lhh
 */
@Mapper
public interface PostMapper {
    @Select("SELECT count(*) FROM post WHERE id = #{postId}")
    boolean existPostById(Long postId);

    @Select("SELECT id, title, excerpt, cover_image FROM post WHERE category_id = #{categoryId}")
    List<PostListVO> getCategoryPosts(Long categoryId);

    @Select("SELECT id, title, excerpt, cover_image, content, categoryId FROM post WHERE id = #{id}")
    PostDetailVO findById(Long id);
}
