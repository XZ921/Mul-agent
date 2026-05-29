-- ==============================================================================
-- V1: 初始化数据库表结构
-- 说明：创建竞品分析 Agent 协作系统全部核心表
-- ==============================================================================

-- ----------------------------
-- 1. 分析任务表
-- ----------------------------
CREATE TABLE analysis_task (
    id BIGSERIAL PRIMARY KEY,
    task_name VARCHAR(200) NOT NULL,
    subject_product VARCHAR(200) NOT NULL,
    competitor_names TEXT NOT NULL,                  -- JSON 数组：["Notion AI","Glean"]
    competitor_urls TEXT,                             -- JSON 数组：["https://..."]
    analysis_dimensions TEXT,                         -- JSON 数组：["产品功能","定价"]
    source_scope TEXT,                                -- JSON 数组：["官网","文档"]
    report_language VARCHAR(20) DEFAULT '中文',
    report_template VARCHAR(50) DEFAULT '标准版',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    schema_id BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_task_status ON analysis_task(status);
CREATE INDEX idx_task_created_at ON analysis_task(created_at);

-- ----------------------------
-- 2. DAG 任务节点表
-- ----------------------------
CREATE TABLE task_node (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    node_name VARCHAR(50) NOT NULL,                  -- 节点标识名：collect_sources
    display_name VARCHAR(100) NOT NULL,              -- 节点显示名：采集公开信息
    agent_type VARCHAR(30) NOT NULL,                  -- Agent 类型：COLLECTOR
    depends_on TEXT,                                  -- JSON 数组：["collect_sources"]
    required BOOLEAN NOT NULL DEFAULT TRUE,           -- 是否为必须节点
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    input_data TEXT,                                  -- 节点输入 (JSON)
    output_data TEXT,                                 -- 节点输出 (JSON)
    error_message TEXT,
    execution_order INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_node_task_id ON task_node(task_id);
CREATE INDEX idx_node_status ON task_node(status);

-- ----------------------------
-- 3. Agent 执行日志表
-- ----------------------------
CREATE TABLE agent_execution_log (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    node_id BIGINT,
    agent_type VARCHAR(30) NOT NULL,                  -- Agent 类型
    agent_name VARCHAR(100) NOT NULL,                 -- Agent 实例名称
    input_data TEXT,                                  -- 输入数据 (JSON)
    output_data TEXT,                                 -- 输出数据 (JSON)
    status VARCHAR(20) NOT NULL,                      -- 执行状态
    model_name VARCHAR(100),                          -- LLM 模型名称
    prompt_used TEXT,                                 -- 使用的 Prompt
    duration_ms BIGINT,                               -- 执行耗时（毫秒）
    token_usage TEXT,                                 -- Token 用量 (JSON)
    error_message TEXT,
    trace_id VARCHAR(50),                             -- 追踪 ID
    reasoning_summary TEXT,                           -- 推理过程摘要
    needs_human_intervention BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_log_task_id ON agent_execution_log(task_id);
CREATE INDEX idx_log_agent_type ON agent_execution_log(agent_type);
CREATE INDEX idx_log_trace_id ON agent_execution_log(trace_id);
CREATE INDEX idx_log_created_at ON agent_execution_log(created_at);

-- ----------------------------
-- 4. 证据来源表
-- ----------------------------
CREATE TABLE evidence_source (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    competitor_name VARCHAR(100) NOT NULL,            -- 所属竞品名称
    evidence_id VARCHAR(20) NOT NULL,                 -- 任务内唯一编号：E001
    title VARCHAR(500) NOT NULL,                      -- 来源标题
    url VARCHAR(2048) NOT NULL,                       -- 来源 URL
    content_snippet TEXT,                             -- 原文引用片段
    full_content TEXT,                                -- 完整采集内容
    page_metadata TEXT,                               -- 页面元数据 (JSON)
    collected_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_evidence_task_id ON evidence_source(task_id);
CREATE INDEX idx_evidence_competitor ON evidence_source(competitor_name);
CREATE INDEX idx_evidence_evidence_id ON evidence_source(evidence_id);

-- ----------------------------
-- 5. 竞品知识 Schema 表
-- ----------------------------
CREATE TABLE competitor_knowledge (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    competitor_name VARCHAR(100) NOT NULL,
    official_url VARCHAR(2048),
    summary TEXT,                                     -- 产品简介
    positioning VARCHAR(500),                         -- 市场定位
    target_users TEXT,                                -- 目标用户 (JSON)
    core_features TEXT,                               -- 核心功能 (JSON)
    pricing TEXT,                                     -- 定价信息 (JSON)
    strengths TEXT,                                   -- 优势 (JSON)
    weaknesses TEXT,                                  -- 劣势 (JSON)
    sources TEXT,                                     -- 信息来源 (JSON)
    extracted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_task_id ON competitor_knowledge(task_id);
CREATE INDEX idx_knowledge_competitor ON competitor_knowledge(competitor_name);

-- ----------------------------
-- 6. 分析报告表
-- ----------------------------
CREATE TABLE report (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    title VARCHAR(300) NOT NULL,
    content TEXT NOT NULL,                            -- Markdown 格式正文
    summary TEXT,                                     -- 报告摘要
    quality_score INT,                                -- 质检评分 (0-100)
    quality_passed BOOLEAN DEFAULT FALSE,
    quality_issues TEXT,                              -- 质检问题列表 (JSON)
    evidence_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_report_task_id ON report(task_id);

-- ----------------------------
-- 7. 分析模板表
-- ----------------------------
CREATE TABLE analysis_schema (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    dimensions TEXT,                                  -- 维度定义 (JSON)
    is_preset BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_schema_name ON analysis_schema(name);
