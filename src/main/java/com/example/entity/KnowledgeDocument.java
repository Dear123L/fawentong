package com.example.entity;

import lombok.Data;

import java.util.Date;

/**
 * 知识库文档（上传的原始文件记录）。
 * 文件解析出纯文本后按 chunk 切片，向量化写入 ES；本表只存元数据与原文路径。
 */
@Data
public class KnowledgeDocument {
    private Long id;
    /** 所属知识库 ID */
    private Long kbId;
    /** 上传者用户 ID */
    private Long userId;
    /** 原始文件名 */
    private String fileName;
    /** 文件存储路径 */
    private String filePath;
    /** 文件类型/MIME */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 解析后的纯文本长度（字符数） */
    private Integer contentLength;
    /** 切片数量 */
    private Integer chunkCount;
    /** 处理状态：pending / processing / done / failed */
    private String status;
    private Date createdTime;
}
