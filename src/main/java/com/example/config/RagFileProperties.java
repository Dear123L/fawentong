package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 文件存储配置（对应 application.yml 的 {@code rag.file.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.file")
public class RagFileProperties {
    /** 上传目录（独立于业务图片目录） */
    private String uploadDir = "/files/rag/";
    /** 服务对外地址，用于拼接文件访问 URL */
    private String baseUrl = "http://localhost:8080";
}
