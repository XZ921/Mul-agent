ALTER TABLE evidence_source
    ADD COLUMN IF NOT EXISTS source_category VARCHAR(50);

UPDATE evidence_source
SET source_category = CASE
    WHEN UPPER(COALESCE(discovery_method, '')) LIKE '%UPLOAD%' THEN 'UPLOADED_DOCUMENTS'
    WHEN UPPER(COALESCE(discovery_method, '')) LIKE '%AUTH%'
        OR UPPER(COALESCE(discovery_method, '')) LIKE '%API%'
        OR UPPER(COALESCE(discovery_method, '')) LIKE '%CONNECTOR%' THEN 'AUTHENTICATED_SOURCES'
    WHEN UPPER(COALESCE(discovery_method, '')) LIKE '%CONFIG%'
        OR UPPER(COALESCE(discovery_method, '')) LIKE '%MANUAL%'
        OR UPPER(COALESCE(discovery_method, '')) LIKE '%USER%' THEN 'USER_PROVIDED'
    ELSE 'AI_DISCOVERED'
END
WHERE source_category IS NULL;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    competitor_name VARCHAR(100) NOT NULL,
    evidence_id VARCHAR(100) NOT NULL,
    document_key VARCHAR(160) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_category VARCHAR(50) NOT NULL,
    discovery_method VARCHAR(50),
    source_domain VARCHAR(255),
    title VARCHAR(500) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    snippet TEXT,
    cleaned_text TEXT,
    source_urls TEXT NOT NULL DEFAULT '[]',
    issue_flags TEXT NOT NULL DEFAULT '[]',
    document_version INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    failure_reason TEXT,
    collected_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_document_task_evidence
    ON knowledge_document(task_id, evidence_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_task_id
    ON knowledge_document(task_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_evidence_id
    ON knowledge_document(evidence_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_document_key
    ON knowledge_document(document_key);

CREATE TABLE IF NOT EXISTS retrieval_chunk (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    knowledge_document_id BIGINT NOT NULL,
    competitor_name VARCHAR(100) NOT NULL,
    evidence_id VARCHAR(100) NOT NULL,
    document_key VARCHAR(160) NOT NULL,
    chunk_key VARCHAR(200) NOT NULL,
    chunk_index INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    source_category VARCHAR(50) NOT NULL,
    document_version INT NOT NULL DEFAULT 1,
    content TEXT NOT NULL,
    snippet TEXT,
    source_urls TEXT NOT NULL DEFAULT '[]',
    issue_flags TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_retrieval_chunk_chunk_key
    ON retrieval_chunk(chunk_key);
CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_task_id
    ON retrieval_chunk(task_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_document_id
    ON retrieval_chunk(knowledge_document_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_document_key
    ON retrieval_chunk(document_key);

CREATE TABLE IF NOT EXISTS retrieval_index (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    knowledge_document_id BIGINT NOT NULL,
    competitor_name VARCHAR(100) NOT NULL,
    evidence_id VARCHAR(100) NOT NULL,
    document_key VARCHAR(160) NOT NULL,
    index_key VARCHAR(200) NOT NULL,
    index_scope VARCHAR(50) NOT NULL,
    source_category VARCHAR(50) NOT NULL,
    document_version INT NOT NULL DEFAULT 1,
    chunk_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    failure_reason TEXT,
    source_urls TEXT NOT NULL DEFAULT '[]',
    issue_flags TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_retrieval_index_index_key
    ON retrieval_index(index_key);
CREATE INDEX IF NOT EXISTS idx_retrieval_index_task_id
    ON retrieval_index(task_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_index_document_id
    ON retrieval_index(knowledge_document_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_index_index_key
    ON retrieval_index(index_key);
