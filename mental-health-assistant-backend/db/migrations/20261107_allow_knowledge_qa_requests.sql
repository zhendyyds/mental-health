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
