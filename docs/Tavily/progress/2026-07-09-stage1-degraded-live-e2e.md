# 2026-07-09 阶段1降级契约 Live E2E 测试记录

## 当前阶段

当前阶段：[Live E2E 已执行，问题已记录]
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检验收：已完成

## 1. 测试目标

本次测试用于验证 Task18 的阶段1首报降级契约是否能在真实 Tavily / LLM 链路中跑通：

- 两竞品任务可以完成采集、抽取、分析、写作、质检主链路。
- `pricing` 缺失或采集降级时，不应把任务推入重试耗尽、人工介入或不可查看报告。
- 阶段1核心字段仍必须满足：`summary / positioning / targetUsers / coreFeatures`。
- `sourceUrls >= 5` 且归一化独立域名数 `>= 2` 仍是硬红线。
- 不打开 Gate 1 / Gate 2 多轮补采，不提高 Tavily 预算。

## 2. 测试输入

```json
{
  "taskName": "阶段1降级契约双竞品 E2E：Notion 与 Airtable",
  "subjectProduct": "企业级知识协作与低代码工作台",
  "competitorNames": ["Notion", "Airtable"],
  "competitorUrls": ["https://www.notion.so", "https://www.airtable.com"],
  "analysisDimensions": ["产品概述", "市场定位", "目标用户", "核心功能", "价格策略"],
  "sourceScope": ["官网", "产品文档", "定价页", "公开测评"],
  "reportLanguage": "中文",
  "reportTemplate": "阶段1首报"
}
```

选择该输入的原因：

- 这是 Task17 友好基线的双竞品变体，规避当前系统“单竞品无法最终跑通”的已知限制。
- Notion 与 Airtable 公开资料充足，适合先验证阶段1降级主链路，而不是继续追复杂开放平台样例。
- 维度收敛为 4 个核心字段 + 1 个 pricing 增强字段，降低 E2E 首次跑通压力。

## 3. 执行命令与接口

- 健康检查：`GET http://127.0.0.1:9093/actuator/health`
- 预览任务：`POST http://127.0.0.1:9093/api/task/preview`
- 创建任务：`POST http://127.0.0.1:9093/api/task/create`
- 执行任务：`POST http://127.0.0.1:9093/api/task/100/execute`
- 查询任务：`GET http://127.0.0.1:9093/api/task/100`
- 查询节点：`GET http://127.0.0.1:9093/api/task/100/nodes`
- 查询报告：`GET http://127.0.0.1:9093/api/report/100`

运行日志目录：

```text
tmp/stage1-degraded-live-e2e-20260709-125813
```

## 4. 最终结果

```json
{
  "taskId": 100,
  "taskStatus": "STOPPED",
  "canViewReport": true,
  "canViewDraftReport": true,
  "completedNodes": "16/16",
  "nodeGroups": "SUCCESS=8; SUCCESS_DEGRADED=5; SKIPPED=3",
  "collectorGroups": "SUCCESS=3; SUCCESS_DEGRADED=5",
  "deliveryStatus": "BLOCKED",
  "readyForDelivery": false,
  "qualityScore": 32,
  "qualityPassed": false,
  "blockerCount": 1,
  "evidenceGapCount": 1,
  "deliverySourceUrls": 13,
  "distinctDomains": 6,
  "writerState": "PARTIAL_SOURCE",
  "writerFlags": "WRITER_CITATION_GAP; OPTIONAL_CITATION_GAP"
}
```

本次 E2E 未通过 Task18 的完整验收线：

- 任务最终进入 `STOPPED`。
- 报告存在且可查看，但交付状态是 `BLOCKED`，不是 `DEGRADED_READY`。
- `sourceUrls` 红线满足：交付摘要有 13 条来源 URL，归一化后 6 个独立域名。
- collector 降级语义部分成立：8 个 collector 中 5 个为 `SUCCESS_DEGRADED`，没有进入 retry 或等待人工介入。
- 下游放行语义部分成立：extractor、analyzer、writer、citation、quality_check 都已执行；说明 collector 降级没有阻断下游。

## 5. 暴露问题

### 问题 1：任务最终仍进入 STOPPED

现象：

- `TaskResponse.status = STOPPED`
- `TaskResponse.errorMessage = 初审未通过且需要人工介入，请补充证据或调整策略后继续`
- `canViewReport = true`
- `canViewDraftReport = true`
- `waitingRetryNodeCount = 0`
- `waitingInterventionNodeCount = 0`

