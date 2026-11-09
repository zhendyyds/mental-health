package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文章分页行（用户端）：仅已发布未删除文章。
 */
@Data
public class UserArticleResponseDTO {
    private String id;
    private String title;
    private String summary;
    private String coverImage;
    private String categoryName;
    private String authorName;
    private Integer readCount;
    private LocalDateTime publishedAt;
}
