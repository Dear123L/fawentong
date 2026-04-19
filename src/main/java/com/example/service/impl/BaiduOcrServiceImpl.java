package com.example.service.impl;

import com.baidu.aip.ocr.AipOcr;
import com.example.service.DocumentConverterService;
import com.example.service.OcrService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
public class BaiduOcrServiceImpl implements OcrService {

    @Autowired  // 新增：注入文档转换服务
    private DocumentConverterService documentConverterService;

    @Value("${baidu.ocr.app-id}")
    private String appId;

    @Value("${baidu.ocr.api-key}")
    private String apiKey;

    @Value("${baidu.ocr.secret-key}")
    private String secretKey;

    private AipOcr client;

    @PostConstruct
    public void init() {
        try {
            log.info("=== 初始化百度OCR ===");
            log.info("AppID: {}", maskString(appId));
            log.info("API Key: {}", maskString(apiKey));

            if (!StringUtils.hasText(appId) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(secretKey)) {
                log.error("百度OCR配置不完整！请检查application.yml");
                throw new RuntimeException("百度OCR配置不完整");
            }

            client = new AipOcr(appId, apiKey, secretKey);
            client.setConnectionTimeoutInMillis(5000);
            client.setSocketTimeoutInMillis(60000);

            log.info("百度OCR客户端初始化成功");

        } catch (Exception e) {
            log.error("百度OCR初始化失败", e);
            throw new RuntimeException("百度OCR初始化失败: " + e.getMessage());
        }
    }

    @Override
    public String extractText(MultipartFile file) throws IOException {
        try {
            log.info("=== 开始OCR识别 ===");
            String fileName = file.getOriginalFilename();
            long fileSize = file.getSize();
            log.info("文件名: {}, 大小: {} bytes", fileName, fileSize);

            if (fileSize == 0) {
                throw new RuntimeException("文件大小为0");
            }

            // 新增：检查文件类型并处理
            String text;

            if (isImageFile(file)) {
                log.info("检测为图片文件，直接OCR处理");
                text = extractTextFromImage(file);
            } else if (isDocumentFile(file)) {
                log.info("检测为文档文件，先转换再OCR处理");
                text = extractTextFromDocument(file);
            } else {
                throw new RuntimeException("不支持的文件格式：" + fileName +
                        "。支持格式：图片(JPG/PNG/BMP/GIF)、PDF、Word文档");
            }

            log.info("OCR识别成功！识别到 {} 个字符", text.length());

            if (text.trim().isEmpty()) {
                log.warn("识别结果为空，可能是图片质量问题");
            }

            return text;

        } catch (Exception e) {
            log.error("OCR识别异常", e);
            throw new RuntimeException("OCR识别失败: " + e.getMessage());
        }
    }

    /**
     * 检查是否为图片文件
     */
    private boolean isImageFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) return false;

        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".bmp") ||
                lowerName.endsWith(".gif") || lowerName.endsWith(".webp");
    }

    /**
     * 检查是否为文档文件
     */
    private boolean isDocumentFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) return false;

        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") ||
                lowerName.endsWith(".docx");
    }

    /**
     * 从图片提取文本
     */
    private String extractTextFromImage(MultipartFile file) throws Exception {
        byte[] fileBytes = file.getBytes();
        return callBaiduOcrApi(fileBytes);
    }

    /**
     * 从文档提取文本
     */
    private String extractTextFromDocument(MultipartFile file) throws Exception {
        // 使用文档转换服务
        List<byte[]> imageBytesList = documentConverterService.convertToImages(file);
        log.info("文档转换为 {} 张图片", imageBytesList.size());

        StringBuilder allText = new StringBuilder();

        for (int i = 0; i < imageBytesList.size(); i++) {
            try {
                log.info("OCR识别第 {} 页", i + 1);
                byte[] imageBytes = imageBytesList.get(i);
                String pageText = callBaiduOcrApi(imageBytes);

                allText.append("【第").append(i + 1).append("页】\n");
                allText.append(pageText).append("\n\n");

                log.info("第 {} 页识别完成，字符数: {}", i + 1, pageText.length());

            } catch (Exception e) {
                log.error("第 {} 页OCR识别失败: {}", i + 1, e.getMessage());
                // 继续处理下一页
                allText.append("【第").append(i + 1).append("页】\n");
                allText.append("(此页识别失败: ").append(e.getMessage()).append(")\n\n");
            }
        }

        return allText.toString();
    }

    /**
     * 新增：调用百度OCR API（复用原有逻辑）
     */
    private String callBaiduOcrApi(byte[] imageBytes) throws Exception {
        log.info("调用百度OCR API，图片大小: {} bytes", imageBytes.length);

        HashMap<String, String> options = new HashMap<>();
        options.put("language_type", "CHN_ENG");  // 中英文混合
        options.put("detect_direction", "true");  // 检测图像朝向
        options.put("detect_language", "true");   // 检测语言
        options.put("probability", "false");      // 是否返回识别结果中每一行的置信度

        long startTime = System.currentTimeMillis();
        JSONObject result = client.basicGeneral(imageBytes, options);
        long endTime = System.currentTimeMillis();
        log.info("OCR API调用耗时: {}ms", endTime - startTime);

        // 检查错误
        if (result.has("error_code")) {
            int errorCode = result.getInt("error_code");
            String errorMsg = result.getString("error_msg");
            log.error("百度OCR识别失败！错误码: {}, 错误信息: {}", errorCode, errorMsg);
            throw new RuntimeException("OCR识别失败: " + errorMsg + " (错误码: " + errorCode + ")");
        }

        // 解析结果
        return parseOcrResult(result);
    }

    /**
     * 原有方法：解析OCR结果
     */
    private String parseOcrResult(JSONObject result) {
        StringBuilder textBuilder = new StringBuilder();

        try {
            if (!result.has("words_result")) {
                log.warn("OCR结果中没有words_result字段");
                return "";
            }

            JSONArray wordsResult = result.getJSONArray("words_result");
            int lineCount = wordsResult.length();
            log.debug("识别到 {} 行文本", lineCount);

            for (int i = 0; i < lineCount; i++) {
                JSONObject word = wordsResult.getJSONObject(i);
                if (word.has("words")) {
                    String line = word.getString("words");
                    textBuilder.append(line);

                    // 添加换行
                    if (i < lineCount - 1) {
                        textBuilder.append("\n");
                    }
                }
            }

            String text = textBuilder.toString();

            // 记录前100个字符用于调试
            if (text.length() > 0) {
                int previewLength = Math.min(100, text.length());
                log.debug("OCR识别预览: {}", text.substring(0, previewLength) +
                        (text.length() > previewLength ? "..." : ""));
            }

            return text;

        } catch (Exception e) {
            log.error("解析OCR结果失败", e);
            return "OCR解析失败: " + e.getMessage();
        }
    }

    private String maskString(String str) {
        if (str == null || str.length() <= 6) {
            return str;
        }
        return str.substring(0, 3) + "***" + str.substring(str.length() - 3);
    }
}