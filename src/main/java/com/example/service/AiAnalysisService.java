package com.example.service;

import com.example.entity.RiskPoint;

import java.util.List;

public interface AiAnalysisService {
    /**
     * 分析合同风险
     */
    List<RiskPoint> analyzeContract(String contractText);
}