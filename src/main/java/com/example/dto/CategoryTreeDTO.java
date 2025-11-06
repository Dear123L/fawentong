package com.example.dto;

import com.example.entity.Category;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class CategoryTreeDTO {
    private Long id;
    private String name;
    private Long parentId;
    private Integer level;
    private String icon;
    private Integer sortOrder;
    private Date createdTime;
    // ✅ 只在DTO中加children
    private List<CategoryTreeDTO> children;
    
    // 从Category实体转换的构造方法
    public CategoryTreeDTO(Category category) {
        this.id = category.getId();
        this.name = category.getName();
        this.parentId = category.getParentId();
        this.level = category.getLevel();
        this.icon = category.getIcon();
        this.sortOrder = category.getSortOrder();
        this.createdTime = category.getCreatedTime();
    }
}