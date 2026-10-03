package com.example.entity;

import lombok.Data;

import java.util.Date;

/**
 * 知识库（rag_knowledge_base 对应的业务实体）。
 * 一个知识库承载若干份文档，文档切片后向量化写入 ES 索引供检索。
 */
@Data
public class KnowledgeBase {
    private Long id;
    /** 知识库名称 */
    private String name;
    /** 类型：private=个人私有 / public=公共 */
    private String type;
    /** 归属用户 ID */
    private Long userId;
    /** 描述 */
    private String description;
    private Date createdTime;
}
