-- yeRAG complete MySQL 8.0 schema
--
-- Usage:
--   1. Create and select an empty database encoded as utf8mb4.
--   2. Execute this file once.
--   3. Start the application only after the schema has been created.
--
-- This file is the single source of truth for the MySQL relational schema.
-- Redis, Elasticsearch and MinIO data structures are not created here.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Identity and organization
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'User primary key',
    username VARCHAR(255) NOT NULL COMMENT 'Unique login name',
    password VARCHAR(255) NOT NULL COMMENT 'Encoded password hash',
    role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT 'Application role',
    org_tags VARCHAR(255) NULL COMMENT 'Comma-separated organization tags',
    primary_org VARCHAR(255) NULL COMMENT 'Primary organization tag',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Creation time',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Application users';

CREATE TABLE organization_tags (
    tag_id VARCHAR(255) NOT NULL COMMENT 'Organization tag identifier',
    name VARCHAR(255) NOT NULL COMMENT 'Display name',
    description TEXT NULL COMMENT 'Tag description',
    parent_tag VARCHAR(255) NULL COMMENT 'Parent organization tag',
    created_by BIGINT NOT NULL COMMENT 'Creator user ID',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Creation time',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update time',
    PRIMARY KEY (tag_id),
    KEY ix_organization_tags_parent (parent_tag),
    KEY ix_organization_tags_creator (created_by),
    CONSTRAINT fk_organization_tags_parent
        FOREIGN KEY (parent_tag) REFERENCES organization_tags(tag_id) ON DELETE SET NULL,
    CONSTRAINT fk_organization_tags_creator
        FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hierarchical organization tags';

-- ---------------------------------------------------------------------------
-- File upload and document metadata
-- ---------------------------------------------------------------------------

-- Delete upload/vector/index records by (file_md5, user_id). The merged MD5 object is shared;
-- preserve it while another upload owner remains; remove upload-part metadata only for the last owner.
CREATE TABLE file_upload (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Upload record primary key',
    file_md5 VARCHAR(32) NOT NULL COMMENT 'MD5 of the complete file',
    file_name VARCHAR(255) NOT NULL COMMENT 'Original file name',
    total_size BIGINT NOT NULL COMMENT 'File size in bytes',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 uploading, 1 completed',
    user_id VARCHAR(64) NOT NULL COMMENT 'Uploader application identity',
    org_tag VARCHAR(255) NULL COMMENT 'Owning organization tag',
    is_public TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Whether the file is public',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Upload creation time',
    merged_at DATETIME(6) NULL COMMENT 'Chunk merge completion time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_upload_md5_user (file_md5, user_id),
    KEY ix_file_upload_user (user_id),
    KEY ix_file_upload_org (org_tag),
    KEY ix_file_upload_visibility (is_public)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='File upload records';

CREATE TABLE chunk_info (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Chunk metadata primary key',
    file_md5 VARCHAR(32) NOT NULL COMMENT 'MD5 of the complete file',
    chunk_index INT NOT NULL COMMENT 'Zero-based chunk order',
    chunk_md5 VARCHAR(32) NOT NULL COMMENT 'MD5 of this chunk',
    storage_path VARCHAR(255) NOT NULL COMMENT 'Chunk object path in MinIO',
    PRIMARY KEY (id),
    KEY ix_chunk_info_file_order (file_md5, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Temporary upload chunk metadata';

CREATE TABLE document_vectors (
    vector_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Document chunk primary key',
    file_md5 VARCHAR(32) NOT NULL COMMENT 'MD5 of the source file',
    chunk_id INT NOT NULL COMMENT 'Parsed text chunk order',
    text_content LONGTEXT NULL COMMENT 'Parsed chunk text',
    model_version VARCHAR(32) NULL COMMENT 'Embedding model version',
    user_id VARCHAR(64) NOT NULL COMMENT 'Uploader application identity',
    org_tag VARCHAR(50) NULL COMMENT 'Owning organization tag',
    is_public TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Whether the chunk is public',
    PRIMARY KEY (vector_id),
    KEY ix_document_vectors_file (file_md5),
    KEY ix_document_vectors_owner (user_id),
    KEY ix_document_vectors_access (org_tag, is_public)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Parsed document chunks and search metadata';

-- ---------------------------------------------------------------------------
-- Memory: sessions and durable turns
-- ---------------------------------------------------------------------------

CREATE TABLE chat_session (
    id CHAR(36) NOT NULL COMMENT 'Session UUID',
    user_id BIGINT NOT NULL COMMENT 'Session owner',
    title VARCHAR(200) NULL COMMENT 'First question title or owner-selected name',
    kind VARCHAR(16) NOT NULL DEFAULT 'WORK' COMMENT 'WORK or internal CODING task conversation',
    status VARCHAR(20) NOT NULL COMMENT 'OPEN, CLOSED or DELETED tombstone',
    last_activity_at DATETIME(6) NOT NULL COMMENT 'Last accepted activity',
    context_expires_at DATETIME(6) NULL COMMENT 'Retired expiration field; no longer controls retention',
    created_at DATETIME(6) NOT NULL COMMENT 'Creation time',
    closed_at DATETIME(6) NULL COMMENT 'Close time',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic context revision',
    -- Default application summary ceiling is 60,000 UTF-8 bytes, below MySQL TEXT's 65,535-byte limit.
    context_summary TEXT NULL COMMENT 'Durable rolling short-term summary',
    summary_covered_turn_seq BIGINT NOT NULL DEFAULT 0 COMMENT 'Last turn included in the summary',
    summary_revision BIGINT NOT NULL DEFAULT 0 COMMENT 'Summary compare-and-set version',
    active_turn_id BIGINT NULL COMMENT 'Currently running turn; guarded by user lock',
    PRIMARY KEY (id),
    KEY ix_chat_session_user (user_id, status, last_activity_at),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_chat_session_status CHECK (status IN ('OPEN', 'CLOSED', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Memory-aware chat sessions';

-- Administrator date-range history reads only completed turns for one user.
-- The API returns at most the latest 200 turns in chronological order.
-- Model context reads preceding ID pages until its independent history token budget is reached.
-- Only the omitted unsummarized prefix enters the next bounded summary batch.
CREATE TABLE chat_turn (
    -- Application input ceiling: 8 MiB; MEDIUMTEXT holds up to 16,777,215 bytes.
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Turn primary key',
    session_id CHAR(36) NOT NULL COMMENT 'Owning session UUID',
    user_id BIGINT NOT NULL COMMENT 'Turn owner',
    user_turn_seq BIGINT NULL COMMENT 'Per-user ordering barrier; set to the generated turn ID',
    request_id VARCHAR(80) NOT NULL COMMENT 'Client idempotency key',
    request_hash CHAR(64) NOT NULL COMMENT 'SHA-256 of the original question',
    question MEDIUMTEXT NULL COMMENT 'Original user question; retained until user deletion',
    answer MEDIUMTEXT NULL COMMENT 'Assistant answer; retained until user deletion',
    failure_code VARCHAR(40) NULL COMMENT 'Terminal failure reason, including SERVER_RESTARTED',
    status VARCHAR(20) NOT NULL COMMENT 'RUNNING, COMPLETED, CANCELLED or FAILED',
    context_sync_status VARCHAR(20) NOT NULL DEFAULT 'NONE'
        COMMENT 'NONE, PENDING, SYNCED or EXPIRED',
    context_sync_attempts INT NOT NULL DEFAULT 0 COMMENT 'Context synchronization attempts',
    context_next_attempt_at DATETIME(6) NULL COMMENT 'Next context synchronization time',
    created_at DATETIME(6) NOT NULL COMMENT 'Creation time',
    completed_at DATETIME(6) NULL COMMENT 'Completion time',
    content_expires_at DATETIME(6) NULL COMMENT 'Retired expiration field; raw turns persist until user deletion',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_turn_request (user_id, request_id),
    UNIQUE KEY uk_chat_turn_sequence (user_id, user_turn_seq),
    KEY ix_chat_turn_session (session_id, user_turn_seq),
    KEY ix_chat_turn_sync (context_sync_status, context_next_attempt_at),
    CONSTRAINT fk_chat_turn_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_chat_turn_session FOREIGN KEY (session_id) REFERENCES chat_session(id),
    CONSTRAINT ck_chat_turn_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_chat_turn_context_sync
        CHECK (context_sync_status IN ('NONE', 'PENDING', 'SYNCED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable chat turns and context synchronization state';

-- ---------------------------------------------------------------------------
-- Memory: extraction queue and Markdown documents
-- ---------------------------------------------------------------------------

CREATE TABLE memory_extraction_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Extraction task primary key',
    turn_id BIGINT NOT NULL COMMENT 'Source completed turn',
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, RUNNING, DONE, FAILED or EXPIRED',
    attempts INT NOT NULL DEFAULT 0 COMMENT 'Claim attempts',
    next_attempt_at DATETIME(6) NOT NULL COMMENT 'Earliest next claim time',
    lease_until DATETIME(6) NULL COMMENT 'Current worker lease deadline',
    lease_token CHAR(36) NULL COMMENT 'Current worker lease token',
    last_error_code VARCHAR(80) NULL COMMENT 'Last bounded error code',
    failure_stage VARCHAR(32) NULL COMMENT 'Candidate, integration or commit failure stage',
    created_at DATETIME(6) NOT NULL COMMENT 'Creation time',
    updated_at DATETIME(6) NOT NULL COMMENT 'Last update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_task_turn (turn_id),
    KEY ix_memory_task_due (status, next_attempt_at),
    KEY ix_memory_task_lease (status, lease_until),
    CONSTRAINT fk_memory_task_turn FOREIGN KEY (turn_id) REFERENCES chat_turn(id),
    CONSTRAINT ck_memory_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Asynchronous long-term-memory extraction tasks';

CREATE TABLE memory_document (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Memory document primary key',
    user_id BIGINT NOT NULL COMMENT 'Document owner',
    memory_type VARCHAR(20) NOT NULL COMMENT 'PROFILE or PREFERENCE',
    content MEDIUMTEXT NOT NULL COMMENT 'Current Markdown document body',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Monotonic document version',
    created_at DATETIME(6) NOT NULL COMMENT 'Creation time',
    updated_at DATETIME(6) NOT NULL COMMENT 'Last content update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_document_type (user_id, memory_type),
    CONSTRAINT fk_memory_document_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_memory_document_type CHECK (memory_type IN ('PROFILE', 'PREFERENCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Current profile and preference Markdown documents';

CREATE TABLE memory_document_section (
    id CHAR(36) NOT NULL COMMENT 'Stable section UUID',
    document_id BIGINT NOT NULL COMMENT 'Owning memory document',
    heading VARCHAR(120) NOT NULL COMMENT 'Markdown level-two heading',
    description VARCHAR(240) NOT NULL DEFAULT '用户记忆条目，读取正文后使用。' COMMENT 'Model-produced discovery description',
    section_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Monotonic section version',
    last_applied_turn_seq BIGINT NOT NULL DEFAULT 0 COMMENT 'Latest applied user turn barrier',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Logical deletion marker',
    created_at DATETIME(6) NOT NULL COMMENT 'Creation time',
    updated_at DATETIME(6) NOT NULL COMMENT 'Last update time',
    PRIMARY KEY (id),
    KEY ix_memory_section_recall (document_id, deleted),
    KEY ix_memory_section_turn (document_id, last_applied_turn_seq),
    CONSTRAINT fk_memory_section_document
        FOREIGN KEY (document_id) REFERENCES memory_document(id),
    CONSTRAINT ck_memory_section_deleted CHECK (deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stable metadata for Markdown memory sections';

CREATE TABLE memory_document_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    metadata_json MEDIUMTEXT NOT NULL COMMENT 'Section identities, descriptions, versions and ordering barriers',
    change_summary TEXT NOT NULL,
    patches_json MEDIUMTEXT NOT NULL COMMENT 'Decisions, reasons and verifiable source references',
    trigger_turn_id BIGINT NULL COMMENT 'No FK: audit survives deletion of a source conversation',
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_memory_document_version (document_id,version),
    CONSTRAINT fk_memory_version_document FOREIGN KEY (document_id) REFERENCES memory_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only Markdown memory versions and provenance';

-- Optional RAG evidence index (ai.rag.evidence-backend=minio).
-- Epoch milliseconds avoid timestamp/timezone drift. No evidence body or API credentials in SQL.
CREATE TABLE rag_run (
    run_id CHAR(36) NOT NULL PRIMARY KEY,
    owner VARCHAR(260) NOT NULL COMMENT 'name:username or id:database user ID from server',
    parent_run_id CHAR(36) NULL,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_status VARCHAR(32) NOT NULL,
    started_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    object_key VARCHAR(160) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    KEY ix_rag_owner_started(owner,started_at),
    KEY ix_rag_parent(parent_run_id),
    KEY ix_rag_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE rag_stage_run (
    run_id CHAR(36) NOT NULL,
    event_ordinal INT NOT NULL,
    stage VARCHAR(40) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    PRIMARY KEY(run_id,event_ordinal),
    CONSTRAINT fk_rag_stage_run FOREIGN KEY(run_id) REFERENCES rag_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE rag_artifact (
    run_id CHAR(36) NOT NULL,
    object_key VARCHAR(160) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    byte_count BIGINT NOT NULL,
    PRIMARY KEY(run_id,sha256),
    CONSTRAINT fk_rag_artifact_run FOREIGN KEY(run_id) REFERENCES rag_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Personal report deliverables (not rag_artifact trace records).
CREATE TABLE work_artifact (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    current_version INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_artifact_owner (id, owner_id),
    KEY ix_artifact_owner_updated (owner_id, updated_at, id),
    CONSTRAINT fk_artifact_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE work_artifact_version (
    artifact_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    version INT NOT NULL,
    title VARCHAR(160) NOT NULL,
    content LONGTEXT NOT NULL,
    sources_json LONGTEXT NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (artifact_id, version),
    UNIQUE KEY uk_artifact_save_request (owner_id, request_id),
    CONSTRAINT fk_artifact_version_owner FOREIGN KEY (artifact_id, owner_id) REFERENCES work_artifact(id, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Isolated cloud Coding workspaces and durable delegated tasks.
CREATE TABLE coding_workspace (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    repository_id VARCHAR(120) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    base_sha VARCHAR(40) NOT NULL,
    permissions_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    active_task_id VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    KEY ix_coding_workspace_owner(owner_id,created_at),
    CONSTRAINT fk_coding_workspace_owner FOREIGN KEY(owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coding_task (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    message LONGTEXT NOT NULL,
    source_mode VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    permissions_json LONGTEXT NOT NULL,
    history_json LONGTEXT NOT NULL,
    summary LONGTEXT NULL,
    result_json LONGTEXT NULL,
    run_id VARCHAR(36) NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    publish_state VARCHAR(24) NOT NULL,
    pr_url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL,
    -- Written and expired using the database clock; never compare with a bound JVM Timestamp.
    heartbeat_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_coding_task_request(owner_id,request_id),
    KEY ix_coding_task_queue(status,created_at),
    CONSTRAINT fk_coding_task_workspace FOREIGN KEY(workspace_id) REFERENCES coding_workspace(id),
    CONSTRAINT fk_coding_task_owner FOREIGN KEY(owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coding_task_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    data_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY ix_coding_task_event(task_id,id),
    CONSTRAINT fk_coding_task_event FOREIGN KEY(task_id) REFERENCES coding_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Durable Work Plan execution checkpoints; no partial answer is stored here.
CREATE TABLE agent_plan_run (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    turn_id BIGINT NOT NULL,
    source_mode VARCHAR(20) NOT NULL,
    phase VARCHAR(20) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 1,
    checkpoint_json MEDIUMTEXT NOT NULL,
    calls INT NOT NULL DEFAULT 0,
    memory_calls INT NOT NULL DEFAULT 0,
    artifact_calls INT NOT NULL DEFAULT 0,
    decisions INT NOT NULL DEFAULT 0,
    unsafe_effect BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(200) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_plan_request (owner_id, request_id),
    UNIQUE KEY uk_agent_plan_turn (turn_id),
    KEY ix_agent_plan_lease (phase, lease_until),
    CONSTRAINT fk_agent_plan_turn FOREIGN KEY (turn_id) REFERENCES chat_turn(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_plan_session FOREIGN KEY (conversation_id) REFERENCES chat_session(id),
    CONSTRAINT fk_agent_plan_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT ck_agent_plan_phase CHECK (phase IN ('RUNNING','SYNTHESIZING','PAUSED','COMPLETED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Work plan state, fenced attempts and step checkpoints';

CREATE TABLE agent_plan_step (
    run_id CHAR(36) NOT NULL,
    step_id INT NOT NULL,
    attempt INT NOT NULL,
    title VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result_json MEDIUMTEXT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (run_id, step_id, attempt),
    CONSTRAINT fk_agent_plan_step FOREIGN KEY (run_id) REFERENCES agent_plan_run(id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_plan_step_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED','CANCELLED','INTERRUPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Step attempt history; retries do not overwrite previous attempts';
