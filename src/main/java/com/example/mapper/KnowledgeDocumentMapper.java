package com.example.mapper;

import com.example.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库文档数据访问。
 */
@Mapper
public interface KnowledgeDocumentMapper {

    /** 登记一份上传文档（入库时状态为 pending）。 */
    @Insert("INSERT INTO knowledge_document(kb_id, user_id, file_name, file_path, file_type, file_size, "
            + "content_length, chunk_count, status, created_time) "
            + "VALUES(#{kbId}, #{userId}, #{fileName}, #{filePath}, #{fileType}, #{fileSize}, "
            + "#{contentLength}, #{chunkCount}, #{status}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeDocument doc);

    @Select("SELECT id, kb_id, user_id, file_name, file_path, file_type, file_size, "
            + "content_length, chunk_count, status, created_time "
            + "FROM knowledge_document WHERE id = #{id}")
    KnowledgeDocument selectById(Long id);

    /** 列出某知识库下的全部文档。 */
    @Select("SELECT id, kb_id, user_id, file_name, file_path, file_type, file_size, "
            + "content_length, chunk_count, status, created_time "
            + "FROM knowledge_document WHERE kb_id = #{kbId} ORDER BY created_time DESC")
    List<KnowledgeDocument> selectByKbId(Long kbId);

    /** 切片与向量化完成后回写统计与状态。 */
    @Update("UPDATE knowledge_document SET content_length = #{contentLength}, "
            + "chunk_count = #{chunkCount}, status = #{status} WHERE id = #{id}")
    int updateParseResult(KnowledgeDocument doc);
}
