-- MySQL 8.0. Apply once, with ai.memory.enabled=false. Existing tables/data are retained.
CREATE TABLE chat_session (
 id CHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 last_activity_at DATETIME(6) NOT NULL,
 context_expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 closed_at DATETIME(6),
 revision BIGINT NOT NULL DEFAULT 0,
 active_turn_id BIGINT,
 CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES users(id),
 INDEX ix_chat_session_user (user_id, status, last_activity_at)
);
CREATE TABLE chat_turn (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 session_id CHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 user_turn_seq BIGINT,
 request_id VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 question MEDIUMTEXT,
 answer MEDIUMTEXT,
 status VARCHAR(20) NOT NULL,
 context_sync_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
 context_sync_attempts INT NOT NULL DEFAULT 0,
 context_next_attempt_at DATETIME(6),
 created_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6),
 content_expires_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_chat_turn_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_chat_turn_session FOREIGN KEY (session_id) REFERENCES chat_session(id),
 UNIQUE (user_id, request_id),
 UNIQUE (user_id, user_turn_seq),
 INDEX ix_chat_turn_session (session_id, user_turn_seq),
 INDEX ix_chat_turn_sync (context_sync_status, context_next_attempt_at)
);
CREATE TABLE memory_extraction_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 turn_id BIGINT NOT NULL UNIQUE,
 status VARCHAR(20) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL,
 lease_until DATETIME(6),
 lease_token CHAR(36),
 last_error_code VARCHAR(80),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_memory_task_turn FOREIGN KEY (turn_id) REFERENCES chat_turn(id),
 INDEX ix_memory_task_due (status, next_attempt_at),
 INDEX ix_memory_task_lease (status, lease_until)
);
CREATE TABLE memory_slot (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 memory_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 current_version_id BIGINT,
 revision BIGINT NOT NULL DEFAULT 0,
 last_applied_turn_seq BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_memory_slot_user FOREIGN KEY (user_id) REFERENCES users(id),
 UNIQUE (user_id, memory_key)
);
CREATE TABLE memory_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 slot_id BIGINT NOT NULL,
 version BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,
 attribute VARCHAR(80) NOT NULL,
 operation VARCHAR(10) NOT NULL,
 value_json JSON NOT NULL,
 status VARCHAR(20) NOT NULL,
 expires_at DATETIME(6),
 source_turn_id BIGINT NOT NULL,
 evidence TEXT,
 created_at DATETIME(6) NOT NULL,
 invalidated_at DATETIME(6),
 CONSTRAINT fk_memory_version_slot FOREIGN KEY (slot_id) REFERENCES memory_slot(id),
 CONSTRAINT fk_memory_version_turn FOREIGN KEY (source_turn_id) REFERENCES chat_turn(id),
 UNIQUE (slot_id, version)
);
-- current_version_id belongs to its slot: checked under the slot/user transaction lock.
-- No cascade deletion: retain identifiers and audit history when chat bodies expire.
