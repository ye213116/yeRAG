-- Stop the old backend before applying; this migration is applied once.
ALTER TABLE chat_session ADD COLUMN title VARCHAR(200) NULL,
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'WORK';
ALTER TABLE chat_turn ADD COLUMN failure_code VARCHAR(40) NULL;
UPDATE chat_session s SET title=(
    SELECT LEFT(TRIM(t.question),100) FROM chat_turn t
    WHERE t.session_id=s.id AND t.question IS NOT NULL ORDER BY t.id LIMIT 1
);
-- Preserve mixed histories; classify only sessions consisting entirely of Coding submissions.
UPDATE chat_session s SET kind='CODING'
WHERE EXISTS (SELECT 1 FROM coding_task c WHERE c.conversation_id=s.id)
AND NOT EXISTS (
    SELECT 1 FROM chat_turn t WHERE t.session_id=s.id
    AND NOT EXISTS (SELECT 1 FROM coding_task c WHERE c.owner_id=t.user_id AND c.request_id=t.request_id)
);
