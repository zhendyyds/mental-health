-- Run inside the data import transaction, before any backend worker can start.
UPDATE knowledge_article SET index_ready = FALSE, index_config_version = NULL, index_generation = NULL,
    active_index_revision = NULL, active_index_config_version = NULL, active_index_generation = NULL
WHERE index_ready OR index_config_version IS NOT NULL OR index_generation IS NOT NULL
    OR active_index_revision IS NOT NULL OR active_index_config_version IS NOT NULL OR active_index_generation IS NOT NULL;

UPDATE knowledge_index_job SET status = 'SUPERSEDED', retryable = FALSE, lease_owner = NULL, lease_token = NULL,
    lease_until = NULL, error_code = 'RESTORE_REBUILD_REQUIRED', updated_at = UTC_TIMESTAMP(6),
    finished_at = COALESCE(finished_at, UTC_TIMESTAMP(6))
WHERE status IN ('PENDING', 'RUNNING', 'RETRY_WAIT') OR (status = 'FAILED' AND retryable);
