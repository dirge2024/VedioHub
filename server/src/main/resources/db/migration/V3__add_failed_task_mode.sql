SET @failed_task_mode_column_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'failed_analysis_tasks' AND column_name = 'mode'
);
SET @failed_task_mode_column_sql = IF(
    @failed_task_mode_column_exists = 0,
    'ALTER TABLE failed_analysis_tasks ADD COLUMN mode VARCHAR(32) NOT NULL DEFAULT ''GENERAL'', ALGORITHM=INSTANT, LOCK=NONE',
    'SELECT 1'
);
PREPARE failed_task_mode_column_statement FROM @failed_task_mode_column_sql;
EXECUTE failed_task_mode_column_statement;
DEALLOCATE PREPARE failed_task_mode_column_statement;
