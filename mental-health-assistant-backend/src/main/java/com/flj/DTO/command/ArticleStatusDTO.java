package com.flj.DTO.command;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发布/下线请求：status 1:发布 2:下线（状态机见 KnowledgeArticleService）。
 */
@Data
public class ArticleStatusDTO {
    @NotNull(message = "目标状态不能为空")
    private Integer status;
}
