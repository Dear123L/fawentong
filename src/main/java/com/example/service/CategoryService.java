package com.example.service;

import com.example.dto.CategoryTreeDTO;
import com.example.entity.Category;
import com.example.vo.CategoryVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface CategoryService {

    List<CategoryTreeDTO> getFullCategoryTree();

    CategoryTreeDTO getCategoryTreeByName(String topCategoryName);

    CategoryTreeDTO getCategoryTreeById(Long categoryId);

    List<CategoryVO> getTopLevelCategories();

    List<CategoryVO> getChildrenByParentId(Long parentId);
}