判断：

- 这不是 collector hard deadline 直接触发的失败。
- 主链路已经产出报告和可查看草稿，但任务级状态仍被 reviewer 的 `requiresHumanIntervention=true` 收口为 `STOPPED`。
- 这与 Task18 “阶段1降级不应产生 STOPPED / WAITING_INTERVENTION” 的验收线冲突。

### 问题 2：核心字段 `Airtable / 目标用户` 未覆盖，导致 BLOCKED

证据：

- Report `deliveryStatus = BLOCKED`
- `blockerCount = 1`
- `evidenceGapCount = 1`
- `qualityScore = 32`
- reviewer blocker 的 evidenceBasis 指向：`阻断字段=目标用户`
- extractor 中第二个竞品的 `targetUsers.status = EVIDENCE_NOT_COVERING`

判断：

- 本次失败的核心 blocker 不是 pricing。
- `pricing` 被识别为 optional warning，符合 Task18 方向。
- 真正导致无法 degraded-ready 的是核心字段 `targetUsers` 对 Airtable 没有形成可用证据链。

### 问题 3：未请求的增强章节仍进入 writer/reviewer 缺口链路

本次输入只请求了：

```text
产品概述 / 市场定位 / 目标用户 / 核心功能 / 价格策略
```

但 writer/reviewer 仍报告：

```text
missingCitationSections = pricing,targetUsers,strengths,weaknesses
```

并出现：

```text
OPTIONAL_STRENGTHS_ANALYSIS_DEFERRED
OPTIONAL_WEAKNESSES_ANALYSIS_DEFERRED
strengthsSummary
weaknessesSummary
```

判断：

- `strengths / weaknesses` 虽然被降级为 optional warning，没有直接占用核心 evidenceGapCount。
- 但它们在未被用户请求的情况下仍进入报告缺口展示，会增加 reviewer 诊断噪声。
- 说明 writer/reviewer 或 report diagnosis 仍可能存在标准版章节残留，至少展示语义还没有完全按用户输入维度收敛。

### 问题 4：Airtable 官方 collector 耗时偏长

现象：

- `collect_sources_02_01` 最终 `SUCCESS`。
- 日志显示该节点耗时约 `332869ms`。
- 在该节点完成前，下游 extractor 一直等待。

判断：

- 这不是本次最终失败的直接原因。
- 但它说明双竞品友好基线仍可能被单个官方 collector 拖慢。
- 需要后续确认 collector hard deadline 对“仍在持续采集但已满足 quorum”的节点是否应该更早收口，避免阶段1首报被非必要深采拖住。

### 问题 5：控制台输出存在中文编码噪声

现象：

- PowerShell 中 API 响应中文显示为乱码。
- Spring Boot / Maven 日志也存在中文乱码。

判断：

- 不影响接口 JSON 结构和本次判断。
- 后续如需人工复盘体验更好，可以单独收敛控制台编码或日志编码配置。

## 6. 通过项

- `POST /api/task/preview` 正常生成双竞品计划：2 个竞品、8 个 collector、16 个总节点。
- `POST /api/task/create` 成功创建任务，taskId 为 `100`。
- `POST /api/task/100/execute` 成功提交执行。
- Tavily live readiness 为可用。
- collector 降级没有触发 retry 或人工介入：
  - `SUCCESS=3`
  - `SUCCESS_DEGRADED=5`
  - `retryCount=0`
- 下游链路被放行并执行：
  - `extract_schema = SUCCESS`
  - `analyze_competitors = SUCCESS`
  - `write_report = SUCCESS`
  - `citation_check = SUCCESS`
  - `quality_check = SUCCESS`
- `sourceUrls` 红线满足：
  - 交付摘要来源数：13
  - 归一化独立域名数：6

## 7. 下一步建议

优先级从高到低：

