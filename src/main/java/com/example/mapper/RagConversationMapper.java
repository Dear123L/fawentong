package com.example.mapper;

import com.example.entity.RagConversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 会话轮次数据访问。
 */
@Mapper
public interface RagConversationMapper {

    /** 落一条问答轮次。 */
    @Insert("INSERT INTO rag_conversation(session_id, user_id, kb_id, turn_index, question, answer, "
            + "clause_ids, created_time) "
            + "VALUES(#{sessionId}, #{userId}, #{kbId}, #{turnIndex}, #{question}, #{answer}, "
            + "#{clauseIds}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RagConversation conv);

    /** 查某会话的历史轮次（按轮次升序）。 */
    @Select("SELECT id, session_id, user_id, kb_id, turn_index, question, answer, clause_ids, created_time "
            + "FROM rag_conversation WHERE session_id = #{sessionId} ORDER BY turn_index ASC")
    List<RagConversation> selectBySessionId(String sessionId);

    /** 取某会话已进行的最大轮次号，新增轮次时 +1。 */
    @Select("SELECT COALESCE(MAX(turn_index), 0) FROM rag_conversation WHERE session_id = #{sessionId}")
    int maxTurnIndex(String sessionId);
}
