package com.flj.rag;

import com.flj.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Short MySQL transactions for the index state machine. Article rows always lock before jobs. */
@Repository
public class KnowledgeIndexRepository {
    private static final String BUILD = "BUILD";
    private static final String CLEANUP = "CLEANUP";
    private static final Set<String> IN_PROGRESS = Set.of("PENDING", "RUNNING", "RETRY_WAIT");
    private static final int MAX_ATTEMPTS = 3;
    private static final int CLEANUP_GRACE_SECONDS = 30;
    private static final String DUE = """
            ((status IN ('PENDING','RETRY_WAIT') AND next_retry_at<=UTC_TIMESTAMP(6))
              OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)))
            """;
    private static final String LIVE_LEASE = """
            id=? AND status='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
              AND article_id=? AND revision=? AND index_config_version=? AND index_generation=? AND operation=?
            """;

    private static final RowMapper<IndexConfig> CONFIG_ROW = (rs, row) -> new IndexConfig(
            rs.getString("index_config_version"), rs.getString("embedding_provider"), rs.getString("embedding_endpoint"),
            rs.getString("embedding_model"), rs.getInt("dimension"), rs.getString("collection_name"),
            rs.getString("extractor_version"), rs.getString("chunker_version"), rs.getInt("chunk_size"), rs.getInt("chunk_overlap"));
    private static final RowMapper<IndexJob> JOB_ROW = (rs, row) -> new IndexJob(
            rs.getLong("id"), rs.getString("article_id"), rs.getLong("revision"), rs.getString("index_config_version"),
            rs.getString("index_generation"), rs.getString("operation"), rs.getString("status"), rs.getInt("attempt_count"),
            rs.getBoolean("retryable"), rs.getObject("next_retry_at", LocalDateTime.class), rs.getString("lease_owner"),
            rs.getString("lease_token"), rs.getObject("lease_until", LocalDateTime.class), rs.getString("error_code"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
            rs.getObject("finished_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String defaultConfigVersion;

    public KnowledgeIndexRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                    @Value("${app.rag.enabled:false}") boolean enabled,
                                    @Value("${app.rag.version:}") String defaultConfigVersion) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.defaultConfigVersion = defaultConfigVersion;
    }

    public IndexConfig registerConfig(IndexConfig config) {
        validateConfig(config);
        try {
            jdbc.update("""
                    INSERT INTO knowledge_index_config (index_config_version,embedding_provider,embedding_endpoint,
                        embedding_model,dimension,collection_name,extractor_version,chunker_version,chunk_size,chunk_overlap,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                    """, config.version(), config.embeddingProvider(), config.embeddingEndpoint(), config.embeddingModel(),
                    config.dimension(), config.collectionName(), config.extractorVersion(), config.chunkerVersion(),
                    config.chunkSize(), config.chunkOverlap());
            return config;
        } catch (DuplicateKeyException duplicate) {
            if (config.equals(findConfig(config.version()))) { return config; }
            throw new BusinessException("409", "索引配置版本或集合名已存在，请创建新版本");
        }
    }

    public IndexConfig findConfig(String version) {
        if (version == null || version.isBlank()) { return null; }
        return first(jdbc.query("SELECT * FROM knowledge_index_config WHERE index_config_version=?", CONFIG_ROW, version));
    }

    /** Joins the caller's article edit/publish transaction. Call after writing the article. */
    public void articleChanged(String articleId, boolean contentChanged) {
        transactions.executeWithoutResult(tx -> {
            ArticleState article = requireArticle(articleId);
            if (contentChanged) {
                jdbc.update("UPDATE knowledge_article SET content_revision=content_revision+1,index_ready=false WHERE id=?", articleId);
                article = requireArticle(articleId);
            }
            IndexConfig config = enabled && article.published() ? findConfig(defaultConfigVersion) : null;
            if (config == null) { invalidateLocked(article); }
            else { queueBuild(article, config.version(), generationJob(article.targetGeneration(), BUILD)); }
        });
    }

    /** Joins the caller's transaction; deleted/offline evidence is immediately invisible to readers. */
    public void invalidate(String articleId) {
        transactions.executeWithoutResult(tx -> invalidateLocked(requireArticle(articleId)));
    }

    public IndexJob reindex(String articleId, String configVersion) {
        if (!enabled) { throw new BusinessException("503", "知识索引功能暂未启用"); }
        String version = configVersion == null || configVersion.isBlank() ? defaultConfigVersion : configVersion;
        if (!Objects.equals(version, defaultConfigVersion)) { throw new BusinessException("409", "只能按当前索引配置重建"); }
        TargetSnapshot observed = first(jdbc.query("""
                SELECT a.index_generation,b.status FROM knowledge_article a
                LEFT JOIN knowledge_index_job b ON b.index_generation=a.index_generation AND b.operation='BUILD'
                WHERE a.id=?
                """, (rs, row) -> new TargetSnapshot(rs.getString(1), rs.getString(2)), articleId));
        if (observed == null) { throw new BusinessException("404", "文章不存在"); }
        return transactions.execute(tx -> {
            ArticleState article = requireArticle(articleId);
            if (!article.published()) { throw new BusinessException("409", "仅已发布且未删除的文章可重建索引"); }
            if (findConfig(version) == null) { throw new BusinessException("409", "索引配置尚未登记"); }
            IndexJob current = generationJob(article.targetGeneration(), BUILD);
            boolean matches = current != null && current.revision() == article.revision() && current.configVersion().equals(version);
            if (!Objects.equals(observed.generation(), article.targetGeneration())) {
                if (matches) { return current; }
                throw new BusinessException("409", "文章索引目标已变更，请刷新后重试");
            }
            if (matches && observed.status() != null && IN_PROGRESS.contains(observed.status())) { return current; }
            return queueBuild(article, version, current);
        });
    }

    public List<IndexJob> listJobs(String articleId, int limit) {
        int size = Math.max(1, Math.min(100, limit));
        if (articleId == null || articleId.isBlank()) {
            return jdbc.query("SELECT * FROM knowledge_index_job ORDER BY id DESC LIMIT ?", JOB_ROW, size);
        }
        return jdbc.query("SELECT * FROM knowledge_index_job WHERE article_id=? ORDER BY id DESC LIMIT ?", JOB_ROW, articleId, size);
    }

    public IndexJob retry(long jobId) {
        if (!enabled) { throw new BusinessException("503", "知识索引功能暂未启用"); }
        JobKey key = first(jdbc.query("SELECT id,article_id FROM knowledge_index_job WHERE id=?",
                (rs, row) -> new JobKey(rs.getLong(1), rs.getString(2)), jobId));
        if (key == null) { throw new BusinessException("404", "索引任务不存在"); }
        return transactions.execute(tx -> {
            ArticleState article = lockArticle(key.articleId());
            IndexJob job = jobById(jobId, true);
            if (job == null) { throw new BusinessException("404", "索引任务不存在"); }
            if (!"FAILED".equals(job.status()) || !job.retryable() || !canRun(article, job) || !currentBuildConfig(job)) {
                throw new BusinessException("409", "任务不可重试，请检查当前索引代次和任务状态");
            }
            jdbc.update("""
                    UPDATE knowledge_index_job SET status='PENDING',attempt_count=0,next_retry_at=UTC_TIMESTAMP(6),
                        lease_owner=NULL,lease_token=NULL,error_code=NULL,finished_at=NULL,updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND status='FAILED'
                    """, jobId);
            return jobById(jobId, false);
        });
    }

    /** Each candidate is checked again under locks; only a DB-due row can get a new token. */
    public ClaimedJob claim(String owner, int leaseSeconds) {
        if (!enabled) { return null; }
        if (owner == null || owner.isBlank() || owner.length() > 100) { throw new IllegalArgumentException("Invalid index worker owner"); }
        validateLeaseSeconds(leaseSeconds);
        List<JobKey> due = jdbc.query("SELECT id,article_id FROM knowledge_index_job WHERE " + DUE
                        + " ORDER BY next_retry_at,id LIMIT 32",
                (rs, row) -> new JobKey(rs.getLong(1), rs.getString(2)));
        for (JobKey key : due) {
            ClaimedJob claimed = transactions.execute(tx -> claimLocked(key, owner, leaseSeconds));
            if (claimed != null) { return claimed; }
        }
        return null;
    }

    public boolean renew(ClaimedJob claim, int leaseSeconds) {
        validateLeaseSeconds(leaseSeconds);
        return withLiveLease(claim, (article, job, tx) -> updateLease("""
                UPDATE knowledge_index_job SET lease_until=GREATEST(lease_until,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))),
                    updated_at=UTC_TIMESTAMP(6) WHERE
                """, job, leaseSeconds) == 1);
    }

    public boolean finishBuild(ClaimedJob claim, List<IndexChunk> chunks) {
        if (!BUILD.equals(claim.job().operation())) { return false; }
        validateChunks(claim.job(), chunks);
        return withLiveLease(claim, (article, job, tx) -> {
            // Commit authoritative text and the new pointer together; expired tokens roll this back.
            deleteChunks(job);
            jdbc.batchUpdate("""
                    INSERT INTO knowledge_chunk (chunk_id,article_id,revision,index_config_version,index_generation,
                        chunk_index,section,content,content_hash,vector_id) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, chunks.stream().map(chunk -> new Object[]{chunk.chunkId(), job.articleId(), job.revision(),
                    job.configVersion(), job.generation(), chunk.chunkIndex(), chunk.section(), chunk.content(),
                    chunk.contentHash(), chunk.vectorId()}).toList());
            if (!succeed(job)) { tx.setRollbackOnly(); return false; }
            jdbc.update("""
                    UPDATE knowledge_article SET active_index_revision=?,active_index_config_version=?,
                        active_index_generation=?,index_ready=true WHERE id=?
                    """, job.revision(), job.configVersion(), job.generation(), job.articleId());
            if (article.activeGeneration() != null && !article.activeGeneration().equals(job.generation())) {
                enqueueCleanup(generationJob(article.activeGeneration(), BUILD), false);
            }
            return true;
        });
    }

    public boolean finishCleanup(ClaimedJob claim) {
        if (!CLEANUP.equals(claim.job().operation())) { return false; }
        return withLiveLease(claim, (article, job, tx) -> {
            deleteChunks(job);
            if (!succeed(job)) { tx.setRollbackOnly(); return false; }
            return true;
        });
    }

    public boolean fail(ClaimedJob claim, String errorCode, boolean retryable) {
        String safeCode = errorCode != null && errorCode.matches("[A-Z0-9_]{1,64}") ? errorCode : "INDEX_OPERATION_FAILED";
        return withLiveLease(claim, (article, job, tx) -> {
            boolean waiting = retryable && job.attemptCount() < MAX_ATTEMPTS;
            int delay = job.attemptCount() == 1 ? 5 : 20;
            return updateLease("""
                    UPDATE knowledge_index_job SET status=?,retryable=?,error_code=?,lease_owner=NULL,lease_token=NULL,
                        next_retry_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),
                        finished_at=IF(?,NULL,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6) WHERE
                    """, job, waiting ? "RETRY_WAIT" : "FAILED", retryable, safeCode, waiting ? delay : 0, waiting) == 1;
        });
    }

    /** A stale writer may finish after its first delete. Re-run cleanup only for an abandoned generation. */
    public void requeueAbandonedCleanup(ClaimedJob claim) {
        IndexJob old = claim.job();
        if (!BUILD.equals(old.operation())) { return; }
        transactions.executeWithoutResult(tx -> {
            ArticleState article = lockArticle(old.articleId());
            if (protectedGeneration(article, old.generation())) { return; }
            IndexJob build = generationJob(old.generation(), BUILD);
            if (build != null && sameGeneration(build, old)) { enqueueCleanup(build, true); }
        });
    }

    public List<ValidatedChunk> validateCandidates(List<IndexCandidate> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) { return List.of(); }
        if (candidates.size() > 100) { throw new IllegalArgumentException("Too many index candidates"); }
        List<String> ids = candidates.stream().filter(Objects::nonNull)
                .filter(candidate -> Objects.equals(defaultConfigVersion, candidate.configVersion())).map(IndexCandidate::chunkId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) { return List.of(); }
        List<Object> parameters = new ArrayList<>();
        parameters.add(defaultConfigVersion);
        parameters.addAll(ids);
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        List<ValidatedChunk> valid = jdbc.query("""
                SELECT c.article_id,c.revision,c.index_config_version,c.index_generation,c.chunk_id,c.vector_id,
                       c.section,c.content,c.content_hash,a.title
                FROM knowledge_chunk c JOIN knowledge_article a ON a.id=c.article_id
                WHERE c.index_config_version=? AND a.status=1 AND a.deleted=false AND a.index_ready=true
                  AND a.content_revision=c.revision AND a.active_index_revision=c.revision
                  AND a.active_index_config_version=c.index_config_version AND a.active_index_generation=c.index_generation
                  AND a.index_config_version=c.index_config_version AND a.index_generation=c.index_generation
                  AND c.chunk_id IN (
                """ + placeholders + ")", (rs, row) -> new ValidatedChunk(new IndexCandidate(rs.getString("article_id"),
                        rs.getLong("revision"), rs.getString("index_config_version"), rs.getString("index_generation"),
                        rs.getString("chunk_id"), rs.getString("vector_id")), rs.getString("title"), rs.getString("section"),
                        rs.getString("content"), rs.getString("content_hash")), parameters.toArray());
        Map<IndexCandidate, ValidatedChunk> byIdentity = valid.stream().collect(Collectors.toMap(ValidatedChunk::candidate, row -> row));
        return new LinkedHashSet<>(candidates).stream().map(byIdentity::get).filter(Objects::nonNull).toList();
    }

    private IndexJob queueBuild(ArticleState article, String version, IndexJob old) {
        if (old != null && old.revision() == article.revision() && old.configVersion().equals(version)
                && IN_PROGRESS.contains(old.status())) { return old; }

        String generation = UUID.randomUUID().toString();
        jdbc.update("UPDATE knowledge_article SET index_config_version=?,index_generation=?,index_ready=false WHERE id=?",
                version, generation, article.id());
        if (old != null) {
            supersede(old, "TARGET_REPLACED");
            if (!old.generation().equals(article.activeGeneration())) { enqueueCleanup(old, false); }
        }
        return insertBuild(article.id(), article.revision(), version, generation);
    }

    private void invalidateLocked(ArticleState article) {
        jdbc.update("""
                UPDATE knowledge_article SET index_config_version=NULL,index_generation=NULL,index_ready=false,
                    active_index_revision=NULL,active_index_config_version=NULL,active_index_generation=NULL WHERE id=?
                """, article.id());
        IndexJob target = generationJob(article.targetGeneration(), BUILD);
        if (target != null) {
            supersede(target, "TARGET_INVALIDATED");
            enqueueCleanup(target, false);
        }
        if (article.activeGeneration() != null && !article.activeGeneration().equals(article.targetGeneration())) {
            enqueueCleanup(generationJob(article.activeGeneration(), BUILD), false);
        }
    }

    private ClaimedJob claimLocked(JobKey key, String owner, int leaseSeconds) {
        ArticleState article = lockArticle(key.articleId());
        IndexJob job = first(jdbc.query("SELECT * FROM knowledge_index_job WHERE id=? AND " + DUE + " FOR UPDATE", JOB_ROW, key.id()));
        if (job == null) { return null; }
        if (!canRun(article, job)) {
            supersede(job, "GENERATION_NOT_RUNNABLE");
            if (BUILD.equals(job.operation()) && !protectedGeneration(article, job.generation())) { enqueueCleanup(job, false); }
            return null;
        }
        if (!currentBuildConfig(job)) {
            failBeforeClaim(job, "CONFIG_NOT_CURRENT", false);
            return null;
        }
        IndexConfig config = findConfig(job.configVersion());
        if (config == null || job.attemptCount() >= MAX_ATTEMPTS) {
            failBeforeClaim(job, config == null ? "CONFIG_NOT_FOUND" : "LEASE_ATTEMPTS_EXHAUSTED", config != null);
            return null;
        }
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("""
                UPDATE knowledge_index_job SET status='RUNNING',attempt_count=attempt_count+1,lease_owner=?,lease_token=?,
                    lease_until=GREATEST(COALESCE(lease_until,UTC_TIMESTAMP(6)),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))),
                    error_code=NULL,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND
                """ + DUE, owner, token, leaseSeconds, job.id());
        if (changed != 1) { return null; }
        return new ClaimedJob(jobById(job.id(), false), config, article == null ? null : article.title(),
                article == null ? null : article.content());
    }

    private boolean withLiveLease(ClaimedJob claim, LeaseAction action) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            IndexJob leased = claim.job();
            ArticleState article = lockArticle(leased.articleId());
            IndexJob job = first(jdbc.query("SELECT * FROM knowledge_index_job WHERE " + LIVE_LEASE + " FOR UPDATE",
                    JOB_ROW, leaseArguments(leased)));
            return job != null && canRun(article, job) && currentBuildConfig(job) && action.apply(article, job, tx);
        }));
    }

    private int updateLease(String sql, IndexJob job, Object... assignments) {
        Object[] lease = leaseArguments(job);
        Object[] args = Arrays.copyOf(assignments, assignments.length + lease.length);
        System.arraycopy(lease, 0, args, assignments.length, lease.length);
        return jdbc.update(sql + LIVE_LEASE, args);
    }

    private Object[] leaseArguments(IndexJob job) {
        return new Object[]{job.id(), job.leaseToken(), job.articleId(), job.revision(), job.configVersion(), job.generation(), job.operation()};
    }

    private boolean succeed(IndexJob job) {
        return updateLease("""
                UPDATE knowledge_index_job SET status='SUCCEEDED',retryable=false,error_code=NULL,lease_owner=NULL,
                    lease_token=NULL,finished_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE
                """, job) == 1;
    }

    private void supersede(IndexJob job, String code) {
        jdbc.update("""
                UPDATE knowledge_index_job SET status='SUPERSEDED',retryable=false,error_code=?,lease_owner=NULL,
                    lease_token=NULL,finished_at=COALESCE(finished_at,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND status<>'SUPERSEDED'
                """, code, job.id());
    }

    private void failBeforeClaim(IndexJob job, String errorCode, boolean retryable) {
        jdbc.update("""
                UPDATE knowledge_index_job SET status='FAILED',retryable=?,lease_owner=NULL,lease_token=NULL,
                    error_code=?,updated_at=UTC_TIMESTAMP(6),finished_at=UTC_TIMESTAMP(6) WHERE id=?
                """, retryable, errorCode, job.id());
    }

    private void enqueueCleanup(IndexJob build, boolean lateWrite) {
        if (build == null) { return; }
        String duplicateAction = lateWrite ? """
                status='PENDING',attempt_count=0,retryable=true,lease_owner=NULL,lease_token=NULL,error_code=NULL,
                finished_at=NULL,updated_at=UTC_TIMESTAMP(6),
                next_retry_at=GREATEST(knowledge_index_job.next_retry_at,planned.next_retry_at)
                """ : "id=knowledge_index_job.id";
        jdbc.update("""
                INSERT INTO knowledge_index_job (article_id,revision,index_config_version,index_generation,operation,
                    status,next_retry_at,created_at,updated_at)
                VALUES (?,?,?,?,'CLEANUP','PENDING',GREATEST(TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),
                    COALESCE(TIMESTAMPADD(SECOND,?,?),UTC_TIMESTAMP(6))),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) AS planned
                ON DUPLICATE KEY UPDATE
                """ + duplicateAction, build.articleId(), build.revision(), build.configVersion(), build.generation(),
                lateWrite ? CLEANUP_GRACE_SECONDS : 0, CLEANUP_GRACE_SECONDS, build.leaseUntil());
    }

    private IndexJob insertBuild(String articleId, long revision, String version, String generation) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_index_job (article_id,revision,index_config_version,index_generation,operation,
                        status,next_retry_at,created_at,updated_at)
                    VALUES (?,?,?,?,'BUILD','PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, articleId);
            statement.setLong(2, revision);
            statement.setString(3, version);
            statement.setString(4, generation);
            return statement;
        }, keys);
        return jobById(Objects.requireNonNull(keys.getKey()).longValue(), false);
    }

    private void deleteChunks(IndexJob job) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE article_id=? AND revision=? AND index_config_version=? AND index_generation=?",
                job.articleId(), job.revision(), job.configVersion(), job.generation());
    }

    private IndexJob generationJob(String generation, String operation) {
        if (generation == null) { return null; }
        return first(jdbc.query("SELECT * FROM knowledge_index_job WHERE index_generation=? AND operation=? FOR UPDATE",
                JOB_ROW, generation, operation));
    }

    private IndexJob jobById(long id, boolean lock) {
        return first(jdbc.query("SELECT * FROM knowledge_index_job WHERE id=?" + (lock ? " FOR UPDATE" : ""), JOB_ROW, id));
    }

    private ArticleState requireArticle(String id) {
        ArticleState article = lockArticle(id);
        if (article == null) { throw new BusinessException("404", "文章不存在"); }
        return article;
    }

    private ArticleState lockArticle(String id) {
        return first(jdbc.query("""
                SELECT id,title,content,status,deleted,content_revision,index_config_version,index_generation,active_index_generation
                FROM knowledge_article WHERE id=? FOR UPDATE
                """, (rs, row) -> new ArticleState(rs.getString("id"), rs.getString("title"), rs.getString("content"),
                rs.getInt("status") == 1 && !rs.getBoolean("deleted"), rs.getLong("content_revision"),
                rs.getString("index_config_version"), rs.getString("index_generation"), rs.getString("active_index_generation")), id));
    }

    private static boolean canRun(ArticleState article, IndexJob job) {
        if (CLEANUP.equals(job.operation())) { return !protectedGeneration(article, job.generation()); }
        return BUILD.equals(job.operation()) && article != null && article.published() && article.revision() == job.revision()
                && job.configVersion().equals(article.targetVersion()) && job.generation().equals(article.targetGeneration());
    }

    private boolean currentBuildConfig(IndexJob job) {
        return !BUILD.equals(job.operation()) || Objects.equals(defaultConfigVersion, job.configVersion());
    }

    private static boolean protectedGeneration(ArticleState article, String generation) {
        return article != null && (generation.equals(article.targetGeneration()) || generation.equals(article.activeGeneration()));
    }

    private static boolean sameGeneration(IndexJob first, IndexJob second) {
        return first.articleId().equals(second.articleId()) && first.revision() == second.revision()
                && first.configVersion().equals(second.configVersion()) && first.generation().equals(second.generation());
    }

    private static void validateChunks(IndexJob job, List<IndexChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || chunks.size() > 512) { throw new IllegalArgumentException("Invalid index chunk count"); }
        for (int i = 0; i < chunks.size(); i++) {
            IndexChunk chunk = chunks.get(i);
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()
                    || !chunk.equals(IndexChunk.create(job, i, chunk.section(), chunk.content()))) {
                throw new IllegalArgumentException("Invalid deterministic index chunk");
            }
        }
    }

    private static void validateConfig(IndexConfig config) {
        if (config == null || config.version() == null || !config.version().matches("[A-Za-z0-9_.-]{1,64}")
                || !identifier(config.embeddingProvider(), 64)
                || !identifier(config.collectionName(), 128) || !identifier(config.extractorVersion(), 32)
                || !identifier(config.chunkerVersion(), 32) || config.embeddingModel() == null
                || !config.embeddingModel().matches("[A-Za-z0-9_./:-]{1,100}") || !safeEndpoint(config.embeddingEndpoint())
                || config.dimension() < 1 || config.dimension() > 8192 || config.chunkSize() < 1 || config.chunkSize() > 4000
                || config.chunkOverlap() < 0 || config.chunkOverlap() >= config.chunkSize()) {
            throw new BusinessException("400", "索引配置参数不合法");
        }
    }

    private static boolean identifier(String value, int length) {
        return value != null && value.length() <= length && value.matches("[A-Za-z0-9_-]+");
    }

    private static boolean safeEndpoint(String value) {
        if (value == null || value.length() > 255) { return false; }
        try {
            URI uri = URI.create(value);
            boolean localHttp = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            return uri.getHost() != null && ("https".equals(uri.getScheme()) || localHttp)
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static void validateLeaseSeconds(int seconds) {
        if (seconds < 1 || seconds > 3600) { throw new IllegalArgumentException("Invalid index lease duration"); }
    }

    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }

    private record ArticleState(String id, String title, String content, boolean published, long revision,
                                String targetVersion, String targetGeneration, String activeGeneration) { }
    private record JobKey(long id, String articleId) { }
    private record TargetSnapshot(String generation, String status) { }

    @FunctionalInterface
    private interface LeaseAction {
        boolean apply(ArticleState article, IndexJob job, TransactionStatus transaction);
    }
}
