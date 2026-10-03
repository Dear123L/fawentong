package com.example.mapper;

import com.example.entity.RagConversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 会话轮次数据访问（表 rag_conversation）。
 * 列名与真实 schema 对齐：无 turn_index / clause_ids（轮次序号由查询顺序隐含）。
 */
@Mapper
public interface RagConversationMapper {

    /** 落一条问答轮次。 */
    @Insert("INSERT INTO rag_conversation(user_id, kb_id, session_id, question, answer, created_at) "
            + "VALUES(#{userId}, #{kbId}, #{sessionId}, #{question}, #{answer}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RagConversation conv);

    /** 查某会话的历史轮次（按时间升序）。 */
    @Select("SELECT id, user_id, kb_id, session_id, question, answer, created_at "
            + "FROM rag_conversation WHERE session_id = #{sessionId} ORDER BY created_at ASC, id ASC")
    List<RagConversation> selectBySessionId(String sessionId);

    /** 该会话已落库的轮次数（新增轮次时 +1 作为序号）。 */
    @Select("SELECT COUNT(*) FROM rag_conversation WHERE session_id = #{sessionId}")
    int countBySessionId(String sessionId);
}
