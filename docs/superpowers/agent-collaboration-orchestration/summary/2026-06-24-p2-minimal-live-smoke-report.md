# P2 最小真实测试记录

> 日期：2026-06-24  
> 分支：`master`  
> 启动方式：真实 Spring Boot HTTP 服务，端口 `9093`，`test` profile，H2 内存库，RocketMQ 关闭  
> 测试目标：验证 P2 协作规划在真实 Controller / Service / JPA / Replay 链路下是否可观测，并记录进入真实执行前暴露的问题。

## 测试范围

- 启动后端：`mvn -pl backend spring-boot:run "-Dspring-boot.run.profiles=test"`
- Ready 探测：`GET /api/task/list?pageNum=1&pageSize=1`
- 任务预览：`POST /api/task/preview`
- 创建任务：`POST /api/task/create`
- 查询详情：`GET /api/task/1`
- 查询节点：`GET /api/task/1/nodes`
- 查询回放：`GET /api/task/1/replay`
- 执行入口探测：`POST /api/task/1/execute`

请求样例：

```json
{
  "taskName": "P2 最小真实测试 - AI 知识库竞品分析",
  "subjectProduct": "企业级 RAG 知识库平台",
  "competitorNames": ["Notion AI"],
  "competitorUrls": ["https://www.notion.so/product/ai"],
  "analysisDimensions": ["产品功能", "价格策略"],
  "sourceScope": ["官网", "产品文档", "定价页"],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

## 结果摘要

| 检查点 | 结果 | 证据 |
| --- | --- | --- |
| 后端启动 | 通过 | `9093` 返回 HTTP 200 |
| Preview 合同 | 通过 | `code=200`，`contractType=TASK_PLAN_PREVIEW_V1`，`collectorCount=3`，`pipelineCount=6` |
| Create 合同 | 通过 | `code=200`，创建 `taskId=1`，任务状态 `PENDING` |
| 节点生成 | 通过 | `GET /api/task/1/nodes` 返回 9 个节点，节点配置包含 `collaborationGoalId / collaborationPlanId / collaborationReviewId / collaborationRoleId / collaborationQualityGate` |
| Replay 可观测性 | 通过 | timeline 包含 `COLLABORATION_PLAN_RECORDED`、`COLLABORATION_CHECKPOINT_UPDATED`、`TASK_CREATED` |
| sourceUrls 顶层溯源 | 通过 | replay 顶层 `sourceUrls=["https://www.notion.so/product/ai"]` |
| 执行入口 | 受限 | HTTP 200，但业务码 `10008 WORKFLOW_DISPATCH_UNAVAILABLE`，detail 指向 `rocketmq.enabled=false` |

执行入口响应摘要：

```json
{
  "code": 10008,
  "message": "工作流事件基础设施不可用，无法发起异步编排",
  "data": {
    "errorType": "WORKFLOW_DISPATCH_UNAVAILABLE",
    "errorCode": 10008,
    "detail": "工作流事件基础设施不可用，无法发起异步编排 — rocketmq.enabled=false"
  }
}
```

## 问题记录

### P2-LIVE-001：`test` profile 无法进入真实 DAG 执行链路

- 现象：`POST /api/task/1/execute` 没有进入节点执行，任务状态保持 `PENDING`。
- 根因线索：`TaskRuntimeCommandAppService.runAfterCommit(...)` 会先调用 `WorkflowEventOutboxService.assertWorkflowIngressReady()`；`test` profile 下 `rocketmq.enabled=false`，因此执行入口被明确阻断。
- 影响：当前最小本地真实测试只能覆盖“预览 / 创建 / 计划落库 / 回放”，不能覆盖真实采集、抽取、分析、写作 DAG 消费链路。
- 分类：环境/集成限制，不是 P2 协作规划合同本身失败。
- 后续建议：完整真实执行测试应切到 `dev` profile，并确认 PostgreSQL、Redis、RocketMQ、必要 API Key 和 Playwright 环境可用；或者为 `test` profile 增加受控的本地同步消费测试入口。

### P2-LIVE-002：业务错误使用 HTTP 200 承载，前端必须检查 `code`

- 现象：执行入口返回 HTTP 200，但业务响应 `code=10008`。
- 影响：如果前端或脚本只看 HTTP status，可能误判“执行已开始”。
- 分类：接口语义/前端兼容风险。
- 后续建议：若这是项目统一约定，需要在前端请求层和测试脚本中强制检查 `code`；若不是约定，后续应评估业务异常是否映射为非 2xx HTTP 状态。

### P2-LIVE-003：Replay 节点摘要未携带计划期 `sourceUrls`

- 现象：`GET /api/task/1/replay` 的顶层 `sourceUrls` 和 timeline 事件带有来源，但 `nodeSummaries[*].sourceUrls` 在未执行状态均为空；同一任务的 `GET /api/task/1/nodes` 节点运行态视图能看到计划期来源 URL。
- 影响：独立回放面板如果只看 `nodeSummaries`，会误以为各节点没有来源线索。
- 分类：P2 回放可追溯性观察项，未阻断当前 P2 smoke。
- 后续建议：评估 `TaskReplayProjectionService` 是否应在节点尚未执行时回填计划期 `sourceUrls`，至少对 collector / extractor / analyzer / writer 节点展示计划来源。
- 修复进展（2026-06-24）：`TaskReplayProjectionService.buildNodeSummaries(...)` 已回填节点运行输出中的嵌套 `sourceUrls`、计划配置中的 `sourceUrls / competitorUrls`，并在节点自身缺少来源时把任务级 timeline 来源兜底回填给 collector / extractor / analyzer / writer 等源消费节点；未执行节点也能在 `nodeSummaries[*].sourceUrls` 和 replay 顶层 `sourceUrls` 中暴露计划来源。
- 验证命令：`mvn -pl backend "-Dtest=TaskReplayProjectionServiceTest" test`，结果 `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。

### P2-LIVE-004：响应 Header 未声明 charset，部分客户端会中文解码异常

- 现象：`curl` 按 UTF-8 读取响应字节时中文正确；PowerShell `Invoke-RestMethod` 显示 mojibake。响应 Header 为 `Content-Type: application/json`，未包含 `charset=utf-8`。
- 影响：现代 JSON 客户端通常按 UTF-8 处理，不影响浏览器主链路；但脚本型测试和旧客户端可能出现中文展示问题。
- 分类：低风险兼容性问题。
- 后续建议：如需提升脚本联调体验，可统一声明 JSON 响应 charset，或在测试脚本中使用 `curl`/显式 UTF-8 解码。

## 测试执行备注

- 一开始使用过隔离端口 `18080`，目的是避免误打已有 `9093` 开发实例；经确认 `9093` 空闲后已切回默认端口。
- 第一次后台启动时 `spring-boot.run.arguments` quoting 不正确，导致 Maven 报 `Unrecognized option: --playwright.health-check-warmup-enabled=false`，应用未启动；已用正确 quoting 重新启动并完成测试。
- 该 quoting 问题属于测试执行噪声，不计入产品缺陷。

## 下一步建议

1. 若只验收 P2 协作规划：当前最小真实测试已覆盖真实 HTTP、落库、节点生成和 replay 可观测性，建议补一条前端页面级 smoke。
2. 若要验收真实执行闭环：启动 `dev` profile，准备 PostgreSQL、Redis、RocketMQ、外部采集和模型 Key，再执行 `POST /api/task/{id}/execute`。
3. 若要让本地最小测试可执行 DAG：设计一个 `test` profile 的受控同步消费通道，避免引入 RocketMQ 但仍能跑到 `DagExecutor`。
