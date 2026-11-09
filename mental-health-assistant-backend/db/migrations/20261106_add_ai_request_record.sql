-- Add persistent request idempotency to an existing schema.
-- Select the database explicitly. No business rows are updated or removed.
-- MySQL DDL commits implicitly: back up and stop writers before migration.
SET NAMES utf8mb4;

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

SET @has_request_column = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'consultation_message' AND COLUMN_NAME = 'request_id'
);
SET @migration_sql = IF(@has_request_column = 0,
    'ALTER TABLE consultation_message ADD COLUMN request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL',
    'SELECT ''consultation_message.request_id already exists'' AS migration_status');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- Persist the configured model name consistently with the new 100-character
-- contract. Widen only a shorter VARCHAR; retain its charset, collation,
-- nullability, literal default and comment. Other column types require review.
SET @migration_sql = (
    SELECT IF(DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH < 100,
        CONCAT('ALTER TABLE consultation_message MODIFY COLUMN ai_model VARCHAR(100) CHARACTER SET ',
            CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME,
            IF(IS_NULLABLE = 'YES', ' NULL', ' NOT NULL'),
            IF(COLUMN_DEFAULT IS NULL, IF(IS_NULLABLE = 'YES', ' DEFAULT NULL', ''),
                IF(EXTRA LIKE '%DEFAULT_GENERATED%', CONCAT(' DEFAULT (', COLUMN_DEFAULT, ')'),
                    CONCAT(' DEFAULT ', QUOTE(COLUMN_DEFAULT)))),
            ' COMMENT ', QUOTE(COLUMN_COMMENT)),
        'SELECT ''consultation_message.ai_model needs no widening'' AS migration_status')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'consultation_message' AND COLUMN_NAME = 'ai_model'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_request_index = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'consultation_message' AND INDEX_NAME = 'uk_message_request_sender'
);
SET @migration_sql = IF(@has_request_index = 0,
    'ALTER TABLE consultation_message ADD UNIQUE KEY uk_message_request_sender (request_id, sender_type)',
    'SELECT ''uk_message_request_sender already exists'' AS migration_status');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
