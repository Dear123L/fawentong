package com.example.dto;

import com.example.entity.Category;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
public class CategoryTreeDTO {
    private Long id;
    private String name;
    private Long parentId;
    private Integer level;
    private String icon;
    private List<CategoryTreeDTO> children;

    public CategoryTreeDTO(Category category) {
        this.id = category.getId();
        this.name = category.getName();
        this.parentId = category.getParentId();
        this.level = category.getLevel();
        this.icon = category.getIcon();
        this.children = null;
    }
}