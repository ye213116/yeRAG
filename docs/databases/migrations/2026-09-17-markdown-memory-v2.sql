-- Apply after 2026-09-09-memory-v1.sql. Existing slot/version data is retained.
CREATE TABLE memory_document (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(20) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_memory_document_user FOREIGN KEY (user_id) REFERENCES users(id),
  UNIQUE KEY uk_memory_document_type (user_id, memory_type)
);
CREATE TABLE memory_document_section (
  id CHAR(36) PRIMARY KEY,
  document_id BIGINT NOT NULL,
  heading VARCHAR(120) NOT NULL,
  last_applied_turn_seq BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_memory_section_document FOREIGN KEY (document_id) REFERENCES memory_document(id),
  INDEX ix_memory_section_recall (document_id, deleted),
  INDEX ix_memory_section_turn (document_id, last_applied_turn_seq)
);
