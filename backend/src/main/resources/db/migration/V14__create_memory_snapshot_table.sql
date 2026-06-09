CREATE TABLE IF NOT EXISTS memory_snapshot (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    plan_version_id BIGINT,
    branch_key VARCHAR(120),
    node_name VARCHAR(100) NOT NULL,
    snapshot_type VARCHAR(40) NOT NULL DEFAULT 'TASK_RAG',
    query_text TEXT,
    summary TEXT,
    gap_summary TEXT,
    source_urls TEXT NOT NULL DEFAULT '[]',
    issue_flags TEXT NOT NULL DEFAULT '[]',
    context_payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_memory_snapshot_task_id
    ON memory_snapshot(task_id);

CREATE INDEX IF NOT EXISTS idx_memory_snapshot_plan_version_id
    ON memory_snapshot(plan_version_id);

CREATE INDEX IF NOT EXISTS idx_memory_snapshot_node_name
    ON memory_snapshot(node_name);
