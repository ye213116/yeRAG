-- Upgrade an existing yeRAG MySQL 8 database before starting the unified memory runtime.
-- Select the target database when invoking mysql. This script is safe to rerun.
-- The retired conversations table and its data are deliberately retained.

SET @schema_name = DATABASE();

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'chat_session'
       AND COLUMN_NAME = 'context_summary') = 0,
    'ALTER TABLE chat_session ADD COLUMN context_summary TEXT NULL COMMENT ''Durable rolling short-term summary'' AFTER revision',
    'SELECT ''context_summary already exists'' AS migration_status');
PREPARE migration_step FROM @ddl;
EXECUTE migration_step;
DEALLOCATE PREPARE migration_step;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'chat_session'
       AND COLUMN_NAME = 'summary_covered_turn_seq') = 0,
    'ALTER TABLE chat_session ADD COLUMN summary_covered_turn_seq BIGINT NOT NULL DEFAULT 0 COMMENT ''Last turn included in summary'' AFTER context_summary',
    'SELECT ''summary_covered_turn_seq already exists'' AS migration_status');
PREPARE migration_step FROM @ddl;
EXECUTE migration_step;
DEALLOCATE PREPARE migration_step;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'chat_session'
       AND COLUMN_NAME = 'summary_revision') = 0,
    'ALTER TABLE chat_session ADD COLUMN summary_revision BIGINT NOT NULL DEFAULT 0 COMMENT ''Summary compare-and-set version'' AFTER summary_covered_turn_seq',
    'SELECT ''summary_revision already exists'' AS migration_status');
PREPARE migration_step FROM @ddl;
EXECUTE migration_step;
DEALLOCATE PREPARE migration_step;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'chat_session'
  AND COLUMN_NAME IN ('context_summary', 'summary_covered_turn_seq', 'summary_revision')
ORDER BY ORDINAL_POSITION;
