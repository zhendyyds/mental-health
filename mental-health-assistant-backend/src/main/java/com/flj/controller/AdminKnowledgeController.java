package com.flj.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.flj.DTO.command.ArticleCreateDTO;
import com.flj.DTO.command.ArticleStatusDTO;
import com.flj.DTO.command.ArticleUpdateDTO;
import com.flj.DTO.response.AdminArticleResponseDTO;
import com.flj.DTO.response.ArticleDetailResponseDTO;
import com.flj.DTO.response.CategoryResponseDTO;
import com.flj.common.Result;
import com.flj.common.ResultCode;
import com.flj.exception.BusinessException;
import com.flj.rag.IndexJob;
import com.flj.rag.KnowledgeIndexRepository;
import com.flj.rag.RagEvidence;
import com.flj.rag.RagIndexSettings;
import com.flj.rag.RagRetrievalService;
import com.flj.service.CategoryService;
import com.flj.service.KnowledgeArticleService;
import com.flj.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端知识库（权限规则见 SecurityConfig：/api/admin/** 需管理员角色；
 * 管理端与用户端接口分离，见 ADR-0002）
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private KnowledgeArticleService knowledgeArticleService;

    @Autowired
    private KnowledgeIndexRepository knowledgeIndexRepository;

    @Autowired
    private RagIndexSettings ragIndexSettings;

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @GetMapping("/index-jobs")
    public Result<List<IndexJobView>> indexJobs(@RequestParam(required = false) String articleId,
                                                @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException("400", "任务查询数量必须在 1 到 100 之间");
        }
        return Result.ok(knowledgeIndexRepository.listJobs(articleId, limit).stream().map(IndexJobView::from).toList());
    }

    @PostMapping("/articles/{articleId}/reindex")
    public Result<IndexJobView> reindex(@PathVariable String articleId) {
        requireRagEnabled();
        return Result.ok(IndexJobView.from(knowledgeIndexRepository.reindex(articleId, ragIndexSettings.currentConfig().version())));
    }

    @PostMapping("/index-jobs/{jobId}/retry")
    public Result<IndexJobView> retryIndexJob(@PathVariable long jobId) {
        requireRagEnabled();
        return Result.ok(IndexJobView.from(knowledgeIndexRepository.retry(jobId)));
    }

    @PostMapping("/retrieval/debug")
    public Result<List<RagEvidence>> debugRetrieval(@Valid @RequestBody RetrievalDebugRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(ragRetrievalService.retrieve(userId, request.query()));
    }

    public record RetrievalDebugRequest(@NotBlank @Size(max = 1000) String query) { }

    public record IndexJobView(long id, String articleId, long revision, String configVersion, String generation,
                               String operation, String status, int attemptCount, boolean retryable,
                               LocalDateTime nextRetryAt, LocalDateTime leaseUntil, String errorCode,
                               LocalDateTime createdAt, LocalDateTime finishedAt) {
        private static IndexJobView from(IndexJob job) {
            return new IndexJobView(job.id(), job.articleId(), job.revision(), job.configVersion(), job.generation(),
                    job.operation(), job.status(), job.attemptCount(), job.retryable(), job.nextRetryAt(), job.leaseUntil(),
                    job.errorCode(), job.createdAt(), job.finishedAt());
        }
    }

    private void requireRagEnabled() {
        if (!ragIndexSettings.isEnabled()) {
            throw new BusinessException("503", "知识索引尚未启用");
        }
    }

    /**
     * 分类列表（当前平铺返回；接口名 tree 沿用前端既有契约）
     */
    @GetMapping("/category/tree")
    public Result<List<CategoryResponseDTO>> categoryTree() {
        return Result.ok(categoryService.listCategories());
    }

    /**
     * 管理端文章分页（含已删除行带 deleted 标记；title 模糊、categoryId/status 精确筛选）
     */
    @GetMapping("/article/page")
    public Result<Page<AdminArticleResponseDTO>> articlePage(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status) {
        return Result.ok(knowledgeArticleService.pageAdminArticles(currentPage, size, title, categoryId, status));
    }

    /**
     * 创建文章（接受客户端 UUID 为 id——ADR-0006；创建即草稿）
     */
    @PostMapping("/article")
    public Result<Void> createArticle(@Valid @RequestBody ArticleCreateDTO createDTO) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        knowledgeArticleService.createArticle(userId, createDTO);
        return Result.ok();
    }

    /**
     * 管理端文章详情（编辑回显；含 tagArray/categoryName）
     */
    @GetMapping("/article/{articleId}")
    public Result<ArticleDetailResponseDTO> articleDetail(@PathVariable String articleId) {
        return Result.ok(knowledgeArticleService.getAdminArticleDetail(articleId));
    }

    /**
     * 编辑文章（直接生效，不改变状态与首发时间）
     */
    @PutMapping("/article/{articleId}")
    public Result<Void> updateArticle(@PathVariable String articleId,
                                      @Valid @RequestBody ArticleUpdateDTO updateDTO) {
        knowledgeArticleService.updateArticle(articleId, updateDTO);
        return Result.ok();
    }

    /**
     * 发布/下线（状态机：0/2→1、1→2；首发时间仅首次发布写入）
     */
    @PutMapping("/article/{articleId}/status")
    public Result<Void> changeStatus(@PathVariable String articleId,
                                     @Valid @RequestBody ArticleStatusDTO statusDTO) {
        knowledgeArticleService.changeStatus(articleId, statusDTO.getStatus());
        return Result.ok();
    }

    /**
     * 软删除（退出生命周期，幂等；管理端列表仍可见带标记）
     */
    @DeleteMapping("/article/{articleId}")
    public Result<Void> deleteArticle(@PathVariable String articleId) {
        knowledgeArticleService.softDeleteArticle(articleId);
        return Result.ok();
    }

    // 从当前请求的 JWT 中解析用户ID（作者归属，对齐 EmotionDiaryController 先例）
    private Long getCurrentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        if (token == null) {
            return null;
        }
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }
}
