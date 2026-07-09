# 2026-07-09 Stage1 collector drain 修复真实 E2E 复跑记录

## 当前阶段

当前阶段：真实 E2E 复跑完成，问题已记录
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检验收：已完成（终态未通过）

## 本次运行

- 目的：
  1. 验证 collector hard deadline drain 修复是否能接住 Notion 的迟到证据。
  2. 记录修复后继续暴露的新问题。
- 后端：重启后运行在 `http://127.0.0.1:9093`
- 健康检查：`2026-07-09 17:30:17` 返回 `UP`
- 现场目录：`tmp/stage1-degraded-live-e2e-drainfix-20260709-172938`
- 输入：沿用上一轮 taskId=102 的双竞品 payload（Notion / Airtable）
- 新任务：`taskId=103`
- 新报告：`reportId=94`

## 终态结果

```text
taskStatus = STOPPED
errorMessage = 初审未通过且需要人工介入，请补充证据或调整策略后继续
completedNodes = 14/14
nodeGroups = SUCCESS=7; SUCCESS_DEGRADED=4; SKIPPED=3
canViewReport = true
deliveryStatus = BLOCKED
qualityScore = 14
qualityPassed = false
evidenceCount = 11
deliverySummary.blockerCount = 1
deliverySummary.evidenceGapCount = 3
```

被跳过节点：

```text
rewrite_report = SKIPPED
citation_check_revision = SKIPPED
quality_check_final = SKIPPED
```

跳过原因来自初审：`requiresHumanIntervention=true` 且 `autoRewriteAllowed=false`，系统认为当前不是自动改写能修好的问题，需要先补证据或调整采集策略。

## 修复是否有效

有效。上一轮 taskId=102 的关键失败链是 Notion 三个 collector 全部 `sourceCount=0`，导致 `extract_schema` 只产出 Airtable。此次复跑已经改变：

```text
collect_sources_01_02 Notion DOCS
  status = SUCCESS_DEGRADED
  degradation = HARD_DEADLINE_REACHED
  selected = 4
  successCollected = 2
  totalCollected = 2
  startedAt = 17:31:13
  completedAt = 17:33:03
  duration = 110s

extract_schema
  status = SUCCESS
  totalCompetitors = 2
  successCount = 2
```

结论：`deadline drain grace` 接住了 Notion DOCS 在 hard deadline 附近返回的证据，Notion 不再整竞品掉出 extractor。

## Collector 节点明细

| 节点 | 状态 | 选中 | 采集 | 耗时 | 降级原因 |
| --- | --- | ---: | ---: | ---: | --- |
| collect_sources_01_01 Notion OFFICIAL | SUCCESS_DEGRADED | 1 | 0/0 | 90s | HARD_DEADLINE_REACHED |
| collect_sources_01_02 Notion DOCS | SUCCESS_DEGRADED | 4 | 2/2 | 110s | HARD_DEADLINE_REACHED |
| collect_sources_01_03 Notion REVIEW | SUCCESS_DEGRADED | 0 | 0/0 | 120s | HARD_DEADLINE_REACHED |
| collect_sources_02_01 Airtable OFFICIAL | SUCCESS_DEGRADED | 0 | 0/0 | 120s | HARD_DEADLINE_REACHED |
| collect_sources_02_02 Airtable DOCS | SUCCESS | 4 | 4/8 | 586s | SEARCH_TIMEOUT_BEFORE_SUPPLEMENT |
| collect_sources_02_03 Airtable REVIEW | SUCCESS | 5 | 5/10 | 335s | - |

## 新暴露问题

### 问题 1：hard deadline drain 只修复了证据交接，没有真正约束内部长尾

`collect_sources_02_02` 和 `collect_sources_02_03` 仍明显突破类型预算：

```text
collect_sources_02_02
  startedAt = 17:31:13
  completedAt = 17:40:59
  duration = 586s
  status = SUCCESS

collect_sources_02_03
  startedAt = 17:31:13
  completedAt = 17:36:48
  duration = 335s
  status = SUCCESS
```

