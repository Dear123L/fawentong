package com.example.controller;

import com.alibaba.fastjson2.JSON;
import com.example.entity.ReviewRecord;
import com.example.entity.RiskPoint;
import com.example.service.AiAnalysisService;
import com.example.service.OcrService;
import com.example.util.JsonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/contract/test")
@Slf4j
public class TestController {

    @Autowired
    private OcrService ocrService;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Value("${file.contract-upload-dir:/tmp/contract-test/}")
    private String contractUploadDir;

    @Value("${file.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${dashscope.api.key:}")
    private String dashscopeApiKey;

    @Value("${baidu.ocr.app-id:}")
    private String baiduAppId;

    /**
     * 检查配置
     */
    @GetMapping("/config")
    public JsonResponse checkConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("dashscopeApiKeyConfigured", StringUtils.hasText(dashscopeApiKey));
        config.put("baiduAppIdConfigured", StringUtils.hasText(baiduAppId));
        config.put("dashscopeApiKeyPreview", maskString(dashscopeApiKey));
        config.put("baiduAppIdPreview", maskString(baiduAppId));

        log.info("配置检查: {}", config);
        return JsonResponse.success("配置检查完成", config);
    }

    /**
     * 测试OCR
     */
    @PostMapping("/testOcr")
    public JsonResponse testOcr(@RequestParam("file") MultipartFile file) {
        try {
            log.info("=== OCR测试开始 ===");
            String text = ocrService.extractText(file);

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", file.getOriginalFilename());
            result.put("fileSize", file.getSize());
            result.put("textLength", text.length());
            result.put("text", text);

            log.info("=== OCR测试完成，识别 {} 个字符 ===", text.length());
            return JsonResponse.success("OCR测试完成", result);

        } catch (Exception e) {
            log.error("OCR测试失败", e);
            return JsonResponse.fail(500, "OCR测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试AI
     */
    @PostMapping("/testAi")
    public JsonResponse testAi(@RequestBody Map<String, String> request) {
        try {
            log.info("=== AI测试开始 ===");
            String text = request.get("text");

            if (!StringUtils.hasText(text)) {
                return JsonResponse.fail(400, "文本不能为空");
            }

            log.info("输入文本长度: {}", text.length());
            List<RiskPoint> riskPoints = aiAnalysisService.analyzeContract(text);

            Map<String, Object> result = new HashMap<>();
            result.put("textLength", text.length());
            result.put("riskPoints", riskPoints);
            result.put("riskCount", riskPoints.size());

            log.info("=== AI测试完成，发现 {} 个风险点 ===", riskPoints.size());
            return JsonResponse.success("AI测试完成", result);

        } catch (Exception e) {
            log.error("AI测试失败", e);
            return JsonResponse.fail(500, "AI测试失败: " + e.getMessage());
        }
    }

    private String maskString(String str) {
        if (str == null || str.length() <= 6) {
            return str;
        }
        return str.substring(0, 3) + "***" + str.substring(str.length() - 3);
    }

    /**
     * 测试接口 - 只测试OCR和AI，跳过数据库
     */
    @PostMapping("/uploadAndReview")
    public JsonResponse testUploadAndReview(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "recordName", required = false) String recordName) {
        try {
            // 硬编码测试用户ID
            Long testUserId = 1L;

            if (files == null || files.isEmpty()) {
                return JsonResponse.fail(400, "请至少上传一个文件");
            }

            log.info("测试上传合同文件，用户ID: {}, 文件数: {}", testUserId, files.size());

            // 1. 保存文件（可选）
            List<String> fileUrls = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String fileUrl = saveFileForTest(file, testUserId, i + 1);
                fileUrls.add(fileUrl);
                log.info("文件 {} 保存成功: {}", i + 1, fileUrl);
            }

            // 2. OCR提取文字
            StringBuilder allTextBuilder = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                log.info("开始OCR识别第 {} 个文件: {}", i + 1, file.getOriginalFilename());
                String text = ocrService.extractText(file);
                log.info("第 {} 页OCR识别结果，字数: {}", i + 1, text.length());
                allTextBuilder.append("【第").append(i + 1).append("页】\n");
                allTextBuilder.append(text).append("\n\n");
            }
            String contractText = allTextBuilder.toString();
            log.info("OCR提取完成，处理{}张图片，总文字长度: {}", files.size(), contractText.length());

            // 保存OCR文本到文件（便于调试）
            saveOcrTextToFile(contractText, testUserId);

            // 3. AI分析风险
            log.info("开始AI分析，文本长度: {}", contractText.length());
            List<RiskPoint> riskPoints = aiAnalysisService.analyzeContract(contractText);
            log.info("AI分析完成，发现风险点: {}个", riskPoints.size());

            // 打印风险点详情
            for (RiskPoint risk : riskPoints) {
                log.info("风险点: {}, 章节: {}, 原文: {}, 警告: {}",
                        risk.getRiskNumber(), risk.getChapter(),
                        risk.getOriginalText(), risk.getRiskWarning());
            }

            // 4. 创建返回结果（不保存到数据库）
            Map<String, Object> result = new HashMap<>();
            result.put("userId", testUserId);
            result.put("recordName", recordName != null ? recordName : "测试记录_" + System.currentTimeMillis());
            result.put("fileUrls", fileUrls);
            result.put("contractText", contractText);
            result.put("riskPoints", riskPoints);
            result.put("riskCount", riskPoints.size());
            result.put("ocrTextLength", contractText.length());
            result.put("fileCount", files.size());

            return JsonResponse.success("合同审查测试完成", result);

        } catch (IllegalArgumentException e) {
            log.warn("参数错误", e);
            return JsonResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("合同审查异常", e);
            return JsonResponse.fail(500, "合同审查失败: " + e.getMessage());
        }
    }

    /**
     * 简单的OCR测试接口（只返回OCR结果）
     */
    @PostMapping("/ocrTest")
    public JsonResponse ocrTest(@RequestParam("file") MultipartFile file) {
        try {
            log.info("OCR测试，文件名: {}, 大小: {} bytes",
                    file.getOriginalFilename(), file.getSize());

            String text = ocrService.extractText(file);

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", file.getOriginalFilename());
            result.put("fileSize", file.getSize());
            result.put("textLength", text.length());
            result.put("textPreview", text.length() > 500 ?
                    text.substring(0, 500) + "..." : text);
            result.put("fullText", text);

            log.info("OCR识别完成，识别字数: {}", text.length());
            return JsonResponse.success("OCR测试完成", result);

        } catch (Exception e) {
            log.error("OCR测试失败", e);
            return JsonResponse.fail(500, "OCR测试失败: " + e.getMessage());
        }
    }

    /**
     * 简单的AI测试接口（使用预设文本）
     */
    @PostMapping("/aiTest")
    public JsonResponse aiTest(@RequestParam("text") String text) {
        try {
            log.info("AI测试，文本长度: {}", text.length());

            List<RiskPoint> riskPoints = aiAnalysisService.analyzeContract(text);

            Map<String, Object> result = new HashMap<>();
            result.put("textLength", text.length());
            result.put("riskPoints", riskPoints);
            result.put("riskCount", riskPoints.size());

            // 打印详细风险点
            for (RiskPoint risk : riskPoints) {
                log.info("AI分析结果 - 风险点: {}, 章节: {}",
                        risk.getRiskNumber(), risk.getChapter());
            }

            return JsonResponse.success("AI测试完成", result);

        } catch (Exception e) {
            log.error("AI测试失败", e);
            return JsonResponse.fail(500, "AI测试失败: " + e.getMessage());
        }
    }

    /**
     * 保存文件到本地（仅用于测试）
     */
    private String saveFileForTest(MultipartFile file, Long userId, int index) throws IOException {
        // 创建测试目录
        String testDir = contractUploadDir + "test_" + userId;
        Path dirPath = Paths.get(testDir);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        // 生成安全的文件名
        String originalFileName = file.getOriginalFilename();
        String fileName;
        if (originalFileName != null) {
            // 清理特殊字符
            String safeName = originalFileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            fileName = String.format("test_%d_%d_%s",
                    System.currentTimeMillis(), index, safeName);
        } else {
            fileName = String.format("test_%d_%d_file",
                    System.currentTimeMillis(), index);
        }

        // 保存文件
        Path filePath = dirPath.resolve(fileName);
        Files.copy(file.getInputStream(), filePath);

        // 占位地址，不产生实际请求
        return String.format("%s/test-files/%s/%s", baseUrl, userId, fileName);
    }

    /**
     * 保存OCR文本到文件（便于查看）
     */
    private void saveOcrTextToFile(String text, Long userId) {
        try {
            String testDir = contractUploadDir + "test_" + userId;
            Path dirPath = Paths.get(testDir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            String fileName = String.format("ocr_text_%d.txt", System.currentTimeMillis());
            Path filePath = dirPath.resolve(fileName);
            Files.writeString(filePath, text);

            log.info("OCR文本已保存到: {}", filePath.toString());
        } catch (Exception e) {
            log.warn("保存OCR文本失败: {}", e.getMessage());
        }
    }
}