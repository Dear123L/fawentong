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
     * 文本切片：按固定字符数切分，相邻切片保留 overlap 重叠以维持边界上下文。
     *
     * @param text     原始文本
     * @param size     切片长度（字符）
     * @param overlap  相邻切片重叠长度（字符）
     * @return 切片列表
     */
    java.util.List<String> splitText(String text, int size, int overlap);
}
