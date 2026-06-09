-- Task 5.2.a：先为组织级知识域提供稳定数据底座，
-- 后续统一接入服务会基于这些对象继续扩展资料生命周期、可信度和权限边界。
CREATE TABLE IF NOT EXISTS knowledge_domain (
    id BIGSERIAL PRIMARY KEY,
    domain_key VARCHAR(120) NOT NULL,
    domain_name VARCHAR(120) NOT NULL,
    description TEXT,
    domain_type VARCHAR(40) NOT NULL DEFAULT 'ORGANIZATION',
    owner_scope VARCHAR(40) NOT NULL DEFAULT 'ORGANIZATION',
    access_scope VARCHAR(40) NOT NULL DEFAULT 'TEAM_SHARED',
    default_lifecycle VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    default_trust_level VARCHAR(40) NOT NULL DEFAULT 'CURATED',
    allowed_source_categories TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_domain_domain_key
    ON knowledge_domain(domain_key);
CREATE INDEX IF NOT EXISTS idx_knowledge_domain_status
    ON knowledge_domain(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_domain_owner_scope
    ON knowledge_domain(owner_scope);

-- 连接器运行时调度仍然属于 Task 5.8，
-- 这里先只沉淀“定义过一次同步”这类可追溯记录，避免后续知识接入成为黑盒。
CREATE TABLE IF NOT EXISTS connector_sync_record (
    id BIGSERIAL PRIMARY KEY,
    knowledge_domain_id BIGINT,
    connector_key VARCHAR(120) NOT NULL,
    connector_type VARCHAR(60) NOT NULL,
    connector_label VARCHAR(120) NOT NULL,
    trigger_type VARCHAR(40) NOT NULL DEFAULT 'MANUAL',
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    source_category VARCHAR(50) NOT NULL DEFAULT 'AUTHENTICATED_SOURCES',
    source_urls TEXT NOT NULL DEFAULT '[]',
    synced_document_count INT NOT NULL DEFAULT 0,
    request_payload TEXT,
    result_summary TEXT,
    failure_reason TEXT,
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_connector_sync_record_domain_id
    ON connector_sync_record(knowledge_domain_id);
CREATE INDEX IF NOT EXISTS idx_connector_sync_record_connector_key
    ON connector_sync_record(connector_key);
CREATE INDEX IF NOT EXISTS idx_connector_sync_record_status
    ON connector_sync_record(sync_status);

-- Task 5.2.b 起，知识文档需要同时承接任务级知识与组织级资料接入，
-- 因此在既有 knowledge_document 上补齐知识域、生命周期和可信度等治理字段。
ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS knowledge_scope VARCHAR(40) NOT NULL DEFAULT 'TASK',
    ADD COLUMN IF NOT EXISTS knowledge_domain_id BIGINT,
    ADD COLUMN IF NOT EXISTS knowledge_domain_key VARCHAR(120),
    ADD COLUMN IF NOT EXISTS source_lifecycle VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS trust_level VARCHAR(40) NOT NULL DEFAULT 'CURATED',
    ADD COLUMN IF NOT EXISTS connector_key VARCHAR(120);

ALTER TABLE knowledge_document
    ALTER COLUMN task_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_document_domain_key
    ON knowledge_document(knowledge_domain_key);
