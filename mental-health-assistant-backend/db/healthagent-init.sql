-- HealthAgent MySQL initialization for a new, empty development database.
-- Create and select the target database, then execute this whole file in one session.
-- Creates the current ten-table schema. Contains no application rows or accounts.
-- This bundle follows the same sequence as the Compose empty-volume initialization.

-- Source: baseline/20261106_schema.sql
-- Empty-database baseline for the current application (MySQL 8.0+).
-- Select the target database explicitly before running. No USE, DROP, seed users,
-- or business data are included. Re-running preserves all existing rows.
-- IF NOT EXISTS is not an upgrade mechanism: use migrations for existing tables.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NULL,
    avatar VARCHAR(255) NULL,
    gender INT NULL,
    birthday DATE NULL,
    user_type INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS consultation_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_title VARCHAR(200) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_emotion_analysis LONGTEXT NULL,
    last_emotion_updated_at DATETIME(6) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_session_user_deleted_started (user_id, deleted, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS consultation_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    sender_type INT NOT NULL,
    message_type INT NOT NULL DEFAULT 1,
    content LONGTEXT NOT NULL,
    emotion_tag VARCHAR(50) NULL,
    ai_model VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    KEY ix_message_session_id (session_id, id),
    UNIQUE KEY uk_message_request_sender (request_id, sender_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_article (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    category_id BIGINT NULL,
    summary TEXT NULL,
    content LONGTEXT NOT NULL,
    cover_image VARCHAR(255) NULL,
    tags VARCHAR(255) NULL,
    author_id BIGINT NULL,
    status INT NOT NULL DEFAULT 0,
    read_count INT NOT NULL DEFAULT 0,
    published_at DATETIME(6) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_article_visible_published (deleted, status, published_at),
    KEY ix_article_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS emotion_diary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    diary_date DATE NOT NULL,
    mood_score INT NOT NULL,
    dominant_emotion VARCHAR(20) NOT NULL,
    emotion_triggers TEXT NULL,
    diary_content TEXT NULL,
    sleep_quality INT NULL,
    stress_level INT NULL,
    ai_emotion_analysis LONGTEXT NULL,
    ai_analysis_updated_at DATETIME(6) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_diary_user_deleted_date (user_id, deleted, diary_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_request_record (
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    scene VARCHAR(32) NOT NULL,
    client_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    user_message_id BIGINT NULL,
    ai_message_id BIGINT NULL,
    ai_model VARCHAR(100) NULL,
    dispatched TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(8) NULL,
    error_message VARCHAR(255) NULL,
    saved TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_request_user_scene_client (user_id, scene, client_request_id),
    KEY ix_request_status (status),
    KEY ix_request_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Source: migrations/20261107_add_knowledge_index.sql
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

-- Source: migrations/20261107_allow_knowledge_qa_requests.sql
-- Allow the request ledger to represent a question without a consultation.
-- Select the database explicitly and pause writers before applying this DDL.
-- Preserve the numeric type, default, comment and visibility of session_id.
SET NAMES utf8mb4;

SET @knowledge_qa_ddl = (
    SELECT CASE
        WHEN IS_NULLABLE = 'YES' THEN 'SELECT ''ai_request_record.session_id already nullable'' AS migration_status'
        WHEN DATA_TYPE <> 'bigint' OR GENERATION_EXPRESSION <> ''
            OR TRIM(REPLACE(REPLACE(EXTRA, 'DEFAULT_GENERATED', ''), 'INVISIBLE', '')) <> '' THEN NULL
        ELSE CONCAT('ALTER TABLE ai_request_record MODIFY COLUMN session_id ', COLUMN_TYPE, ' NULL',
            IF(COLUMN_DEFAULT IS NULL, '',
                IF(EXTRA LIKE '%DEFAULT_GENERATED%', CONCAT(' DEFAULT (', COLUMN_DEFAULT, ')'),
                    CONCAT(' DEFAULT ', QUOTE(COLUMN_DEFAULT)))),
            IF(EXTRA LIKE '%INVISIBLE%', ' INVISIBLE', ''),
            ' COMMENT ', QUOTE(COLUMN_COMMENT))
        END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_request_record' AND COLUMN_NAME = 'session_id'
);
-- An absent or unsupported legacy column leaves the statement NULL and fails
-- before changing any data. Compare SHOW CREATE TABLE before migrating again.
PREPARE knowledge_qa_migration FROM @knowledge_qa_ddl;
EXECUTE knowledge_qa_migration;
DEALLOCATE PREPARE knowledge_qa_migration;
