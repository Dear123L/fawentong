package com.example.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文档文本抽取：把上传的 docx/pdf/txt 等文件解析为纯文本，供切片与向量化。
 */
public interface DocumentTextExtractorService {

    /**
     * 抽取文件纯文本。
     *
     * @param file 上传文件
     * @return 解析出的纯文本；不支持的类型返回空串
     */
    String extractText(MultipartFile file);

    /**
     * 条款级切分：按"第X条"把文本切成独立条款，超长条款（&gt;800 字）递归拆子块。
     * 同一条款（含子块）共享 {@code canonicalId}，子块靠 {@code subIndex} 区分。
     *
     * @param text         原始文本
     * @param sourceLabel  来源标识（通常传 "doc" + docId），用于构造 canonicalId 前缀
     * @return 条款块列表
     */
    java.util.List<ClauseBlock> splitIntoClauseBlocks(String text, String sourceLabel);
}
