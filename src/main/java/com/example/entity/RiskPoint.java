package com.example.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskPoint {
    private String riskNumber;    // 风险点序号
    private String chapter;       // 章节（第几款第几项）
    private String originalText;  // 原文
    private String riskWarning;   // 风险警示
}