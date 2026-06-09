CREATE TABLE IF NOT EXISTS ai_call_audit_record (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT,
    node_name VARCHAR(100),
    trace_id VARCHAR(50),
    capability VARCHAR(30) NOT NULL,
    provider_key VARCHAR(50) NOT NULL,
    model_name VARCHAR(120),
    retry_count INTEGER,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    success BOOLEAN NOT NULL DEFAULT FALSE,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    summary TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_audit_task_id ON ai_call_audit_record (task_id);
CREATE INDEX IF NOT EXISTS idx_ai_audit_node_name ON ai_call_audit_record (node_name);
CREATE INDEX IF NOT EXISTS idx_ai_audit_trace_id ON ai_call_audit_record (trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_audit_created_at ON ai_call_audit_record (created_at);
