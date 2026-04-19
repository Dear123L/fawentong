package com.example.controller;

import com.example.dto.ConsumerComplaintRequestDTO;
import com.example.dto.DepositRefundRequestDTO;
import com.example.service.DocumentService;
import com.example.util.JsonResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64; // 替换导入
import java.util.HashMap;
import java.util.Map;

/**
 * 文档生成控制器
 */
@RestController
@RequestMapping("/api/document")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    /**
     * 方案1：生成Base64编码的文档（推荐）
     * 返回完整的文档信息，包含Base64编码的内容
     */
    @PostMapping("/generate/deposit-refund")
    public JsonResponse generateDepositRefundLetter(@RequestBody DepositRefundRequestDTO requestDTO) {
        try {
            // 1. 生成文档内容
            byte[] documentBytes = documentService.generateDepositRefundLetter(requestDTO);

            // 2. 获取文件名
            String fileName = documentService.getFileName(requestDTO);

            // 3. 转换为Base64字符串 - 使用标准库替换
            String base64Content = Base64.getEncoder().encodeToString(documentBytes);

            // 4. 构建直接下载的Data URL
            String downloadUrl = "data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;base64," + base64Content;

            // 5. 构建返回数据
            Map<String, Object> result = new HashMap<>();
            result.put("fileName", fileName);
            result.put("fileSize", documentBytes.length);
            result.put("fileType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            result.put("base64Content", base64Content);
            result.put("downloadUrl", downloadUrl);
            result.put("message", "文档生成成功，可使用downloadUrl直接下载");

            return JsonResponse.success("文档生成成功", result);

        } catch (Exception e) {
            e.printStackTrace();
            return JsonResponse.systemError("生成文档失败: " + e.getMessage());
        }
    }


    /**
     * 最简单的解决方案：使用英文文件名
     */
    @PostMapping("/download/deposit-refund")
    public ResponseEntity<byte[]> downloadDepositRefundLetter(@RequestBody DepositRefundRequestDTO requestDTO) {
        try {
            // 1. 生成文档内容
            byte[] documentBytes = documentService.generateDepositRefundLetter(requestDTO);

            // 2. 使用英文文件名避免编码问题
            String fileName = "deposit_refund_" + System.currentTimeMillis() + ".docx";

            // 3. 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.setContentDispositionFormData("attachment", fileName);
            headers.setCacheControl("no-cache, no-store, must-revalidate");
            headers.setPragma("no-cache");
            headers.setExpires(0);
            headers.setContentLength(documentBytes.length);

            // 4. 返回文件流
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(documentBytes);

        } catch (Exception e) {
            e.printStackTrace();
            String errorMessage = "生成文档失败: " + e.getMessage();
            return ResponseEntity.internalServerError()
                    .body(errorMessage.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 生成消费者协会投诉书 - 直接下载（英文文件名）
     */
    @PostMapping("/download/consumer-complaint")
    public ResponseEntity<byte[]> downloadConsumerComplaintLetter(@RequestBody ConsumerComplaintRequestDTO requestDTO) {
        try {
            // 1. 生成文档内容
            byte[] documentBytes = documentService.generateConsumerComplaintLetter(requestDTO);

            // 2. 使用英文文件名避免编码问题
            String fileName = "consumer_complaint_" + System.currentTimeMillis() + ".docx";

            // 3. 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.setContentDispositionFormData("attachment", fileName);
            headers.setCacheControl("no-cache, no-store, must-revalidate");
            headers.setPragma("no-cache");
            headers.setExpires(0);
            headers.setContentLength(documentBytes.length);

            // 4. 返回文件流
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(documentBytes);

        } catch (Exception e) {
            e.printStackTrace();
            String errorMessage = "生成消费者协会投诉书失败: " + e.getMessage();
            return ResponseEntity.internalServerError()
                    .body(errorMessage.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 方案B：适配小程序的下载接口
     * 小程序可以使用wx.downloadFile下载
     */
    @PostMapping("/download/deposit-refund-mini")
    public ResponseEntity<byte[]> downloadDepositRefundForMini(@RequestBody DepositRefundRequestDTO requestDTO) {
        try {
            // 1. 生成文档内容
            byte[] documentBytes = documentService.generateDepositRefundLetter(requestDTO);

            // 2. 获取文件名
            String fileName = documentService.getFileName(requestDTO);

            // 确保.docx扩展名
            if (!fileName.toLowerCase().endsWith(".docx")) {
                fileName = fileName + ".docx";
            }

            // 3. 设置响应头 - 针对小程序的简化版本
            HttpHeaders headers = new HttpHeaders();

            // 设置正确的Content-Type
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

            // 小程序通常能处理原始文件名，不需要复杂的编码
            headers.setContentDispositionFormData("attachment", fileName);

            // 设置Content-Length，小程序可能需要
            headers.setContentLength(documentBytes.length);

            // 4. 返回文件流
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(documentBytes);

        } catch (Exception e) {
            e.printStackTrace();
            String errorMessage = "生成文档失败: " + e.getMessage();
            return ResponseEntity.internalServerError()
                    .body(errorMessage.getBytes(StandardCharsets.UTF_8));
        }
    }
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public JsonResponse health() {
        return JsonResponse.success("文档服务正常运行");
    }
}
