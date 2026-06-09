CREATE TABLE IF NOT EXISTS conversation_session (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT,
    report_id BIGINT,
    page_type VARCHAR(40) NOT NULL,
    current_mode VARCHAR(40),
    session_summary TEXT,
    latest_user_message TEXT,
    latest_assistant_message TEXT,
    last_intent_decision_id BIGINT,
    active_form_draft_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversation_session_task_id
    ON conversation_session(task_id);

CREATE INDEX IF NOT EXISTS idx_conversation_session_report_id
    ON conversation_session(report_id);

CREATE TABLE IF NOT EXISTS intent_decision (
    id BIGSERIAL PRIMARY KEY,
    conversation_session_id BIGINT NOT NULL,
    task_id BIGINT,
    report_id BIGINT,
    page_type VARCHAR(40) NOT NULL,
    mode VARCHAR(40) NOT NULL,
    intent_type VARCHAR(80) NOT NULL,
    user_message TEXT NOT NULL,
    decision_reason TEXT,
    decision_payload TEXT,
    high_risk_action BOOLEAN NOT NULL DEFAULT FALSE,
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    source_urls TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_intent_decision_session_id
    ON intent_decision(conversation_session_id);

CREATE INDEX IF NOT EXISTS idx_intent_decision_task_id
    ON intent_decision(task_id);

CREATE TABLE IF NOT EXISTS form_draft (
    id BIGSERIAL PRIMARY KEY,
    conversation_session_id BIGINT NOT NULL,
    task_id BIGINT,
    draft_payload TEXT NOT NULL,
    change_summary TEXT,
    preview_summary TEXT,
    source_urls TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_form_draft_session_id
    ON form_draft(conversation_session_id);

CREATE INDEX IF NOT EXISTS idx_form_draft_task_id
    ON form_draft(task_id);
