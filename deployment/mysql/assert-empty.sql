-- Exact existence checks, not InnoDB's estimated TABLE_ROWS.
SET SESSION group_concat_max_len = 65535;
SELECT GROUP_CONCAT(CONCAT('EXISTS(SELECT 1 FROM `', REPLACE(TABLE_NAME, '`', '``'), '` LIMIT 1)')
                    ORDER BY TABLE_NAME SEPARATOR '+') INTO @restore_row_checks
FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';
SET @restore_check_sql = CONCAT('SELECT ', @restore_row_checks);
PREPARE restore_empty_check FROM @restore_check_sql;
EXECUTE restore_empty_check;
DEALLOCATE PREPARE restore_empty_check;
