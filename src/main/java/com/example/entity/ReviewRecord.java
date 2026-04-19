package com.example.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRecord {
    private Long id;
    private Long userId;
    private String recordName;
    private String fileName;      // 原始文件名（或第一个文件的）
    private String filePath;      // 主文件路径（或第一个文件的）
    private String fileUrls;  // 所有文件URL列表的JSON
    private String contractText;
    private Integer riskCount;
    private String status = "1";  // 默认有效
    private String riskPointsJson;
    private Date createTime;
    private Date updateTime;

    // 非数据库字段
    private List<RiskPoint> riskPoints;
    private List<String> fileUrlList;  // 解析后的文件URL列表
}