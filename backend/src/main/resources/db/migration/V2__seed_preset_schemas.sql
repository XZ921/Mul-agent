-- ==============================================================================
-- V2: 预置分析模板数据
-- 说明：第一版预置 3 个分析模板，开箱即用
-- ==============================================================================

-- 模板 1：功能对比分析
INSERT INTO analysis_schema (name, description, dimensions, is_preset)
VALUES (
    '功能对比分析',
    '对竞品进行全维度功能对比分析，包括产品功能、技术能力、用户体验和差异化特性，适合产品经理选型参考',
    '[
        {"name": "产品功能", "description": "核心功能模块完整度和差异化对比", "weight": 0.25},
        {"name": "技术能力", "description": "技术架构、AI 能力、集成能力", "weight": 0.20},
        {"name": "用户体验", "description": "界面设计、交互流程、上手难度", "weight": 0.15},
        {"name": "差异化特性", "description": "独特的卖点和区别于竞品的能力", "weight": 0.15},
        {"name": "目标用户", "description": "主要服务的用户群体和使用场景", "weight": 0.10},
        {"name": "价格策略", "description": "定价模式、免费额度、性价比", "weight": 0.15}
    ]',
    TRUE
);

-- 模板 2：定价策略分析
INSERT INTO analysis_schema (name, description, dimensions, is_preset)
VALUES (
    '定价策略分析',
    '聚焦竞品定价模式、套餐结构和价格竞争力，适合商业模式研究和定价决策参考',
    '[
        {"name": "定价模式", "description": "免费增值/订阅/按量付费等模式", "weight": 0.20},
        {"name": "套餐结构", "description": "各版本功能差异和定价梯度", "weight": 0.20},
        {"name": "免费额度", "description": "免费版/试用版的能力边界", "weight": 0.15},
        {"name": "性价比", "description": "同等价位下的功能对比", "weight": 0.20},
        {"name": "商业模式", "description": "盈利方式、收入来源、增长策略", "weight": 0.15},
        {"name": "目标付费群体", "description": "各套餐对应的目标用户画像", "weight": 0.10}
    ]',
    TRUE
);

-- 模板 3：SWOT 分析
INSERT INTO analysis_schema (name, description, dimensions, is_preset)
VALUES (
    'SWOT 分析',
    '系统评估每个竞品的优势、劣势、机会和威胁，适合战略规划和竞争策略制定',
    '[
        {"name": "优势 (Strengths)", "description": "产品、技术、品牌、团队等内部优势", "weight": 0.25},
        {"name": "劣势 (Weaknesses)", "description": "产品短板、资源限制、市场声量等内部劣势", "weight": 0.25},
        {"name": "机会 (Opportunities)", "description": "市场趋势、技术变革、政策利好等外部机会", "weight": 0.25},
        {"name": "威胁 (Threats)", "description": "竞争压力、替代品、监管风险等外部威胁", "weight": 0.25}
    ]',
    TRUE
);
