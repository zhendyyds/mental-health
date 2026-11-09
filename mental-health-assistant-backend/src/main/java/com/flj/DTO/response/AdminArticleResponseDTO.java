package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文章分页行（管理端）：含已删除行，deleted 为标记。
 */
@Data
public class AdminArticleResponseDTO {
    private String id;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String authorName;
    private Integer readCount;
    // 生命周期 0:草稿 1:已发布 2:已下线
    private Integer status;
    // 首发时间（草稿为 null）
    private LocalDateTime publishedAt;
    // 最后编辑时间
    private LocalDateTime updatedAt;
    // 软删除标记
    private Boolean deleted;
}
