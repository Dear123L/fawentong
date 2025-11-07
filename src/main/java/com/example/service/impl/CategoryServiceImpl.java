package com.example.service.impl;

import com.example.dto.CategoryTreeDTO;
import com.example.entity.Category;
import com.example.mapper.CategoryMapper;
import com.example.service.CategoryService;
import com.example.vo.CategoryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryServiceImpl implements CategoryService {
    @Autowired
    private CategoryMapper categoryMapper;

    /**
     * 获取所有分类的完整树形结构
     */
    @Override
    public List<CategoryTreeDTO> getFullCategoryTree() {
        List<Category> allCategories = categoryMapper.selectAllCategories();
        return buildCategoryTree(allCategories);
    }

    /**
     * 根据一级分类名称获取该分类的完整树形结构
     * @param topCategoryName 一级分类名称，如："维权通"、"法规案例通"、"文书通"
     */
    @Override
    public CategoryTreeDTO getCategoryTreeByName(String topCategoryName) {
        List<Category> allCategories = categoryMapper.selectAllCategories();
        return buildSingleCategoryTree(allCategories, topCategoryName);
    }

    /**
     * 根据分类ID获取该分类的完整树形结构
     */
    @Override
    public CategoryTreeDTO getCategoryTreeById(Long categoryId) {
        List<Category> allCategories = categoryMapper.selectAllCategories();

        // 找到目标分类
        Category targetCategory = allCategories.stream()
                .filter(c -> c.getId().equals(categoryId))
                .findFirst()
                .orElse(null);

        if (targetCategory == null) {
            return null;
        }

        return buildSingleCategoryTree(allCategories, targetCategory.getName());
    }

    /**
     * 构建完整的分类树（所有一级分类）
     */
    private List<CategoryTreeDTO> buildCategoryTree(List<Category> allCategories) {
        List<CategoryTreeDTO> result = new ArrayList<>();
        Map<Long, List<CategoryTreeDTO>> parentChildrenMap = buildParentChildrenMap(allCategories);

        // 获取所有一级分类
        List<CategoryTreeDTO> level1Categories = parentChildrenMap.get(0L);
        if (level1Categories != null) {
            for (CategoryTreeDTO level1 : level1Categories) {
                setChildren(level1, parentChildrenMap);
                result.add(level1);
            }
        }

        return result;
    }

    /**
     * 构建单个分类的树形结构
     */
    private CategoryTreeDTO buildSingleCategoryTree(List<Category> allCategories, String categoryName) {
        Map<Long, List<CategoryTreeDTO>> parentChildrenMap = buildParentChildrenMap(allCategories);

        // 找到指定名称的一级分类
        List<CategoryTreeDTO> level1Categories = parentChildrenMap.get(0L);
        if (level1Categories != null) {
            for (CategoryTreeDTO level1 : level1Categories) {
                if (categoryName.equals(level1.getName())) {
                    setChildren(level1, parentChildrenMap);
                    return level1;
                }
            }
        }

        return null;
    }

    /**
     * 构建父级-子级映射关系
     */
    private Map<Long, List<CategoryTreeDTO>> buildParentChildrenMap(List<Category> allCategories) {
        Map<Long, List<CategoryTreeDTO>> parentChildrenMap = new HashMap<>();

        for (Category category : allCategories) {
            CategoryTreeDTO dto = new CategoryTreeDTO(category);
            Long parentId = category.getParentId();
            parentChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(dto);
        }

        return parentChildrenMap;
    }

    /**
     * 递归设置子分类
     */
    private void setChildren(CategoryTreeDTO parent, Map<Long, List<CategoryTreeDTO>> parentChildrenMap) {
        List<CategoryTreeDTO> children = parentChildrenMap.get(parent.getId());
        if (children != null) {
            for (CategoryTreeDTO child : children) {
                setChildren(child, parentChildrenMap);
            }
            parent.setChildren(children);
        }
    }

    /**
     * 获取所有一级分类（简单列表）
     */
    @Override
    public List<CategoryVO> getTopLevelCategories() {
        return categoryMapper.selectByLevel(1);
    }

    /**
     * 根据父级ID获取子分类（简单列表）
     */
    @Override
    public List<CategoryVO> getChildrenByParentId(Long parentId) {
        return categoryMapper.selectByParentId(parentId);
    }
}