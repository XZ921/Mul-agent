-- ==============================================================================
-- V29: 优化预置分析模板的描述与维度定义
-- 说明：
--   1. 修正模板描述，统一为“名称 + 一句话说明 + 适用场景”结构
--   2. 维度增加 coverage 字段（REQUIRED / OPTIONAL），标记采集可得性
--   3. 修正权重分配，避免分析结论被当作采集维度
--   4. SWOT 模板明确 O/T 为人工补充项
-- ==============================================================================

-- 模板 1：全维度功能对比
-- 改动：
--   - 描述去口语化，统一结构
--   - 去掉“差异化特性”（是分析结论，非采集维度）
--   - “用户体验”降为 OPTIONAL（公开来源难以采集，需第三方测评）
--   - “价格策略”降为 OPTIONAL 概览（详细定价由模板 2 负责）
--   - “目标用户”权重提升，新增“市场定位”维度
UPDATE analysis_schema
SET description = '覆盖产品功能、技术能力、目标用户与市场定位的系统性对比，适用于产品选型与能力评估',
    dimensions = '[
        {"name": "产品功能", "description": "核心功能模块完整度对比", "weight": 0.25, "coverage": "REQUIRED"},
        {"name": "技术能力", "description": "技术架构、AI 能力、开放接口与集成能力", "weight": 0.25, "coverage": "REQUIRED"},
        {"name": "目标用户", "description": "主要服务用户群体与典型使用场景", "weight": 0.20, "coverage": "REQUIRED"},
        {"name": "市场定位", "description": "产品定位、品牌策略与差异化方向", "weight": 0.15, "coverage": "REQUIRED"},
        {"name": "用户体验", "description": "交互设计、上手门槛与用户反馈（需第三方测评来源）", "weight": 0.10, "coverage": "OPTIONAL"},
        {"name": "价格概览", "description": "定价模式与免费额度概述（详细分析请使用定价策略模板）", "weight": 0.05, "coverage": "OPTIONAL"}
    ]',
    updated_at = CURRENT_TIMESTAMP
WHERE name = '功能对比分析' AND is_preset = TRUE;

-- 模板 2：定价策略分析
-- 改动：
--   - 描述修复重复文案
--   - 去掉“性价比”（是跨产品分析结论，非单竞品采集维度）
--   - “免费额度”合并入“套餐结构”
--   - “商业模式”收窄为“盈利模式”（仅覆盖公开来源可确认的部分）
--   - 新增“价格竞争力”为 OPTIONAL（需跨产品交叉对比）
UPDATE analysis_schema
SET description = '对比定价模式、套餐结构与盈利方式，适用于商业模式研究与定价决策参考',
    dimensions = '[
        {"name": "定价模式", "description": "免费、订阅、按量付费等定价模型", "weight": 0.25, "coverage": "REQUIRED"},
        {"name": "套餐结构", "description": "各版本功能差异、定价梯度与免费额度边界", "weight": 0.30, "coverage": "REQUIRED"},
        {"name": "目标付费群体", "description": "各套餐对应的目标用户画像", "weight": 0.20, "coverage": "REQUIRED"},
        {"name": "盈利模式", "description": "可从公开信息确认的盈利方式与收入来源", "weight": 0.15, "coverage": "OPTIONAL"},
        {"name": "价格竞争力", "description": "同等价位下的功能对比（需跨产品交叉分析）", "weight": 0.10, "coverage": "OPTIONAL"}
    ]',
    updated_at = CURRENT_TIMESTAMP
WHERE name = '定价策略分析' AND is_preset = TRUE;

-- 模板 3：SWOT 分析
-- 改动：
--   - 描述明确 S/W 为系统自动采集，O/T 为人工补充框架
--   - S/W 权重提升（系统可采集），O/T 降权（需人工输入）
--   - O/T 标记为 OPTIONAL，避免系统强行填充空洞内容
UPDATE analysis_schema
SET description = '系统评估各竞品的优势与劣势（自动采集），提供机会与威胁分析框架（需人工补充），适用于战略规划与竞争策略制定',
    dimensions = '[
        {"name": "优势 (Strengths)", "description": "产品、技术、品牌等内部优势", "weight": 0.35, "coverage": "REQUIRED"},
        {"name": "劣势 (Weaknesses)", "description": "产品短板、资源限制、市场声量等内部劣势", "weight": 0.35, "coverage": "REQUIRED"},
        {"name": "机会 (Opportunities)", "description": "市场趋势、技术变革、政策利好等外部机会（需人工补充）", "weight": 0.15, "coverage": "OPTIONAL"},
        {"name": "威胁 (Threats)", "description": "竞争压力、替代品、监管风险等外部威胁（需人工补充）", "weight": 0.15, "coverage": "OPTIONAL"}
    ]',
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'SWOT 分析' AND is_preset = TRUE;
