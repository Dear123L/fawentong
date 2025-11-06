package com.example.entity;

import lombok.Data;

import java.util.Date;

@Data
public class Post {
    private Long id;
    private String title;
    private String excerpt;          // 简介/摘要
    private String coverImage;       // 封面图
    private String content;          // 内容
    private Long categoryId;         // 分类ID
    private Integer sortOrder;       // 排序字段
    private Date createdTime;        // 创建时间
}