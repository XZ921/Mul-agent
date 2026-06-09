ALTER TABLE intent_decision
    ADD COLUMN IF NOT EXISTS risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW';

ALTER TABLE intent_decision
    ADD COLUMN IF NOT EXISTS impact_scope VARCHAR(80) NOT NULL DEFAULT 'NONE';

ALTER TABLE intent_decision
    ADD COLUMN IF NOT EXISTS confirmation_request_payload TEXT;

UPDATE intent_decision
SET risk_level = CASE
    WHEN high_risk_action OR requires_confirmation THEN 'HIGH'
    ELSE 'LOW'
END;

UPDATE intent_decision
SET impact_scope = CASE
    WHEN requires_confirmation THEN 'TASK_EXECUTION'
    WHEN high_risk_action THEN 'TASK_EVIDENCE_CHAIN'
    ELSE 'NONE'
END;
