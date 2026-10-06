package com.example.service.impl;

import com.alibaba.fastjson2.JSON;
import com.example.entity.ReviewRecord;
import com.example.entity.RiskPoint;
import com.example.mapper.ReviewRecordMapper;
import com.example.service.AiAnalysisService;
import com.example.service.ContractReviewService;
import com.example.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
public class ContractReviewServiceImpl implements ContractReviewService {

    @Autowired
    private ReviewRecordMapper recordMapper;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired  // 添加OCR服务
    private OcrService ocrService;

    @Value("${file.contract-upload-dir:/images/contract/}")
    private String contractUploadDir;

    @Value("${file.base-url}")
    private String baseUrl;

    /**
     * 上传多张图片并审查合同
     */
    @Override
    public ReviewRecord uploadAndReview(List<MultipartFile> files, String recordName, Long userId) throws IOException {
        try {
            // 1. 参数校验
            if (files == null || files.isEmpty()) {
                throw new IllegalArgumentException("请至少上传一个文件");
            }

            // 2. 生成记录名称
            if (!StringUtils.hasText(recordName)) {
                recordName = "合同审查_" + System.currentTimeMillis();
            }

            // 3. 生成批次ID和统一的时间戳
            String batchId = UUID.randomUUID().toString().substring(0, 8);
            String timestamp = String.valueOf(System.currentTimeMillis());

            // 4. 保存所有文件并生成URL列表（按上传顺序）
            List<String> fileUrls = new ArrayList<>();
            String firstFileName = null;
            String firstFilePath = null;

            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String fileUrl = saveFile(file, userId, batchId, timestamp, i + 1);
                fileUrls.add(fileUrl);

                // 记录第一个文件信息
                if (i == 0) {
                    firstFileName = file.getOriginalFilename();
                    firstFilePath = getFilePathFromUrl(fileUrl);
                }
            }

            // 5. OCR提取文字（使用正式OCR）
            StringBuilder allTextBuilder = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String text = ocrService.extractText(file);
                allTextBuilder.append("【第").append(i + 1).append("页】\n");
                allTextBuilder.append(text).append("\n\n");
            }
            String contractText = allTextBuilder.toString();
            log.info("OCR提取完成，处理{}张图片，总文字长度: {}", files.size(), contractText.length());

            // 6. AI分析风险（调用真实AI服务）
            List<RiskPoint> riskPoints = aiAnalysisService.analyzeContract(contractText);
            log.info("AI分析完成，发现风险点: {}个", riskPoints.size());
            
            ReviewRecord record = new ReviewRecord();
            record.setId(System.currentTimeMillis());
            record.setUserId(userId);
            record.setRecordName(recordName);
            record.setFileName(firstFileName);
            if (!fileUrls.isEmpty()) {
                record.setFilePath(fileUrls.get(0));
            }
            record.setFileUrls(JSON.toJSONString(fileUrls));
            record.setFileUrlList(fileUrls);
            record.setContractText(contractText);
            record.setRiskCount(riskPoints.size());
            record.setRiskPointsJson(JSON.toJSONString(riskPoints));
            record.setRiskPoints(riskPoints);
            record.setStatus("1");

