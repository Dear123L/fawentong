package com.example.mapper;

import com.example.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库文档数据访问（表 knowledge_document）。
 * 列名与真实 schema 对齐：无 file_size / content_length，有 file_url。
 */
@Mapper
public interface KnowledgeDocumentMapper {

    /** 登记一份上传文档（入库时状态为 pending）。 */
    @Insert("INSERT INTO knowledge_document(kb_id, user_id, file_name, file_path, file_url, "
            + "file_type, status, chunk_count, created_at) "
            + "VALUES(#{kbId}, #{userId}, #{fileName}, #{filePath}, #{fileUrl}, "
            + "#{fileType}, #{status}, #{chunkCount}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeDocument doc);

    @Select("SELECT id, kb_id, user_id, file_name, file_path, file_url, file_type, "
            + "status, chunk_count, created_at "
            + "FROM knowledge_document WHERE id = #{id}")
    KnowledgeDocument selectById(Long id);

    /** 列出某知识库下的全部文档。 */
    @Select("SELECT id, kb_id, user_id, file_name, file_path, file_url, file_type, "
            + "status, chunk_count, created_at "
            + "FROM knowledge_document WHERE kb_id = #{kbId} ORDER BY created_at DESC")
    List<KnowledgeDocument> selectByKbId(Long kbId);

    /** 切片与向量化完成后回写统计与状态。 */
    @Update("UPDATE knowledge_document SET chunk_count = #{chunkCount}, status = #{status} WHERE id = #{id}")
    int updateParseResult(KnowledgeDocument doc);
}
