-- MySQL 8. Stop ALL old application/worker instances before applying this migration.
-- Old workers would otherwise continue clearing expired content during a rolling deployment.
-- Requires the 20260927 durable memory migration. Back up existing data first.
-- Older installations may have created these tables without the named CHECK.
SET @yerag_drop_status_check = IF(EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE() AND table_name='chat_session'
      AND constraint_name='ck_chat_session_status' AND constraint_type='CHECK'
), 'ALTER TABLE chat_session DROP CHECK ck_chat_session_status', 'SELECT 1');
PREPARE yerag_status_stmt FROM @yerag_drop_status_check;
EXECUTE yerag_status_stmt;
DEALLOCATE PREPARE yerag_status_stmt;
ALTER TABLE chat_session
    ADD CONSTRAINT ck_chat_session_status CHECK (status IN ('OPEN','CLOSED','DELETED')),
    MODIFY context_expires_at DATETIME(6) NULL COMMENT 'Retired expiration field; not a retention deadline';
ALTER TABLE chat_turn
    MODIFY content_expires_at DATETIME(6) NULL COMMENT 'Retired expiration field; raw turns persist until user deletion';
UPDATE chat_session SET context_expires_at=NULL;
UPDATE chat_turn SET content_expires_at=NULL;
-- Never claim recovery of question/answer content already erased by an older worker.
