ALTER TABLE ai_call_audit_record
    ADD COLUMN IF NOT EXISTS estimated_input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS estimated_cost DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS budget_decision VARCHAR(60),
    ADD COLUMN IF NOT EXISTS provider_error_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS degradation_count INTEGER;
