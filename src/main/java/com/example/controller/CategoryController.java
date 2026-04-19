package com.example.controller;

import com.example.dto.CategoryTreeDTO;
import com.example.entity.Category;
import com.example.service.CategoryService;
import com.example.util.JsonResponse;
import com.example.vo.CategoryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/category")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 获取所有分类的完整树形结构
     */
    @GetMapping("/tree")
    public JsonResponse getFullCategoryTree() {
        try {
            List<CategoryTreeDTO> categoryTree = categoryService.getFullCategoryTree();
            return JsonResponse.success("获取分类树成功",categoryTree);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonResponse.systemError("获取分类树失败");
        }
    }

    /**
     * 根据分类ID获取该分类的完整树形结构
     */
    @GetMapping("/tree/id/{categoryId}")
    public JsonResponse getCategoryTreeById(@PathVariable Long categoryId) {
        CategoryTreeDTO categoryTree = categoryService.getCategoryTreeById(categoryId);
        if (categoryTree == null) {
            return JsonResponse.paramError("分类不存在: " + categoryId);
        }
        return JsonResponse.success("查找成功",categoryTree);
    }

    /**
     * 获取所有一级分类（简单列表）
     */
    @GetMapping("/top-level")
    public JsonResponse getTopLevelCategories() {
        List<CategoryVO> categories = categoryService.getTopLevelCategories();
        return JsonResponse.success("获取导航栏分类成功",categories);
    }

    /**
     * 根据父级ID获取子分类（简单列表）
     */
    @GetMapping("/children/{parentId}")
    public JsonResponse getChildrenByParentId(@PathVariable Long parentId) {
        List<CategoryVO> categories = categoryService.getChildrenByParentId(parentId);
        return JsonResponse.success("获取子分类成功",categories);
    }
}