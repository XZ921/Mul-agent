# P2 Dev 真实冒烟测试记录

> 日期：2026-06-24  
> 分支：`master`  
> 启动方式：真实 Spring Boot HTTP 服务，端口 `9093`，默认 `dev` profile  
> 基础设施：PostgreSQL、Redis、RocketMQ、Playwright、模型与采集配置均走真实环境  
> 任务 ID：`56`  
> 测试目标：验证 `execute` 之后的真实异步编排、采集、抽取、分析、写作、质检与 replay 链路。

## 请求样例

```json
{
  "taskName": "P2 真实冒烟测试 - AI 知识库竞品分析",
  "subjectProduct": "企业级 RAG 知识库平台",
  "competitorNames": ["Notion AI"],
  "competitorUrls": ["https://www.notion.so/product/ai"],
  "analysisDimensions": ["产品功能"],
  "sourceScope": ["官网"],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

## 执行结果

| 检查点 | 结果 | 证据 |
| --- | --- | --- |
| 后端启动 | 通过 | `dev` profile 启动，PostgreSQL/Flyway ready，`9093` 返回 HTTP 200 |
| Preview | 通过 | `code=200`，`contractType=TASK_PLAN_PREVIEW_V1`，`collectorCount=1`，`pipelineCount=6` |
| Create | 通过 | `code=200`，创建 `taskId=56`，初始节点数 7 |
| Execute | 通过 | `POST /api/task/56/execute` 返回 `code=200`，`message=Task execution started` |
| RocketMQ 编排 | 通过 | 节点按 `collect -> extract -> analyze -> write -> quality_check` 推进，replay 出现 `TASK_EXECUTION_REQUESTED / NODE_READY / NODE_COMPLETED / ORCHESTRATION_DECISION_RECORDED` |
| Agent 日志 | 通过 | `GET /api/agent-log/task/56` 返回 5 条，`COLLECTOR / EXTRACTOR / ANALYZER / WRITER / REVIEWER` 均为 `SUCCESS` |
| Evidence | 通过但偏弱 | `GET /api/report/56/evidences` 返回 1 条官方来源：`https://notion.so/product/ai` |
| 最终任务状态 | 未通过交付 | 任务终态 `STOPPED`，错误信息：`初审未通过且需要人工介入，请补充证据或调整策略后继续` |
| Report API | 有产物但不可交付 | `GET /api/report/56` 返回报告数据，但任务详情 `canViewReport=false` |

最终节点状态：

| 节点 | 状态 | 备注 |
| --- | --- | --- |
| `collect_sources_01_01` | `SUCCESS` | 采集成功 1/1，但 `补源方式=TIMEOUT_FALLBACK`，`进度状态=DEGRADED`，`降级原因=SEARCH_TIMEOUT_BEFORE_SUPPLEMENT` |
| `extract_schema` | `SUCCESS` | 模型网关调用成功，输出含 `sourceUrls=["https://notion.so/product/ai"]` |
| `analyze_competitors` | `SUCCESS` | 模型网关调用成功，但主要结构化分析字段为 `null` |
| `write_report` | `SUCCESS` | 生成初版报告 |
| `quality_check` | `SUCCESS` | 评分 25，`passed=false`，`requiresHumanIntervention=true`，`autoRewriteAllowed=false` |
| `rewrite_report` | `SKIPPED` | `跳过修订：初审严重失败，需先人工补证据、调整搜索范围或重跑采集链路` |
| `quality_check_final` | `SKIPPED` | `Dependencies not satisfied: rewrite_report=SKIPPED` |

## 问题记录

### P2-DEV-001：真实执行链路跑通，但最小数据不足以通过交付质检

- 现象：任务最终进入 `STOPPED`，不是 `SUCCESS`。
- 证据：`quality_check` 输出 `score=25`，`passed=false`，`requiresHumanIntervention=true`。
- 判断：这是质量门禁有效拦截，不是编排链路崩溃；但说明当前最小输入不足以作为“成功交付”冒烟样例。
- 后续建议：下一轮成功交付 smoke 需要至少扩大到 `官网 + 产品文档 + 定价页`，或选择信息密度更高、页面结构更稳定的目标 URL。

### P2-DEV-002：采集链路发生降级，证据数量不足

- 现象：collector 输出 `补源方式=TIMEOUT_FALLBACK`、`进度状态=DEGRADED`、`降级原因=SEARCH_TIMEOUT_BEFORE_SUPPLEMENT`，最终 evidence 只有 1 条。
- 影响：后续 analyzer/writer 缺少足够证据，质量检查判定结论支撑度为严重缺口。
- 后续建议：排查搜索 provider 超时、Playwright 页面验证耗时和 Notion 页面正文抽取质量；真实交付 smoke 不宜只依赖单个官网入口。

### P2-DEV-003：Analyzer 成功但核心分析字段为空，Writer 仍继续生成报告

- 现象：`analyze_competitors` 状态为 `SUCCESS`，但输出中的 `overview / featureComparison / positioningComparison / pricingComparison / targetUserComparison / strengthsSummary / weaknessesSummary` 等字段为 `null`。
- 影响：Writer 依赖 RAG 上下文继续生成报告，容易把“分析不足”推迟到质检阶段才暴露。
- 后续建议：为 analyzer 输出增加质量门禁；当核心字段为空时应生成受控的 `AgentSuggestion / OrchestrationDecision`，优先补采或人工确认，而不是直接进入 writer。

