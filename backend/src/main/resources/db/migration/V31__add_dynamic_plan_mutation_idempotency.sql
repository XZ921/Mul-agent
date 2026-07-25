-- Task 10 Day 2：动态计划必须能够按权威 decisionId 幂等重放。
ALTER TABLE task_plan
    ADD COLUMN IF NOT EXISTS decision_id VARCHAR(160);

ALTER TABLE task_plan
    ADD COLUMN IF NOT EXISTS mutation_id VARCHAR(180);

CREATE UNIQUE INDEX IF NOT EXISTS uk_task_plan_task_decision
    ON task_plan(task_id, decision_id)
    WHERE decision_id IS NOT NULL;
