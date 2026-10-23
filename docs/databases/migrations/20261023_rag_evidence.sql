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
