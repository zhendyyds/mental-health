package com.flj.DTO.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑知识文章（管理端）：直接生效，不改变状态与首发时间（已发布文章在线改）。
 */
@Data
public class ArticleUpdateDTO {
    @NotBlank(message = "请输入文章标题")
    @Size(max = 200, message = "文章标题最多200个字符")
    private String title;

    @NotNull(message = "请选择文章分类")
    private Long categoryId;

    @Size(max = 1000, message = "文章摘要最多1000个字符")
    private String summary;

    @NotBlank(message = "请输入文章内容")
    private String content;

    @Size(max = 255, message = "封面图路径长度不能超过255个字符")
    private String coverImage;

    @Size(max = 255, message = "标签总长度不能超过255个字符")
    private String tags;
}
