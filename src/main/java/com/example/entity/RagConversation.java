package com.example.entity;

import lombok.Data;

import java.util.Date;

/**
 * RAG 会话轮次记录（对应表 rag_conversation）。
 * 短期记忆在 Redis，本表用于历史查询与长期画像抽取的语料来源。
 */
@Data
public class RagConversation {
    private Long id;
    /** 用户 ID（长期记忆主键） */
    private Long userId;
    /** 知识库 ID */
    private Long kbId;
    /** 会话 ID（短期记忆键） */
    private String sessionId;
    /** 用户提问 */
    private String question;
    /** 系统答复 */
    private String answer;
    private Date createdAt;
}
