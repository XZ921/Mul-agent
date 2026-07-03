# Task12 9a 复测进度记录

## 当前阶段
- 当前阶段：准备执行 9093 本地重启与 9a 真实链路复测。
- 当前阶段：9a 复测已完成，结论与取证材料已归档。

## 执行计划
```json
{
  "taskName": "12-search-first-9a-retest",
  "sourcePlan": "docs/Tavily/plan/2026-07-02-12-search-first-routing-inversion-and-quota-calibration-plan.md",
  "dependsOnPlan": "docs/Tavily/task/2026-07-03-12-search-first-candidate-fusion-and-verification-inversion-plan.md",
      "updatedAt": "2026-07-03 20:05:30 +08:00",
  "steps": [
    {
      "id": "review-context",
      "name": "复核方案与上轮痕迹",
      "goal": "确认 9a KPI、接口路径、历史任务号与潜在已知故障",
      "eta": "15 分钟",
      "dependsOn": [],
      "status": "completed"
    },
    {
      "id": "restart-backend",
      "name": "重启 9093 后端",
      "goal": "确保本地 dev 服务重新加载当前代码并恢复可用",
      "eta": "10 分钟",
      "dependsOn": ["review-context"],
      "status": "completed"
    },
    {
      "id": "create-runtime-task",
      "name": "创建 9a 验证任务",
      "goal": "生成新的抖音开放平台 vs 哔哩哔哩开放平台真实任务并记录请求响应",
      "eta": "10 分钟",
      "dependsOn": ["restart-backend"],
      "status": "completed"
    },
    {
      "id": "execute-and-observe",
      "name": "触发执行并抓取运行态",
      "goal": "获取 task/nodes/events/log/db 关键信息，定位链路卡点",
      "eta": "30 分钟",
      "dependsOn": ["create-runtime-task"],
      "status": "completed"
    },
    {
      "id": "summarize-errors",
      "name": "归纳暴露错误",
      "goal": "输出本次 9a 复测中最核心的新旧错误与根因判断",
      "eta": "15 分钟",
      "dependsOn": ["execute-and-observe"],
      "status": "completed"
    }
  ]
}
```

## 进度看板
- [x] 读取 9a 方案与 Task12 新方案/进度
- [x] 确认真实接口入口为 `/api/task/create` 与 `/api/task/{id}/execute`
- [x] 确认上轮真实任务号为 `80`，19:44 的 `81/82` 为半成品重试
- [x] 重启 9093 后端并确认可访问
- [x] 创建新任务并保存请求/响应
- [x] 触发执行并抓取任务/节点/日志/库表快照
- [x] 总结本次暴露错误

## 当前发现
- 上轮 19:43~19:44 的错误链路包含三类现象：错误接口 `/api/analysis-task/create` 的 404、错误编码请求体导致的 UTF-8 解析失败、以及随后成功创建但未完成执行观察。
- 本次复测将以新的任务目录保存请求、响应、执行响应、任务快照、节点快照、日志切片与数据库指标，避免与旧结果混淆。
- 2026-07-03 19:58 已完成 9093 重启：旧进程 PID `14580` 已停止，新进程 PID `19284` 于 `19:58:16` 启动；`GET /v3/api-docs` 返回 `200`，`netstat` 已确认 `0.0.0.0:9093` 监听恢复。
- 2026-07-03 20:00 创建新任务 `taskId=83`，目录：`tmp/task12-9a-20260703-200027/`。`preview` 与 `create` 响应中的中文字段正常，说明本次 UTF-8 无 BOM 请求体已避开上轮编码噪音。
- 2026-07-03 20:01 触发执行后，数据库与任务接口显示 `collect_sources_01_01`、`collect_sources_02_01` 两个 collector 进入 `RUNNING`，其余节点保持 `PENDING`。
- 2026-07-03 20:00:35 ~ 20:01:02，`task_workflow_event` 中两条协作事件连续重试 6 次后进入 `DEAD_LETTER`：
  - `COLLABORATION_PLAN_RECORDED`
  - `COLLABORATION_CHECKPOINT_UPDATED`
  根因已从日志堆栈确认：RocketMQ topic `task.collaboration` 含非法字符 `.`，违反 `^[%|a-zA-Z0-9_-]+$` 校验。
- 2026-07-03 20:02 ~ 20:04，任务主链路出现停滞：`analysis_task.updated_at` 一直停在 `20:01:12`，`evidence_source` 始终为 `0` 行，两个 collector 节点持续 `RUNNING` 且没有 `last_attempt_at`、没有错误消息。
- 2026-07-03 20:04 抓取 `jcmd 19284 Thread.print`，确认 `pool-5-thread-1` / `pool-5-thread-2` 都阻塞在：
  - `cn.bugstack.competitoragent.source.TavilySearchClient.search(TavilySearchClient.java:102)`
  - 调用栈来自 `TavilyFastLaneProvider.searchFieldEvidenceQueries(...)`
  - 两个线程都卡在 `HttpClient.send(...)`，阻塞时长已超过配置的 Tavily `timeout-seconds=45`
- 2026-07-03 20:05 已主动停止任务 `83` 以避免继续占用线程。停止后 `analysis_task.status=STOPPED`，但两个 collector 节点在数据库中仍保持 `RUNNING`，20 秒后复查仍未回收，说明 stop 没有中断已经卡住的 Tavily 同步调用。
- 本次取证材料已归档到 `tmp/task12-9a-20260703-200027/`，包含请求响应、执行响应、停止响应、任务/节点/replay 快照、线程栈、数据库状态与错误日志切片。
