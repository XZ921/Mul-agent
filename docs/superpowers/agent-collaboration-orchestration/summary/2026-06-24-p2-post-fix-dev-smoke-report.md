# P2 修复后 Dev 真实冒烟记录

> 日期：2026-06-24  
> 分支：`master`  
> 启动方式：真实 Spring Boot HTTP 服务，端口 `9093`，默认 `dev` profile  
> 基础设施：PostgreSQL、Redis、RocketMQ、Playwright、模型与采集配置走真实环境  
> 启动日志：`backend/logs/p2-dev-smoke-9093-20260624-171350.out.log`  
> 目标：验证 `P2-DEV-007` 任务状态回退修复，以及 `P2-LIVE-003` replay sourceUrls 回填修复。

## 任务信息

- 预检查任务：`taskId=57`，用于验证首次实现后 replay sourceUrls 的真实形态。
- 最终验证任务：`taskId=58`，用于验证补充任务级来源兜底后的 replay 和真实执行状态。

请求样例：

```json
{
  "taskName": "P2 修复后真实冒烟 - AI 知识库竞品分析 - 20260624-1714",
  "subjectProduct": "企业级 RAG 知识库平台",
  "competitorNames": ["Notion AI"],
  "competitorUrls": ["https://www.notion.so/product/ai"],
  "analysisDimensions": ["产品功能"],
  "sourceScope": ["官网"],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

## 结果摘要

| 检查点 | 结果 | 证据 |
| --- | --- | --- |
| 后端启动 | 通过 | `dev` profile 启动，`9093` ready，PostgreSQL/Flyway ready，RocketMQ producer ready |
| Create | 通过 | `taskId=58` 创建成功，初始状态 `PENDING`，节点数 7 |
| Replay 顶层 sourceUrls | 通过 | `sourceUrls=["https://www.notion.so/product/ai","https://www.notion.so"]` |
| Replay 节点 sourceUrls | 通过 | `collect/extract/analyze/write/rewrite` 均能看到来源；`quality_check` 与 `quality_check_final` 保持为空，避免对 reviewer 过度传播 |
| Execute | 通过 | `POST /api/task/58/execute` 返回 `code=200`，`message=Task execution started` |
| 状态回退观察 | 通过 | tick 1-24 覆盖 collector、extractor、analyzer 运行窗口，未观察到“节点 RUNNING 但任务 PENDING” |
| Analyzer 空输出门禁 | 生效 | `analyze_competitors` 进入 `WAITING_INTERVENTION`，任务收敛到 `STOPPED`，阻断 Writer 凭空扩写 |

## 轮询证据

| tick 范围 | 活跃节点 | 任务状态 | 结论 |
| --- | --- | --- | --- |
| 1-12 | `collect_sources_01_01:RUNNING` | `RUNNING` | 未复现 PENDING 回退 |
| 13-18 | `extract_schema:RUNNING` | `RUNNING` | 未复现 PENDING 回退 |
| 19-24 | `analyze_competitors:RUNNING` | `RUNNING` | 未复现 PENDING 回退 |
| 25-30 | 无活跃节点，`analyze_competitors:WAITING_INTERVENTION` | `STOPPED` | Analyzer 门禁触发人工介入 |

## 节点终态摘录

| 节点 | 状态 | 备注 |
| --- | --- | --- |
| `collect_sources_01_01` | `SUCCESS` | 采集成功，公开 sourceUrls 包含 `https://www.notion.so/product/ai`、`https://notion.so/product/ai`、`https://www.notion.so` |
| `extract_schema` | `SUCCESS` | 抽取成功，公开 sourceUrls 包含 `https://notion.so/product/ai` |
| `analyze_competitors` | `WAITING_INTERVENTION` | 核心结构化分析字段为空，触发人工介入 |
| `write_report` | `PENDING` | 被 analyzer 门禁阻断，未继续生成报告 |
| `quality_check` | `PENDING` | 未执行 |
| `rewrite_report` | `PENDING` | 未执行 |
| `quality_check_final` | `PENDING` | 未执行 |

## 问题记录

### P2-POST-001：最小单官网样本仍不足以通过 Analyzer 结构化门禁

- 现象：`analyze_competitors` 失败并进入 `WAITING_INTERVENTION`，错误指向 `featureComparison / positioningComparison / pricingComparison / targetUserComparison / strengthsSummary / weaknessesSummary` 均未生成。
- 判断：这是 `P2-DEV-003` 修复后的预期保护，不是编排链路崩溃；它把“分析不足”提前挡在 Analyzer 阶段，避免 Writer 继续凭空扩写。
- 后续建议：下一轮成功交付 smoke 需要扩大来源范围到 `官网 + 产品文档 + 定价页`，或选择信息密度更高、正文更稳定的目标 URL。

### P2-POST-002：PowerShell 客户端仍显示中文 mojibake

- 现象：`Invoke-RestMethod` 输出的中文字段仍出现 mojibake。
- 判断：对应既有 `P2-LIVE-004`，本轮未修复 charset 响应兼容问题。
- 后续建议：如要优化脚本联调体验，可继续评估统一声明 JSON 响应 `charset=utf-8`，或在 smoke 脚本中统一使用 `curl`/显式 UTF-8 解码。

## 验证命令

- `mvn -pl backend "-Dtest=TaskReplayProjectionServiceTest#shouldBackfillTaskLevelSourceUrlsToDownstreamSourceConsumerNodeSummaries" test`，结果 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。
- `mvn -pl backend "-Dtest=TaskReplayProjectionServiceTest" test`，结果 `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。
- `mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest,ReportWriterAgentTest,TaskNodeViewAssemblerTest,ReportServiceTest,TaskControllerTest,ReportControllerTest,NodeExecutionRecoveryPolicyTest,RuntimeStateRefresherTest,TaskReplayProjectionServiceTest" test`，结果 `Tests run: 47, Failures: 0, Errors: 0, Skipped: 0`。
