// 1. 创建接口：DocumentConverterService.java
package com.example.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentConverterService {
    
    /**
     * 检查文件是否支持
     */
    boolean isSupportedFile(MultipartFile file);
    
    /**
     * 将文档转换为图片字节数组列表
     */
    List<byte[]> convertToImages(MultipartFile file) throws Exception;
}