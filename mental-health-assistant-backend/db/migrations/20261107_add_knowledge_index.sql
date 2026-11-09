-- Append the durable knowledge-index state to the existing MySQL schema.
-- Select the database explicitly and pause writers before applying this DDL.
-- Existing articles keep their content and status and start with no ready index.
SET NAMES utf8mb4;

SET @knowledge_index_columns = (
    SELECT CONCAT_WS(', ',
        IF(COUNT(CASE WHEN COLUMN_NAME = 'content_revision' THEN 1 END) = 0,
            'ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 1', NULL),
        IF(COUNT(CASE WHEN COLUMN_NAME = 'index_config_version' THEN 1 END) = 0,
            'ADD COLUMN index_config_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', NULL),
        IF(COUNT(CASE WHEN COLUMN_NAME = 'index_generation' THEN 1 END) = 0,
            'ADD COLUMN index_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL', NULL),
        IF(COUNT(CASE WHEN COLUMN_NAME = 'active_index_revision' THEN 1 END) = 0,
            'ADD COLUMN active_index_revision BIGINT NULL', NULL),
        IF(COUNT(CASE WHEN COLUMN_NAME = 'active_index_config_version' THEN 1 END) = 0,
            'ADD COLUMN active_index_config_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', NULL),
        IF(COUNT(CASE WHEN COLUMN_NAME = 'active_index_generation' THEN 1 END) = 0,
            'ADD COLUMN active_index_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL', NULL),
        IF(COUNT(CASE WHEN COLUMN_NAME = 'index_ready' THEN 1 END) = 0,
            'ADD COLUMN index_ready BOOLEAN NOT NULL DEFAULT FALSE', NULL)
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_article'
);
SET @knowledge_index_ddl = IF(@knowledge_index_columns = '',
    'SELECT ''knowledge_article index columns already exist'' AS migration_status',
    CONCAT('ALTER TABLE knowledge_article ', @knowledge_index_columns));
PREPARE knowledge_index_migration FROM @knowledge_index_ddl;
EXECUTE knowledge_index_migration;
DEALLOCATE PREPARE knowledge_index_migration;

CREATE TABLE IF NOT EXISTS knowledge_index_config (
    index_config_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_provider VARCHAR(64) NOT NULL,
    embedding_endpoint VARCHAR(255) NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    dimension INT NOT NULL,
    collection_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    extractor_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunker_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_size INT NOT NULL,
    chunk_overlap INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (index_config_version),
    UNIQUE KEY uk_knowledge_index_collection (collection_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_index_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    article_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    index_config_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    retryable BOOLEAN NOT NULL DEFAULT TRUE,
    next_retry_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(100) NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_job_generation (article_id, revision, index_config_version, index_generation, operation),
    UNIQUE KEY uk_knowledge_generation_operation (index_generation, operation),
    KEY ix_knowledge_job_due (status, next_retry_at, id),
    KEY ix_knowledge_job_lease (status, lease_until, id),
    KEY ix_knowledge_job_article (article_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    chunk_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    article_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    index_config_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_index INT NOT NULL,
    section VARCHAR(255) NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vector_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (chunk_id),
    UNIQUE KEY uk_knowledge_chunk_generation (article_id, revision, index_config_version, index_generation, chunk_index),
    UNIQUE KEY uk_knowledge_chunk_vector (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