1. 修正任务级状态收口：当报告已存在且可查看，但质量结果是阶段1可解释降级时，不应继续统一落到 `STOPPED`；需要区分 `BLOCKED`、`DEGRADED_READY`、`NEEDS_REVIEW_BUT_VIEWABLE` 的任务级状态语义。
2. 定位 `Airtable / targetUsers` 为什么没有形成证据链：检查 extractor 字段映射、Airtable 官方/公开资料证据是否被 prompt 截断或被 `EVIDENCE_NOT_COVERING` 误杀。
3. 收敛 writer/reviewer 的章节展示：未请求的 `strengths / weaknesses` 不应进入用户可见缺口主链路；如果保留，只能作为 optional audit，并避免放大 reviewer 诊断噪声。
4. 评估 collector quorum 后是否允许更早进入下游，避免单个官方节点深采 5 分钟以上拖慢阶段1首报。

## 8. 第二轮复测：调整 sourceScope 后验证核心字段

### 8.1 复测目的

第一次失败后判断：主要阻断点更像上游搜索与采集没有给 `Airtable / 目标用户` 提供可用证据，而不是 pricing 降级契约失效。

因此第二轮只调整测试输入，不修改代码：

- 去掉 `定价页` sourceScope，避免采集预算被 pricing 抢走。
- 加入 `客户案例`，提升 `目标用户 / 市场定位` 的命中概率。
- 继续保留 `价格策略` analysisDimension，用于观察 pricing optional 降级。

### 8.2 复测输入

```json
{
  "taskName": "阶段1降级契约双竞品 E2E：Notion 与 Airtable 核心字段验证",
  "subjectProduct": "企业级知识协作与低代码工作台",
  "competitorNames": ["Notion", "Airtable"],
  "competitorUrls": ["https://www.notion.so", "https://www.airtable.com"],
  "analysisDimensions": ["产品概述", "市场定位", "目标用户", "核心功能", "价格策略"],
  "sourceScope": ["官网", "客户案例", "产品文档", "公开测评"],
  "reportLanguage": "中文",
  "reportTemplate": "阶段1首报"
}
```

预览结果：

```json
{
  "competitorCount": 2,
  "collectorCount": 6,
  "pipelineCount": 8,
  "nodeCount": 14
}
```

### 8.3 复测结果

```json
{
  "taskId": 101,
  "taskStatus": "STOPPED",
  "canViewReport": true,
  "canViewDraftReport": true,
  "completedNodes": "14/14",
  "nodeGroups": "SUCCESS=8; SUCCESS_DEGRADED=3; SKIPPED=3",
  "collectorGroups": "SUCCESS=3; SUCCESS_DEGRADED=3",
  "deliveryStatus": "BLOCKED",
  "readyForDelivery": false,
  "qualityScore": 29,
  "qualityPassed": false,
  "blockerCount": 2,
  "evidenceGapCount": 1,
  "deliverySourceUrls": 14,
  "distinctDomains": 10,
  "writerState": "PARTIAL_SOURCE",
  "writerFlags": "WRITER_CITATION_GAP; OPTIONAL_CITATION_GAP"
}
```

第二轮仍未通过完整验收：

- 任务最终仍是 `STOPPED`。
- 报告仍是 `BLOCKED`，不是 `DEGRADED_READY`。
- `sourceUrls` 红线满足：14 条交付来源、10 个归一化独立域名。
- collector 降级继续没有触发 retry / waiting intervention：3 个 `SUCCESS_DEGRADED`，3 个 `SUCCESS`。
- extractor / analyzer / writer / citation / reviewer 全部被放行并执行。

### 8.4 第二轮关键变化

核心字段覆盖明显改善：

```json
[
  {
    "summaryStatus": "TRACEABLE",
    "positioningStatus": "TRACEABLE",
    "targetUsersStatus": "TRACEABLE",
    "targetUsersHasValue": true,
    "coreFeaturesStatus": "TRACEABLE",
    "pricingStatus": "EMPTY"
  },
  {
    "summaryStatus": "TRACEABLE",
    "positioningStatus": "TRACEABLE",
    "targetUsersStatus": "TRACEABLE",
    "targetUsersHasValue": true,
    "coreFeaturesStatus": "TRACEABLE",
    "pricingStatus": "TRACEABLE"
  }
]
```

结论：

- 第一轮的 `Airtable / targetUsers` 核心字段缺口，在第二轮通过 sourceScope 调整已消失。
- 这支持“第一次失败主要是上游搜索与采集输入不友好”的判断。
- 但任务仍失败，说明后续还有 reviewer / citation / report delivery 层面的系统语义问题。

### 8.5 第二轮新增暴露问题

