-- Complete the soft-delete columns required by the current entities.
-- Safe to repeat: existing columns and rows are preserved.
USE mental_health_assistant;

SET @has_deleted_column = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'consultation_session' AND COLUMN_NAME = 'deleted'
);
SET @migration_sql = IF(@has_deleted_column = 0,
    'ALTER TABLE consultation_session ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Soft delete flag''',
    'SELECT ''consultation_session.deleted already exists'' AS migration_status');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_deleted_column = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_article' AND COLUMN_NAME = 'deleted'
);
SET @migration_sql = IF(@has_deleted_column = 0,
    'ALTER TABLE knowledge_article ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Soft delete flag''',
    'SELECT ''knowledge_article.deleted already exists'' AS migration_status');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_deleted_column = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'emotion_diary' AND COLUMN_NAME = 'deleted'
);
SET @migration_sql = IF(@has_deleted_column = 0,
    'ALTER TABLE emotion_diary ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Soft delete flag''',
    'SELECT ''emotion_diary.deleted already exists'' AS migration_status');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
