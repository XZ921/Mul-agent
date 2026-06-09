-- ==============================================================================
-- V26: add task quota reservation state flag
-- 说明：
-- 1. 显式记录任务是否仍然持有 TASK_CONCURRENCY 预留位，补齐 reserve / release 闭环；
-- 2. 按当前语义回填：PENDING / RUNNING 任务视为仍占位，其余终态任务视为已释放；
-- 3. 同步把组织级 reserved_value 校准为当前仍占位的任务数量，修复历史遗留滞留占位。
-- ==============================================================================

ALTER TABLE analysis_task
    ADD COLUMN IF NOT EXISTS task_quota_reserved BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE analysis_task
SET task_quota_reserved = CASE
    WHEN status IN ('PENDING', 'RUNNING') THEN TRUE
    ELSE FALSE
END;

UPDATE organization_quota_snapshot
SET reserved_value = (
    SELECT COUNT(*)
    FROM analysis_task
    WHERE task_quota_reserved = TRUE
)
WHERE organization_key = 'default-organization'
  AND quota_scope = 'TASK'
  AND quota_key = 'TASK_CONCURRENCY'
  AND snapshot_status = 'ACTIVE';