现场日志显示 `02_02` 在 `pool-20` 中持续反复采集 Airtable support/help/guide 等页面，多个 Playwright timeout / retry 后又重新开始同一类 URL。外层 collector hard deadline 没有把这类内部 search/recovery/collection 循环真正截断。

判断：当前修复解决的是“future 已快完成但节点先关门导致证据丢失”；仍未解决“SearchExecutionCoordinator / PageCollector 内部循环不消费同一个 deadline token”的问题。

### 问题 2：超时长尾节点仍以 SUCCESS 收口，deadline 语义不一致

`02_02` 耗时 586s、`02_03` 耗时 335s，但最终都是 `SUCCESS`，没有保留 `HARD_DEADLINE_REACHED` 或 `SUCCESS_DEGRADED` 语义。

这会导致下游和 UI 看不出这些证据是通过超预算长尾拿到的，和 Notion DOCS 的 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED` 形成事实口径不一致。

### 问题 3：证据数量过线后，质量失败前移到“证据支撑度和报告结构”

本次 `evidenceCount=11`，sourceUrls 红线不再是直接失败点。但质量分只有 `14`，初审结果为：

```text
score = 14
passed = false
requiresHumanIntervention = true
autoRewriteAllowed = false
diagnosisCount = 8
ERROR = 4
WARNING = 4
```

质量诊断集中在：

```text
执行摘要
产品概览
优势与弱点
结论与战略建议
功能对比
市场定位
建议
短板与风险 / 定价策略 / 产品概览 / 市场定位 / 目标用户 / 优势判断
```

报告诊断同时带出以下事实：

```text
NOTION_OFFICIAL_AND_REVIEW_SOURCES_MISSING
AIRTABLE_OFFICIAL_SOURCE_MISSING
EVIDENCE_NOT_COVERING
PROMPT_CONTENT_TRUNCATED
SKIPPED_UNUSABLE_EVIDENCE
PROMPT_BUDGET_SKIPPED
COLLECTOR_QUORUM_DEGRADED
COLLECTOR_FAMILY_MISSING_OFFICIAL
COLLECTOR_FAMILY_MISSING_REVIEW
COLLECTOR_FAMILY_MISSING_PRICING
HARD_DEADLINE_REACHED
SEARCH_TIMEOUT_BEFORE_SUPPLEMENT
OPTIONAL_PRICING_NOT_READY
OPTIONAL_FIELD_DEFERRED
```

判断：这次失败不再是 Notion evidence payload 全空，而是“有证据，但来源家族和字段支撑不够，writer 仍产出过多不可验证判断”。

### 问题 4：blocker 统计口径仍需要核对

最终报告：

```text
deliverySummary.blockerCount = 1
reportDiagnosis.blockerCount = 1
```

但 `quality_check` 原始诊断里有 `4` 条 `ERROR`，且 `deliverySummary.recommendedAction` 文案中提到“已有 4 条阻断级诊断”。这说明 `ERROR`、`blockerCount`、`recommendedAction` 三者之间的映射仍不够清晰。

## 结论

本轮 collector drain 修复有效，已经把失败主因从“Notion 整竞品因迟到证据被丢弃”推进到“证据支撑度、来源家族覆盖和下游报告质量门”。

下一步优先级：

1. 给 `SearchExecutionCoordinator / CollectionExecutionCoordinator / PlaywrightPageCollector` 传递同一个 hard deadline token，禁止内部 retry/collection 循环越过外层 deadline。
2. 统一超预算证据的节点语义：只要越过 hard deadline，即使最后采到证据，也应保留 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`。
3. 复盘 writer/reviewer：当证据不足时，是否应该把不可验证结论降级为“待验证假设”，而不是写入正文后再由 reviewer BLOCKED。
4. 统一 `ERROR`、`blockerCount`、`recommendedAction` 的统计和展示口径。
