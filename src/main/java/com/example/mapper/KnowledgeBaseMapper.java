package com.example.mapper;

import com.example.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库数据访问（表 knowledge_base）。
 * 列名与真实 schema 对齐：created_at / updated_at / is_deleted。
 */
@Mapper
public interface KnowledgeBaseMapper {

    /** 新建知识库，回填自增主键。 */
    @Insert("INSERT INTO knowledge_base(name, type, user_id, description, created_at, is_deleted) "
            + "VALUES(#{name}, #{type}, #{userId}, #{description}, NOW(), 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBase kb);

    /** 按主键查询，不存在返回 null。 */
    @Select("SELECT id, name, type, user_id, description, created_at, updated_at, is_deleted "
            + "FROM knowledge_base WHERE id = #{id} AND is_deleted = 0")
    KnowledgeBase selectById(Long id);

    /** 列出某用户可见的知识库（自己的 + 公共的），排除已删。 */
    @Select("SELECT id, name, type, user_id, description, created_at, updated_at, is_deleted "
            + "FROM knowledge_base WHERE is_deleted = 0 "
            + "AND (user_id = #{userId} OR type = 'public') ORDER BY created_at DESC")
    List<KnowledgeBase> selectByUserId(Long userId);

    /** 逻辑删除。 */
    @Update("UPDATE knowledge_base SET is_deleted = 1, updated_at = NOW() WHERE id = #{id}")
    int softDeleteById(Long id);
}
