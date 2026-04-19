package com.example.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface OcrService {
    /**
     * 提取文件中的文字
     */
    String extractText(MultipartFile file) throws IOException;
}