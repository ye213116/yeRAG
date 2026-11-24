-- Durable Work Plan execution checkpoints; no partial answer is stored here.
-- Existing installations may use a different collation from MySQL's charset default.
-- Match chat_session.id without changing the existing parent table or its data.
SET @yerag_plan_session_charset = (
    SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'id'
);
SET @yerag_plan_session_collation = (
    SELECT COLLATION_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'id'
);
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
    CONSTRAINT fk_agent_plan_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT ck_agent_plan_phase CHECK (phase IN ('RUNNING','SYNTHESIZING','PAUSED','COMPLETED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Work plan state, fenced attempts and step checkpoints';

SET @yerag_plan_session_fk = CONCAT(
    'ALTER TABLE agent_plan_run MODIFY conversation_id CHAR(36) CHARACTER SET ',
    @yerag_plan_session_charset, ' COLLATE ', @yerag_plan_session_collation,
    ' NOT NULL, ADD CONSTRAINT fk_agent_plan_session FOREIGN KEY (conversation_id) REFERENCES chat_session(id)'
);
PREPARE yerag_plan_session_fk FROM @yerag_plan_session_fk;
EXECUTE yerag_plan_session_fk;
DEALLOCATE PREPARE yerag_plan_session_fk;

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
