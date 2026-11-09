package com.flj.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flj.DTO.command.ArticleCreateDTO;
import com.flj.DTO.command.ArticleUpdateDTO;
import com.flj.DTO.response.AdminArticleResponseDTO;
import com.flj.DTO.response.ArticleDetailResponseDTO;
import com.flj.DTO.response.UserArticleResponseDTO;
import com.flj.entity.Article;
import com.flj.entity.Category;
import com.flj.entity.User;
import com.flj.exception.BusinessException;
import com.flj.mapper.ArticleMapper;
import com.flj.mapper.CategoryMapper;
import com.flj.mapper.UserMapper;
import com.flj.rag.KnowledgeIndexRepository;
import com.flj.util.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识文章服务（领域定义见 CONTEXT.md「知识内容域」）：
 * - 主键为客户端生成 UUID（ADR-0006），创建即草稿，发布从列表操作；
 * - 正文入库前 Jsoup 白名单清洗（单一卡点）；
 * - 状态机：0/2 → 1 发布（首发时间只写一次）、1 → 2 下线；
 * - 软删除 = 退出生命周期（不可编辑/发布，管理端列表带标记可见）；
 * - 阅读量：仅用户端详情计数，原子自增不去重。
 */
@Service
public class KnowledgeArticleService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeArticleService.class);

    // 客户端 UUID 主键格式（ADR-0006）
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // 正文清洗后的宽松上限（前端编辑器限 5000 字，此处对 HTML 长度兜底）
    private static final int MAX_CONTENT_LENGTH = 100_000;

    // 用户端排序白名单（白名单外回落默认，防 order by 注入）
    private static final Map<String, String> USER_SORT_FIELDS = Map.of(
            "publishedAt", "published_at",
            "readCount", "read_count");
    private static final String DEFAULT_SORT_FIELD = "published_at";

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private KnowledgeIndexRepository knowledgeIndexRepository;

    // ==================== 管理端 ====================

    /**
     * 创建文章：接受客户端 UUID 为 id，创建即草稿（status=0）
     */
    public void createArticle(Long authorId, ArticleCreateDTO createDTO) {
        if (!UUID_PATTERN.matcher(createDTO.getId()).matches()) {
            throw new BusinessException("文章ID格式不合法");
        }
        requireCategory(createDTO.getCategoryId());
        String content = sanitizeContent(createDTO.getContent());

        Article article = Article.builder()
                .id(createDTO.getId())
                .title(createDTO.getTitle().trim())
                .categoryId(createDTO.getCategoryId())
                .summary(createDTO.getSummary())
                .content(content)
                .coverImage(createDTO.getCoverImage())
                .tags(normalizeTags(createDTO.getTags()))
                .authorId(authorId)
                .status(0)
                .readCount(0)
                .deleted(false)
                .build();
        try {
            articleMapper.insert(article);
        } catch (DataIntegrityViolationException e) {
            // 主键冲突（重复提交/重放）按业务错误返回
            throw new BusinessException("文章ID已存在，请勿重复提交");
        }
    }

    /**
     * 编辑文章：直接生效（已发布文章在线改），不改变状态与首发时间；
     * updated_at 仅在此处刷新（发布流转与阅读计数不刷新）
     */
    @Transactional
    public void updateArticle(String articleId, ArticleUpdateDTO updateDTO) {
        requireCategory(updateDTO.getCategoryId());
        String content = sanitizeContent(updateDTO.getContent());
        String title = updateDTO.getTitle().trim();
        String newCover = StrUtil.trimToNull(updateDTO.getCoverImage());
        Article existing = requireActiveArticle(lockArticle(articleId));

        Article update = Article.builder()
                .id(articleId)
                .title(title)
                .categoryId(updateDTO.getCategoryId())
                .summary(updateDTO.getSummary())
                .content(content)
                .tags(normalizeTags(updateDTO.getTags()))
                .updatedAt(LocalDateTime.now())
                .build();
        articleMapper.update(update, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .set(Article::getCoverImage, newCover));
        if (!Objects.equals(existing.getTitle(), title) || !Objects.equals(existing.getContent(), content)) {
            knowledgeIndexRepository.articleChanged(articleId, true);
        }

        // 封面被替换或移除时清理旧文件（封面路径按文章目录隔离，无共享引用；
        // 剩余孤儿场景仅为"上传后放弃保存"，无文件表故不做全量清理——见设计取舍）
        String oldCover = StrUtil.trimToNull(existing.getCoverImage());
        if (oldCover != null && !oldCover.equals(newCover)) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        fileStorageService.deleteFile(oldCover);
                    } catch (RuntimeException failure) {
                        log.warn("Article cover cleanup failed after commit, articleId={}, errorType={}",
                                articleId, failure.getClass().getSimpleName());
                    }
                }
            });
        }
    }

    /**
     * 发布/下线：WHERE 携带前置状态，防并发双击；
     * 发布时校验完整性，首发时间仅当前为 null 时写入（重新发布不覆盖）
     */
    @Transactional
    public void changeStatus(String articleId, Integer targetStatus) {
        if (targetStatus == null || (targetStatus != 1 && targetStatus != 2)) {
            throw new BusinessException("目标状态不合法");
        }
        Article article = requireActiveArticle(lockArticle(articleId));
        if (targetStatus == 1 && (StrUtil.isBlank(article.getTitle()) || StrUtil.isBlank(article.getContent()))) {
            throw new BusinessException("标题或正文为空，无法发布");
        }

        // 状态机：0/2 → 1（发布）；1 → 2（下线）
        // 不显式回写 updated_at：状态流转经 ON UPDATE CURRENT_TIMESTAMP 自然刷新（最后变更时间）
        List<Integer> allowedFrom = targetStatus == 1 ? List.of(0, 2) : List.of(1);
        LambdaUpdateWrapper<Article> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Article::getId, articleId)
                .eq(Article::getDeleted, false)
                .in(Article::getStatus, allowedFrom)
                .set(Article::getStatus, targetStatus);
        if (targetStatus == 1 && article.getPublishedAt() == null) {
            updateWrapper.set(Article::getPublishedAt, LocalDateTime.now());
        }
        int rows = articleMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new BusinessException("文章状态已变更，请刷新后重试");
        }
        if (targetStatus == 1) {
            knowledgeIndexRepository.articleChanged(articleId, false);
        } else {
            knowledgeIndexRepository.invalidate(articleId);
        }
    }

    /**
     * 软删除：退出生命周期；幂等（对齐情绪日记管理端删除先例）
     */
    @Transactional
    public void softDeleteArticle(String articleId) {
        Article article = lockArticle(articleId);
        if (article == null || Boolean.TRUE.equals(article.getDeleted())) {
            return;
        }
        LambdaUpdateWrapper<Article> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Article::getId, articleId)
                .eq(Article::getDeleted, false)
                .set(Article::getDeleted, true);
        if (articleMapper.update(null, updateWrapper) == 1) {
            knowledgeIndexRepository.invalidate(articleId);
        }
    }

    /**
     * 管理端分页：含已删除行（deleted 为标记），默认按最后编辑时间倒序
     */
    public Page<AdminArticleResponseDTO> pageAdminArticles(int currentPage, int size, String title, Long categoryId, Integer status) {
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(title), Article::getTitle, StrUtil.trim(title))
                .eq(categoryId != null, Article::getCategoryId, categoryId)
                .eq(status != null, Article::getStatus, status)
                .orderByDesc(Article::getUpdatedAt)
                .orderByDesc(Article::getCreatedAt);
        Page<Article> page = articleMapper.selectPage(new Page<>(currentPage, size), queryWrapper);

        Page<AdminArticleResponseDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<Article> records = page.getRecords();
        Map<Long, String> categoryNames = categoryNames(records.stream().map(Article::getCategoryId).toList());
        Map<Long, String> authorNames = authorNames(records.stream().map(Article::getAuthorId).toList());
        result.setRecords(records.stream().map(article -> {
            AdminArticleResponseDTO dto = new AdminArticleResponseDTO();
            dto.setId(article.getId());
            dto.setTitle(article.getTitle());
            dto.setCategoryId(article.getCategoryId());
            dto.setCategoryName(categoryNames.get(article.getCategoryId()));
            dto.setAuthorName(authorNames.get(article.getAuthorId()));
            dto.setReadCount(article.getReadCount());
            dto.setStatus(article.getStatus());
            dto.setPublishedAt(article.getPublishedAt());
            dto.setUpdatedAt(article.getUpdatedAt());
            dto.setDeleted(article.getDeleted());
            return dto;
        }).toList());
        return result;
    }

    /**
     * 管理端详情（编辑回显；已软删/不存在抛业务异常）
     */
    public ArticleDetailResponseDTO getAdminArticleDetail(String articleId) {
        Article article = requireActiveArticle(articleId);
        return toDetailDTO(article);
    }

    // ==================== 用户端（公开，见 SecurityConfig 放行） ====================

    /**
     * 用户端分页：强制已发布且未删除；排序字段白名单（防 order by 注入）
     */
    public Page<UserArticleResponseDTO> pageUserArticles(int currentPage, int size, String sortField, String sortDirection) {
        // 注意 Map.of 不可变 Map 不接受 null key（get(null) 抛 NPE），先判空再查白名单
        String column = StrUtil.isBlank(sortField)
                ? DEFAULT_SORT_FIELD
                : USER_SORT_FIELDS.getOrDefault(sortField, DEFAULT_SORT_FIELD);
        boolean asc = "asc".equalsIgnoreCase(sortDirection);

        QueryWrapper<Article> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", 1)
                .eq("deleted", 0)
                .orderBy(true, asc, column);
        Page<Article> page = articleMapper.selectPage(new Page<>(currentPage, size), queryWrapper);

        Page<UserArticleResponseDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<Article> records = page.getRecords();
        Map<Long, String> categoryNames = categoryNames(records.stream().map(Article::getCategoryId).toList());
        Map<Long, String> authorNames = authorNames(records.stream().map(Article::getAuthorId).toList());
        result.setRecords(records.stream().map(article -> {
            UserArticleResponseDTO dto = new UserArticleResponseDTO();
            dto.setId(article.getId());
            dto.setTitle(article.getTitle());
            dto.setSummary(article.getSummary());
            dto.setCoverImage(article.getCoverImage());
            dto.setCategoryName(categoryNames.get(article.getCategoryId()));
            dto.setAuthorName(authorNames.get(article.getAuthorId()));
            dto.setReadCount(article.getReadCount());
            dto.setPublishedAt(article.getPublishedAt());
            return dto;
        }).toList());
        return result;
    }

    /**
     * 用户端详情：仅已发布未删除可见（否则返回 null，调用方 404）；
     * 命中即阅读量原子 +1（管理端预览不走此方法，不去重）
     */
    public ArticleDetailResponseDTO getUserArticleDetail(String articleId) {
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Article::getId, articleId)
                .eq(Article::getStatus, 1)
                .eq(Article::getDeleted, false);
        Article article = articleMapper.selectOne(queryWrapper);
        if (article == null) {
            return null;
        }

        LambdaUpdateWrapper<Article> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Article::getId, articleId)
                .setSql("read_count = read_count + 1")
                // Keep the database's current value even when an edit commits after the detail was read.
                .setSql("updated_at = updated_at");
        articleMapper.update(null, updateWrapper);
        article.setReadCount(article.getReadCount() == null ? 1 : article.getReadCount() + 1);

        return toDetailDTO(article);
    }

    // ==================== 私有工具 ====================

    /**
     * 查询未软删除的文章；不存在/已软删除抛业务异常
     */
    private Article requireActiveArticle(String articleId) {
        return requireActiveArticle(articleMapper.selectById(articleId));
    }

    private Article lockArticle(String articleId) {
        return articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, articleId).last("FOR UPDATE"));
    }

    private Article requireActiveArticle(Article article) {
        if (article == null || Boolean.TRUE.equals(article.getDeleted())) {
            throw new BusinessException("文章不存在");
        }
        return article;
    }

    private void requireCategory(Long categoryId) {
        if (categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException("文章分类不存在");
        }
    }

    /**
     * 正文清洗 + 长度兜底
     */
    private String sanitizeContent(String content) {
        String sanitized = HtmlSanitizer.sanitize(content);
        if (sanitized.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException("文章内容过长");
        }
        return sanitized;
    }

    /**
     * 标签归一化：逗号拆分、去空、重拼（标签内不可能含分隔逗号，格式天然约束）
     */
    private String normalizeTags(String tags) {
        if (StrUtil.isBlank(tags)) {
            return null;
        }
        String joined = Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining(","));
        if (joined.isEmpty()) {
            return null;
        }
        if (joined.length() > 255) {
            throw new BusinessException("标签总长度不能超过255个字符");
        }
        return joined;
    }

    private ArticleDetailResponseDTO toDetailDTO(Article article) {
        Map<Long, String> categoryNames = categoryNames(List.of(article.getCategoryId()));
        Map<Long, String> authorNames = authorNames(List.of(article.getAuthorId()));
        ArticleDetailResponseDTO dto = new ArticleDetailResponseDTO();
        dto.setId(article.getId());
        dto.setTitle(article.getTitle());
        dto.setSummary(article.getSummary());
        dto.setContent(article.getContent());
        dto.setCoverImage(article.getCoverImage());
        dto.setCategoryId(article.getCategoryId());
        dto.setCategoryName(categoryNames.get(article.getCategoryId()));
        dto.setTags(article.getTags());
        dto.setTagArray(splitTags(article.getTags()));
        dto.setAuthorName(authorNames.get(article.getAuthorId()));
        dto.setReadCount(article.getReadCount());
        dto.setStatus(article.getStatus());
        dto.setPublishedAt(article.getPublishedAt());
        dto.setUpdatedAt(article.getUpdatedAt());
        return dto;
    }

    private List<String> splitTags(String tags) {
        if (StrUtil.isBlank(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private Map<Long, String> categoryNames(Collection<Long> categoryIds) {
        List<Long> ids = categoryIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
    }

    private Map<Long, String> authorNames(Collection<Long> authorIds) {
        List<Long> ids = authorIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
    }
}
