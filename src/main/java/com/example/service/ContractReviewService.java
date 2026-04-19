package com.example.service;

import com.example.entity.ReviewRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ContractReviewService {

    ReviewRecord  uploadAndReview(List<MultipartFile> file, String recordName, Long userId) throws IOException;

    ReviewRecord saveRecord(Long recordId, String recordName, Long userId);

    List<ReviewRecord> getUserRecords(Long userId);

    ReviewRecord getRecordDetail(Long recordId, Long userId);

    boolean deleteRecord(Long recordId, Long userId);
}