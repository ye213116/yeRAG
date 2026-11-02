-- MySQL 8; stop all older application/Worker instances before applying.
-- Apply AFTER 20261102_persistent_conversation_history.sql. Apply once, with a backup.
ALTER TABLE memory_document ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE memory_document_section
    ADD COLUMN description VARCHAR(240) NOT NULL DEFAULT '用户记忆条目，读取正文后使用。',
    ADD COLUMN section_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE memory_extraction_task ADD COLUMN failure_stage VARCHAR(32) NULL;
CREATE TABLE memory_document_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    metadata_json MEDIUMTEXT NOT NULL,
    change_summary TEXT NOT NULL,
    patches_json MEDIUMTEXT NOT NULL,
    trigger_turn_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_memory_document_version (document_id,version),
    CONSTRAINT fk_memory_version_document FOREIGN KEY (document_id) REFERENCES memory_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Discovery descriptions do not copy body facts or preferences into the default index.
UPDATE memory_document_section
    SET description=CONCAT(LEFT(heading,80),'的用户记忆条目；读取正文后使用。');
UPDATE memory_document SET version=1;
INSERT INTO memory_document_version(document_id,version,content,metadata_json,change_summary,patches_json,trigger_turn_id,created_at)
SELECT d.id,1,d.content,COALESCE((
    SELECT JSON_ARRAYAGG(JSON_OBJECT('id',s.id,'documentId',s.document_id,'heading',s.heading,
        'description',s.description,'sectionVersion',s.section_version,'lastAppliedTurnSeq',s.last_applied_turn_seq,
        'deleted',JSON_EXTRACT(IF(s.deleted=1,'true','false'),'$')))
    FROM memory_document_section s WHERE s.document_id=d.id
),JSON_ARRAY()),'MIGRATION: initial Markdown snapshot; original section IDs retained',JSON_ARRAY(),NULL,CURRENT_TIMESTAMP(6)
FROM memory_document d;
