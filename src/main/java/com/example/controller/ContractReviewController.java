package com.example.controller;

import com.example.entity.ReviewRecord;
import com.example.service.ContractReviewService;
import com.example.util.JsonResponse;
import com.example.util.SecurityUtils;
import com.example.util.UserContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/contract")
@CrossOrigin(origins = "*")
public class ContractReviewController {

    @Autowired
    private ContractReviewService contractReviewService;

    /**
     * 上传并审查合同（支持多文件）
     */
    @PostMapping("/uploadAndReview")
    public JsonResponse uploadAndReview(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "recordName", required = false) String recordName) {
        try {
//            TODO
            Long userId = UserContext.getCurrentUserId();
            if (userId == null || !SecurityUtils.isLogin()) {
                return JsonResponse.authError("用户未登录");
            }

            if (files == null || files.isEmpty()) {
                return JsonResponse.fail(400, "请至少上传一个文件");
            }

            ReviewRecord result = contractReviewService.uploadAndReview(files, recordName, userId);
            return JsonResponse.success("合同审查完成", result);

        } catch (IllegalArgumentException e) {
            log.warn("参数错误", e);
            return JsonResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("合同审查异常", e);
            return JsonResponse.fail(500, "合同审查失败: " + e.getMessage());
        }
    }

    /**
     * 保存/重命名记录
     */
    @PostMapping("/saveRecord")
    public JsonResponse saveRecord(@RequestBody SaveRecordRequest request) {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null || !SecurityUtils.isLogin()) {
                return JsonResponse.authError("用户未登录");
            }
//            Long userId = 666L;

            ReviewRecord record = contractReviewService.saveRecord(
                    request.getRecordId(),
                    request.getRecordName(),
                    userId
            );
            return JsonResponse.success("保存记录成功", record);

        } catch (IllegalArgumentException e) {
            log.warn("参数错误", e);
            return JsonResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("保存记录异常", e);
            return JsonResponse.fail(500, "保存记录失败");
        }
    }

    /**
     * 获取用户历史记录
     */
    @GetMapping("/getUserRecords")
    public JsonResponse getUserRecords() {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null || !SecurityUtils.isLogin()) {
                return JsonResponse.authError("用户未登录");
            }
//            Long userId = 666L;

            List<ReviewRecord> records = contractReviewService.getUserRecords(userId);
            return JsonResponse.success("获取历史记录成功", records);

        } catch (Exception e) {
            log.error("获取历史记录异常", e);
            return JsonResponse.fail(500, "获取历史记录失败");
        }
    }

    /**
     * 获取记录详情
     */
    @GetMapping("/getRecordDetail")
    public JsonResponse getRecordDetail(@RequestParam("recordId") Long recordId) {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null || !SecurityUtils.isLogin()) {
                return JsonResponse.authError("用户未登录");
            }

            ReviewRecord record = contractReviewService.getRecordDetail(recordId, userId);
            return JsonResponse.success("获取记录详情成功", record);

        } catch (IllegalArgumentException e) {
            log.warn("参数错误", e);
            return JsonResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("获取记录详情异常", e);
            return JsonResponse.fail(500, "获取记录详情失败");
        }
    }

    /**
     * 删除记录（软删除）
     */
    @DeleteMapping("/deleteRecord")
    public JsonResponse deleteRecord(@RequestParam("recordId") Long recordId) {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null || !SecurityUtils.isLogin()) {
                return JsonResponse.authError("用户未登录");
            }

            boolean success = contractReviewService.deleteRecord(recordId, userId);
            if (success) {
                return JsonResponse.success("删除记录成功");
            } else {
                return JsonResponse.fail(404, "记录不存在或无权限");
            }

        } catch (IllegalArgumentException e) {
            log.warn("参数错误", e);
            return JsonResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("删除记录异常", e);
            return JsonResponse.fail(500, "删除记录失败");
        }
    }

    @Data
    public static class SaveRecordRequest {
        private Long recordId;
        private String recordName;
    }
}