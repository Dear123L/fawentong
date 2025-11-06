package com.example.entity;

import lombok.Data;

import java.util.Date;

@Data
public class Category {
    private Long id;
    private String name;
    private Long parentId;
    private Integer level;
    private String icon;
    private Integer sortOrder;
    private Date createdTime;
}