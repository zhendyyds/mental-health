package com.flj.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flj.DTO.response.CategoryResponseDTO;
import com.flj.entity.Category;
import com.flj.mapper.CategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识文章分类：当前为单级平铺（parent_id 预留层级，见 CONTEXT.md「文章分类」）。
 */
@Service
public class CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    /**
     * 全量分类（管理端下拉/筛选用；接口名 /category/tree 沿用前端既有契约）
     */
    public List<CategoryResponseDTO> listCategories() {
        LambdaQueryWrapper<Category> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(Category::getId);
        return categoryMapper.selectList(queryWrapper).stream()
                .map(category -> {
                    CategoryResponseDTO dto = new CategoryResponseDTO();
                    dto.setId(category.getId());
                    dto.setCategoryName(category.getCategoryName());
                    return dto;
                })
                .toList();
    }
}
