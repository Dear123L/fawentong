package com.example.mapper;

import com.example.entity.Category;
import com.example.vo.CategoryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author lhh
 */
@Mapper
public interface CategoryMapper {
    @Select("SELECT id,name,icon FROM category WHERE level = #{level} order by sort_order asc")
    List<CategoryVO> selectByLevel(int i);

    @Select("SELECT id,name,icon FROM category WHERE parent_id = #{parentId} order by sort_order asc")
    List<CategoryVO> selectByParentId(Long parentId);

    @Select("SELECT * FROM category")
    List<Category> selectAllCategories();

    @Select("SELECT count(*) FROM category WHERE id = #{categoryId}")
    boolean existById(Long categoryId);
}