#### 问题 6：核心字段过线后，reviewer 仍因 structuredBlocks 判 BLOCKER

第二轮 reviewer 给出两个 blocker：

```text
SEARCH_QUALITY / missing_structured_evidence / 通用
SEARCH_QUALITY / missing_structured_evidence / 功能对比
```

关键证据：

```text
structuredBlocks=0
qualitySignals=[DIRECT_HTML_RUNTIME_FAILURE, LIGHTWEIGHT_RUNTIME_FAILURE, UPGRADED_TO_FULL_RENDER, MAIN_CONTENT_READY, NO_STRUCTURED_BLOCKS, FULL_RENDER_READY, FIELD_CONTEXT_FALLBACK_FROM_NODE_CONFIG, FIELD_RELEVANCE_PARTIAL]
```

以及：

```text
structuredBlocks=0
qualitySignals=[DIRECT_HTML_HTTP_STATUS_ERROR, LIGHTWEIGHT_RUNTIME_FAILURE, UPGRADED_TO_FULL_RENDER, PUBLIC_SHELL_ONLY, LOGIN_GATE_PARTIAL, FULL_RENDER_READY, FIELD_CONTEXT_FALLBACK_FROM_NODE_CONFIG, FIELD_RELEVANCE_WEAK, EVIDENCE_REPAIR_REQUIRED, HIGH_TRUST_LOW_USABILITY, SCORE_CONTRADICTION_DETECTED, REPAIR_QUERY_PROPOSED]
```

判断：

- extractor 认为核心字段 `TRACEABLE`。
- reviewer 又从 structuredBlocks 可复用性角度判 blocker。
- 这已经不是简单的上游字段缺证，而是“字段级 coverage 与 reviewer 搜索质量判定口径不一致”。

#### 问题 7：citation coverage 仍把可查看报告压成不可交付

第二轮 citation：

```text
citationEvidenceState = PARTIAL_SOURCE
citationRiskSeverity = ERROR
citationCoverageRate = 0.5135
```

writer：

```text
missingCitationSections = pricing,strengths,weaknesses,conclusion,report_conclusion
citationGapSeverity = HIGH
```

判断：

- `pricing / strengths / weaknesses` 属于 optional 或未请求增强章节，不应把阶段1首报主链路压成不可交付。
- `conclusion / report_conclusion` 属于 writer 自动生成章节，应该优先要求保守改写或裁剪，而不是直接进入任务级 `STOPPED`。

#### 问题 8：未请求章节残留在第二轮仍存在

第二轮输入仍未请求：

```text
优势判断
短板与风险
```

但 writer / report diagnosis 仍出现：

```text
strengthsSummary
weaknessesSummary
OPTIONAL_STRENGTHS_ANALYSIS_DEFERRED
OPTIONAL_WEAKNESSES_ANALYSIS_DEFERRED
```

判断：

- 它们目前是 optional gap，没有直接成为核心 `evidenceGapCount` 的根因。
- 但它们持续进入 missingCitationSections 和诊断展示，说明标准版残留仍在污染阶段1首报体验。

#### 问题 9：任务级 STOPPED 仍未解决

第二轮依然：

```text
taskStatus = STOPPED
canViewReport = true
canViewDraftReport = true
waitingRetryNodeCount = 0
waitingInterventionNodeCount = 0
```

判断：

- 即使核心字段已经过线，任务级状态仍被 reviewer 的 `requiresHumanIntervention=true` 推到 `STOPPED`。
- 这说明 Task18 还需要专门收敛任务状态语义：`BLOCKED` 报告可查看、`NEEDS_REVIEW`、`STOPPED` 不能混用。

### 8.6 复测结论

第二轮复测把问题分层得更清楚：

1. 第一轮的 `targetUsers` 缺口确实偏上游输入问题，调整 sourceScope 后已解决。
2. pricing 降级仍然没有成为核心 blocker，optional 方向基本成立。
3. 当前真正阻断阶段1首报交付的是下游系统口径：
   - coverage 与 reviewer structuredBlocks 判定不一致；
   - citation coverage 对 optional / 自动结论章节过于严厉；
   - 未请求章节残留污染诊断；
   - 报告可查看但任务仍 `STOPPED`。

因此，下一步不应继续只换测试样例，而应针对 reviewer / writer / report delivery 做契约收敛。
