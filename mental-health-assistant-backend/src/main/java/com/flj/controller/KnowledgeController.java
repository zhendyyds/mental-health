package com.flj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flj.DTO.response.ArticleDetailResponseDTO;
import com.flj.DTO.response.UserArticleResponseDTO;
import com.flj.common.Result;
import com.flj.service.KnowledgeArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端知识库（公开访问：SecurityConfig PUBLIC_PATHS 放行，
 * 知识内容无归属权概念，见 CONTEXT.md「知识文章」）。
 * 服务端强制只返回已发布且未删除的文章。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeArticleService knowledgeArticleService;

    /**
     * 已发布文章分页（排序白名单：publishedAt/readCount，默认 publishedAt 倒序）
     */
    @GetMapping("/article/page")
    public Result<Page<UserArticleResponseDTO>> articlePage(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection) {
        return Result.ok(knowledgeArticleService.pageUserArticles(currentPage, size, sortField, sortDirection));
    }

    /**
     * 文章详情（仅已发布未删除；命中即阅读量 +1，管理端预览不计数）
     */
    @GetMapping("/article/{articleId}")
    public Result<ArticleDetailResponseDTO> articleDetail(@PathVariable String articleId) {
        ArticleDetailResponseDTO detail = knowledgeArticleService.getUserArticleDetail(articleId);
        if (detail == null) {
            return Result.error("404", "文章不存在", null);
        }
        return Result.ok(detail);
    }
}
