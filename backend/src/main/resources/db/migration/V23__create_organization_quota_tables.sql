-- Task 5.8.a：先把组织级治理的两个基础持久化对象落地，
-- 让配额策略与连接器运行时注册表后续接入时，不需要再临时拼装表结构。
CREATE TABLE IF NOT EXISTS organization_quota_snapshot (
    id BIGSERIAL PRIMARY KEY,
    organization_key VARCHAR(120) NOT NULL,
    quota_scope VARCHAR(40) NOT NULL DEFAULT 'TASK',
    quota_key VARCHAR(120) NOT NULL,
    limit_value INT NOT NULL DEFAULT 0,
    used_value INT NOT NULL DEFAULT 0,
    reserved_value INT NOT NULL DEFAULT 0,
    quota_unit VARCHAR(40) NOT NULL DEFAULT 'COUNT',
    snapshot_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_urls TEXT NOT NULL DEFAULT '[]',
    snapshot_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_org_quota_snapshot_org_key
    ON organization_quota_snapshot(organization_key);
CREATE INDEX IF NOT EXISTS idx_org_quota_snapshot_scope
    ON organization_quota_snapshot(quota_scope);
CREATE INDEX IF NOT EXISTS idx_org_quota_snapshot_status
    ON organization_quota_snapshot(snapshot_status);

-- 连接器运行时租约需要单独持久化，
-- 这样后续才能在组织级视角解释“谁占住了哪个连接器槽位、何时释放”。
CREATE TABLE IF NOT EXISTS connector_runtime_lease (
    id BIGSERIAL PRIMARY KEY,
    organization_key VARCHAR(120) NOT NULL,
    connector_key VARCHAR(120) NOT NULL,
    runtime_slot VARCHAR(60) NOT NULL DEFAULT 'DEFAULT',
    lease_owner VARCHAR(120) NOT NULL,
    lease_status VARCHAR(20) NOT NULL DEFAULT 'HELD',
    lease_token VARCHAR(160) NOT NULL,
    source_urls TEXT NOT NULL DEFAULT '[]',
    acquired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_connector_runtime_lease_token
    ON connector_runtime_lease(lease_token);
CREATE INDEX IF NOT EXISTS idx_connector_runtime_lease_org_key
    ON connector_runtime_lease(organization_key);
CREATE INDEX IF NOT EXISTS idx_connector_runtime_lease_connector_key
    ON connector_runtime_lease(connector_key);
CREATE INDEX IF NOT EXISTS idx_connector_runtime_lease_status
    ON connector_runtime_lease(lease_status);
