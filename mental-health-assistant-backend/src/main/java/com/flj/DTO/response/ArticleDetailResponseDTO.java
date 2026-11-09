package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文章详情：管理端编辑回显与用户端详情共用。
 * tags 为原始逗号串，tagArray 为其拆分结果（ArticleDialog 既有契约）。
 */
@Data
public class ArticleDetailResponseDTO {
    private String id;
    private String title;
    private String summary;
    private String content;
    private String coverImage;
    private Long categoryId;
    private String categoryName;
    private String tags;
    private List<String> tagArray;
    private String authorName;
    private Integer readCount;
    private Integer status;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
}
