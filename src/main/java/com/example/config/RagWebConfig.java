package com.example.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * RAG 上传文件的静态资源映射。
 *
 * <p>把 {@code rag.file.upload-dir} 映射为 {@code /rag-files/**}，使前端可直接访问
 * 上传后的文档（下载模板、预览原文等）。
 */
@Slf4j
@Configuration
public class RagWebConfig implements WebMvcConfigurer {

    @Value("${rag.file.upload-dir:/files/rag/}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = uploadDir.endsWith("/") ? uploadDir : uploadDir + "/";
        registry.addResourceHandler("/rag-files/**")
                .addResourceLocations("file:" + dir);
        log.info("RAG 静态文件映射: /rag-files/** -> file:{}", dir);
    }
}
