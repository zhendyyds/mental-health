-- Align the existing local schema with ConsultationSession.deleted.
-- Existing sessions remain visible; no rows are removed or rewritten.
USE mental_health_assistant;

SET @has_deleted_column = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'consultation_session'
      AND COLUMN_NAME = 'deleted'
);
SET @migration_sql = IF(
    @has_deleted_column = 0,
    'ALTER TABLE consultation_session ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Soft delete flag''',
    'SELECT ''deleted column already exists'' AS migration_status'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
