-- Apply once to an existing MySQL 8 database before deploying report management.
-- New databases use docs/databases/ddl.sql instead. No conversation data is rewritten.
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
