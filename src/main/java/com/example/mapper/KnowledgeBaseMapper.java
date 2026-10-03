package com.example.mapper;

import com.example.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库数据访问。
 */
@Mapper
public interface KnowledgeBaseMapper {

    /** 新建知识库，回填自增主键。 */
    @Insert("INSERT INTO knowledge_base(name, type, user_id, description, created_time) "
            + "VALUES(#{name}, #{type}, #{userId}, #{description}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBase kb);

    /** 按主键查询，不存在返回 null。 */
    @Select("SELECT id, name, type, user_id, description, created_time "
            + "FROM knowledge_base WHERE id = #{id}")
    KnowledgeBase selectById(Long id);

    /** 列出某用户可见的知识库（自己的 + 公共的）。 */
    @Select("SELECT id, name, type, user_id, description, created_time "
            + "FROM knowledge_base WHERE user_id = #{userId} OR type = 'public' "
            + "ORDER BY created_time DESC")
    List<KnowledgeBase> selectByUserId(Long userId);
}
