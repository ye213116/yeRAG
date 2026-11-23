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
