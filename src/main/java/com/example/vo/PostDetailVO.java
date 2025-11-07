package com.example.vo;

import lombok.Data;

/**
 * @author lhh
 */
@Data
public class PostDetailVO {
    private Long id;
    private String title;
    private String excerpt;          // 简介/摘要
    private String coverImage;       // 封面图
    private String content;          // 内容
    private Long categoryId;         // 分类ID
}
