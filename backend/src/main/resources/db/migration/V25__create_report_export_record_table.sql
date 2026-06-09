-- V25: create report export record table
-- 说明：
-- 1. 把报告导出从一次性下载动作提升为正式导出记录，便于交付中心消费稳定对象。
-- 2. 当前阶段先显式建模 task_id、export_version 与 source_urls，为后续渲染器和审计摘要留扩展位。
-- 3. 实际导出文件内容、交付摘要收口和高级审计聚合将在后续 5.7.b / 5.7.c 继续补齐。

CREATE TABLE IF NOT EXISTS report_export_record (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    export_version INTEGER NOT NULL,
    export_format VARCHAR(30) NOT NULL DEFAULT 'MARKDOWN',
    export_status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
    export_summary TEXT,
    source_urls TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_export_task_version
    ON report_export_record(task_id, export_version);

CREATE INDEX IF NOT EXISTS idx_report_export_task_id
    ON report_export_record(task_id);

CREATE INDEX IF NOT EXISTS idx_report_export_created_at
    ON report_export_record(created_at);
