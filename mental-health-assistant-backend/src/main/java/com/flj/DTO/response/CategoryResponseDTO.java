package com.flj.DTO.response;

import lombok.Data;

/**
 * 文章分类（当前平铺返回，接口名保留 /category/tree 是前端既有契约）。
 */
@Data
public class CategoryResponseDTO {
    private Long id;
    private String categoryName;
}