### P2-DEV-004：初版报告出现明显幻觉与交付格式问题

- 现象：报告正文包含模型开场白“好的，作为一名专业的竞品分析报告撰写专家...”，并写入错误日期 `2024-05-24`。
- 影响：即使证据可追溯，这类日期和格式错误也会降低交付可信度。
- 后续建议：Writer prompt 注入明确当前日期 `2026-06-24`；报告输出前增加格式清洗和日期一致性校验；质检维度应显式捕捉“错误日期 / 元话术残留”。

### P2-DEV-005：任务详情与报告 API 的可见性语义不一致

- 现象：任务详情 `canViewReport=false`，但 `GET /api/report/56` 返回 `code=200` 且有报告数据。
- 影响：前端可能隐藏报告入口，但直接 API 又能取到未通过质检的初版报告。
- 后续建议：明确“草稿报告”和“可交付报告”两个状态；如果允许查看草稿，应在 TaskResponse 中暴露单独字段，例如 `canViewDraftReport`。

### P2-DEV-006：sourceUrls 在部分公开 DTO 中未贯穿

- 现象：Evidence、extract/analyze 输出内部有 `sourceUrls`，但 `GET /api/task/56/nodes` 中各节点 `sourceUrls` 计数为 0，`GET /api/report/56` 的 `reportSourceUrls` 为 `null`。
- 影响：独立页面或 API 消费方无法从公开 DTO 直接完成溯源。
- 后续建议：统一从 node output / evidence / report 引用中回填公开 DTO 的 `sourceUrls`，避免只在内部 JSON 中存在。

### P2-DEV-007：轮询中观察到任务状态短暂回退为 `PENDING`

- 现象：tick 10 时 `analyze_competitors` 已经 `RUNNING`，但任务详情状态短暂为 `PENDING`，后续恢复为 `RUNNING`。
- 影响：前端进度条可能出现轻微跳变。
- 后续建议：检查任务快照刷新时机，避免节点已运行时任务级状态仍被旧快照覆盖。

## 结论

这轮真实冒烟验证了 `P2-LIVE-001` 中的主要阻塞在 dev 环境下不存在：RocketMQ 编排消费链路真实可用，任务能从 execute 推进到质检。  
但当前最小输入只能证明链路可运行，不能证明可成功交付；本轮暴露的问题主要集中在证据充分性、Analyzer 空输出保护、Writer 防幻觉、报告可见性和 sourceUrls 公开 DTO 贯穿。

## 下一步建议

1. 优先处理 `P2-DEV-005 / P2-DEV-006`，因为它们是接口语义和无幻觉溯源问题，工程边界清晰。
2. 再处理 `P2-DEV-003 / P2-DEV-004`，让质量问题尽量在 analyzer/writer 阶段前移，而不是全部压到最终质检。
3. 之后跑第二轮成功交付 smoke：扩大来源范围到 `官网 + 产品文档 + 定价页`，并验证任务终态能达到 `SUCCESS`。

## 修复进展（2026-06-24）

- `P2-DEV-005` 已补充任务详情的草稿报告可见性语义：`TaskResponse.canViewReport` 仍只代表正式成功交付，新增 `TaskResponse.canViewDraftReport` 表示 Writer 已产出草稿但任务可能仍停在质检/人工介入状态。
- `P2-DEV-006` 已补充公开 DTO 的来源 URL 贯穿：`TaskNodeResponse.sourceUrls` 聚合节点运行输出和计划配置来源，`ReportResponse.sourceUrls` 聚合 evidence、diagnosis、delivery summary、evidence entry point 和 audit summary 来源。
- `P2-DEV-003` 已补充 Analyzer 核心结构化字段门禁：当 `featureComparison / positioningComparison / pricingComparison / targetUserComparison / strengthsSummary / weaknessesSummary` 均为空时，`analyze_competitors` 返回失败，阻断 Writer 凭空扩写。
- `P2-DEV-004` 已补充 Writer 报告卫生治理：prompt 注入当前日期，保存前清理模型开场白，并把报告日期/生成日期/撰写日期统一归一到当前日期。
- `P2-DEV-007` 已修复运行时快照状态回退：`RuntimeStateRefresher` 改为使用 `NodeExecutionRecoveryPolicy` 基于最新节点权威状态归约任务状态，避免任务主表短暂滞后时用 `PENDING` 覆盖已有 `RUNNING` 节点的快照/SSE。
- 验证命令：`mvn -pl backend "-Dtest=TaskNodeViewAssemblerTest,ReportServiceTest" test`，结果 `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`。
- 验证命令：`mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest,ReportWriterAgentTest" test`，结果 `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`。
- 验证命令：`mvn -pl backend "-Dtest=RuntimeStateRefresherTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest" test`，结果 `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。
- 验证命令：`mvn -pl backend clean "-Dtest=CompetitorAnalysisAgentTest,ReportWriterAgentTest,TaskNodeViewAssemblerTest,ReportServiceTest,TaskControllerTest,ReportControllerTest,NodeExecutionRecoveryPolicyTest,RuntimeStateRefresherTest" test`，结果 `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`。
