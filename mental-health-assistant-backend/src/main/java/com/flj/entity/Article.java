package com.flj.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文章：管理员创作的公开科普内容（见 CONTEXT.md「知识内容域」）。
 * 主键为前端生成的 UUID（ADR-0006）；status 是生命周期（与软删除正交）。
 * 表 knowledge_article 随原项目库导入，唯一增量为 deleted 列（见 docs/sql/knowledge-article.sql）。
 */
@Data
@TableName("knowledge_article")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article {
    // 文章ID（客户端生成 UUID）
    @TableId(type = IdType.INPUT)
    private String id;

    // 标题
    @TableField("title")
    private String title;

    // 分类ID
    @TableField("category_id")
    private Long categoryId;

    // 摘要（可选）
    @TableField("summary")
    private String summary;

    // 正文（富文本 HTML，入库前已按白名单清洗）
    @TableField("content")
    private String content;

    // 封面图路径
    @TableField("cover_image")
    private String coverImage;

    // 标签（逗号分隔，标签内禁用逗号）
    @TableField("tags")
    private String tags;

    // 作者（管理员）用户ID
    @TableField("author_id")
    private Long authorId;

    // 生命周期 0:草稿 1:已发布 2:已下线
    @TableField("status")
    private Integer status;

    // 阅读量（仅用户端详情访问计数，不去重）
    @TableField("read_count")
    private Integer readCount;

    // 首发时间（首次发布写入，重新发布不覆盖）
    @TableField("published_at")
    private LocalDateTime publishedAt;

    // 软删除标记 false:正常 true:已删除（退出生命周期，管理端带标记可见）
    @TableField("deleted")
    private Boolean deleted;

    // 创建时间
    @TableField("created_at")
    private LocalDateTime createdAt;

    // 最后编辑时间（发布流转与阅读计数不刷新）
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
