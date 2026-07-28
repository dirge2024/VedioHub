SET @content_hash_column_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND column_name = 'content_hash'
);
SET @content_hash_column_sql = IF(
    @content_hash_column_exists = 0,
    'ALTER TABLE media_files ADD COLUMN content_hash VARCHAR(64) NULL, ALGORITHM=INSTANT, LOCK=NONE',
    'SELECT 1'
);
PREPARE content_hash_column_statement FROM @content_hash_column_sql;
EXECUTE content_hash_column_statement;
DEALLOCATE PREPARE content_hash_column_statement;

SET @content_hash_index_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND index_name = 'idx_media_content_hash'
);
SET @content_hash_index_sql = IF(
    @content_hash_index_exists = 0,
    'ALTER TABLE media_files ADD INDEX idx_media_content_hash(content_hash), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE content_hash_index_statement FROM @content_hash_index_sql;
EXECUTE content_hash_index_statement;
DEALLOCATE PREPARE content_hash_index_statement;