            // 8. 保存到数据库
            int result = recordMapper.insert(record);
            if (result > 0) {
                log.info("合同审查完成，用户ID: {}, 文件数: {}, 风险点: {}个",
                        userId, files.size(), riskPoints.size());
                return record;
            } else {
                throw new RuntimeException("保存记录失败");
            }

        } catch (Exception e) {
            log.error("合同审查失败，用户ID: {}", userId, e);
            throw new RuntimeException("合同审查失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存文件到服务器并生成URL（根据文件类型区分路径）
     */
    private String saveFile(MultipartFile file, Long userId, String batchId, String timestamp, int index) throws IOException {
        // 获取文件扩展名
        String originalFileName = file.getOriginalFilename();
        String extension = "";
        String fileType = "file"; // 默认类型

        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf(".")).toLowerCase();

            // 判断文件类型
            if (extension.matches("\\.(jpg|jpeg|png|gif|bmp|webp|svg)$")) {
                fileType = "images";
            } else {
                fileType = "file";
            }
        }

        // 创建用户目录：/images/{fileType}/contract/{userId}/
        String userDir = "/images/" + fileType + "/contract/" + userId;
        Path userPath = Paths.get(userDir);
        if (!Files.exists(userPath)) {
            Files.createDirectories(userPath);
        }

        // 生成安全文件名
        String safeFileName;
        if (originalFileName != null) {
            String nameWithoutExt;
            if (originalFileName.contains(".")) {
                nameWithoutExt = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
                extension = originalFileName.substring(originalFileName.lastIndexOf('.'));
            } else {
                nameWithoutExt = originalFileName;
                extension = "";
            }

            // 清理文件名中的特殊字符，保留中文字符
            nameWithoutExt = nameWithoutExt.replaceAll("[\\\\/:*?\"<>|]", "_");

            // 新文件名：原文件名_时间戳_批次_序号.扩展名
            safeFileName = String.format("%s_%s_%s_%d%s",
                    nameWithoutExt, timestamp, batchId, index, extension);
        } else {
            // 如果没有原始文件名，根据类型生成默认扩展名
            if (fileType.equals("images")) {
                safeFileName = String.format("image_%s_%s_%d.jpg", timestamp, batchId, index);
            } else {
                safeFileName = String.format("file_%s_%s_%d.docx", timestamp, batchId, index);
            }
        }

        // 保存文件
        Path filePath = userPath.resolve(safeFileName);
        Files.copy(file.getInputStream(), filePath);

        // 生成访问URL：/static/{fileType}/contract/{userId}/{fileName}
        String fileUrl = String.format("%s/static/%s/contract/%s/%s",
                baseUrl, fileType, userId, safeFileName);

        log.info("文件保存成功: 类型={}, 路径={}, URL={}", fileType, filePath, fileUrl);
        return fileUrl;
    }

    /**
     * 从URL提取文件路径
     */
    private String getFilePathFromUrl(String fileUrl) {
        try {
            // 从URL中提取文件类型和相对路径
            String prefix = baseUrl + "/static/";
            if (fileUrl.startsWith(prefix)) {
                String relativePath = fileUrl.substring(prefix.length());

                // 解析路径格式：{fileType}/contract/{userId}/{fileName}
                String[] parts = relativePath.split("/", 4); // 最多分割4部分

                if (parts.length >= 4) {
                    String fileType = parts[0]; // images 或 file
                    // parts[1] 应该是 "contract"
                    String userId = parts[2];
                    String fileName = parts[3];

                    // 构建本地文件路径：/images/{fileType}/contract/{userId}/{fileName}
                    String localPath = String.format("/images/%s/contract/%s/%s",
                            fileType, userId, fileName);

                    log.debug("从URL提取路径: {} -> {}", fileUrl, localPath);
                    return localPath;
                }
            }
        } catch (Exception e) {
            log.error("从URL提取路径失败: {}", fileUrl, e);
        }

        return null;
    }

    @Override
    public ReviewRecord saveRecord(Long recordId, String recordName, Long userId) {
        try {
            // 参数校验
            if (recordId == null) {
                throw new IllegalArgumentException("记录ID不能为空");
            }
            if (userId == null) {
                throw new IllegalArgumentException("用户ID不能为空");
            }
            if (!StringUtils.hasText(recordName)) {
                throw new IllegalArgumentException("记录名称不能为空");
            }

            // 查询记录
            ReviewRecord record = recordMapper.selectByIdAndUserId(recordId, userId);
            if (record == null) {
                throw new RuntimeException("记录不存在或无权限");
            }

            // 更新记录
            int result = recordMapper.updateRecord(recordId, userId, recordName, "1");
            if (result > 0) {
                // 重新查询并解析JSON字段
                record = recordMapper.selectByIdAndUserId(recordId, userId);
                parseJsonFields(record);
                return record;
            } else {
                throw new RuntimeException("更新记录失败");
            }

        } catch (Exception e) {
            log.error("保存记录失败，用户ID: {}, 记录ID: {}", userId, recordId, e);
            throw new RuntimeException("保存记录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ReviewRecord> getUserRecords(Long userId) {
        try {
            if (userId == null) {
                throw new IllegalArgumentException("用户ID不能为空");
            }

            List<ReviewRecord> records = recordMapper.selectByUserId(userId);

            log.info("获取用户记录成功，用户ID: {}, 记录数量: {}", userId, records.size());
            return records;

        } catch (Exception e) {
            log.error("获取用户记录失败，用户ID: {}", userId, e);
            throw new RuntimeException("获取历史记录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ReviewRecord getRecordDetail(Long recordId, Long userId) {
        try {
            if (recordId == null) {
                throw new IllegalArgumentException("记录ID不能为空");
            }
            if (userId == null) {
                throw new IllegalArgumentException("用户ID不能为空");
            }

            ReviewRecord record = recordMapper.selectByIdAndUserId(recordId, userId);
            if (record == null) {
                throw new RuntimeException("记录不存在或无权限");
            }

            // 解析JSON字段
            parseJsonFields(record);

            log.info("获取记录详情成功，用户ID: {}, 记录ID: {}", userId, recordId);
            return record;

        } catch (Exception e) {
            log.error("获取记录详情失败，用户ID: {}, 记录ID: {}", userId, recordId, e);
            throw new RuntimeException("获取记录详情失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteRecord(Long recordId, Long userId) {
        try {
            if (recordId == null) {
                throw new IllegalArgumentException("记录ID不能为空");
            }
            if (userId == null) {
                throw new IllegalArgumentException("用户ID不能为空");
            }

            int result = recordMapper.softDelete(recordId, userId);
            boolean success = result > 0;

            if (success) {
                log.info("删除记录成功，用户ID: {}, 记录ID: {}", userId, recordId);
            } else {
                log.warn("删除记录失败或无权限，用户ID: {}, 记录ID: {}", userId, recordId);
            }

            return success;

        } catch (Exception e) {
            log.error("删除记录失败，用户ID: {}, 记录ID: {}", userId, recordId, e);
            throw new RuntimeException("删除记录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析JSON字段到业务对象
     */
    private void parseJsonFields(ReviewRecord record) {
        try {
            // 解析文件URL列表
            if (StringUtils.hasText(record.getFileUrls())) {
                List<String> fileUrlList = JSON.parseArray(record.getFileUrls(), String.class);
                record.setFileUrlList(fileUrlList);
                log.info("解析fileUrls成功: {}", fileUrlList);
            } else {
                log.warn("fileUrls为空或null，记录ID: {}", record.getId());
            }

            // 解析风险点
            if (StringUtils.hasText(record.getRiskPointsJson())) {
                List<RiskPoint> riskPoints = JSON.parseArray(record.getRiskPointsJson(), RiskPoint.class);
                record.setRiskPoints(riskPoints);
            }
        } catch (Exception e) {
            log.warn("解析JSON字段失败，记录ID: {}", record.getId(), e);
        }
    }
}