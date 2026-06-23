# 2026-06-23 第三轮 live fixture 与 replay 验证计划

## 本轮红线

- 不新增 workflow delivery 节点。
- 不扩搜索与采集 3.2。
- 不把 replay / cache 绕过 Provider 直接向 extractor 写正文。
- 不让 DownstreamEvidenceView 重新变成长正文载体。

## 当前阶段

- [x] Task 1: 锁定第三轮边界与代码基线
- [x] Task 2: 引入 `ExtractorEvidenceInput` 与来源端口
- [x] Task 3: Provider 正式组包与 `auditRefs` 透出
- [x] Task 4: extractor 改为消费内部输入投影
- [x] Task 5: shared projection / cache 契约回归
- [x] Task 6: live fixture 计划与文档回链

## 样本目标

1. rerun `extract_schema` 时，`/api/task/{taskId}/replay` 或节点视图中可以看到 `extractorInput.inputSource=REPOSITORY_BACKED_PORT`。
2. `extractorInput.auditRefs.searchAudit.available=true` 时，说明 collector shared projection 已正确透传到 Provider 诊断面。
3. `extractorInput.competitors[*].skippedEvidence[*]` 仍可见 `PROMPT_BUDGET_SKIPPED / THIN_CONTENT_ONLY / CONTENT_GAP` 等跳过原因。
4. shared projection 与 Redis cache 中不出现正文长文本，只保留 trace-only 投影。

## 执行命令

```powershell
$liveLog = "backend/logs/live-9093-20260623-105931.out.log"
mvn -pl backend spring-boot:run

$taskId = 50
Invoke-RestMethod "http://localhost:9093/api/task/$taskId/nodes/extract_schema/rerun" -Method Post
Invoke-RestMethod "http://localhost:9093/api/task/$taskId/replay" -Method Get | ConvertTo-Json -Depth 10
Invoke-RestMethod "http://localhost:9093/api/task/$taskId/nodes" -Method Get | ConvertTo-Json -Depth 10
```

## 判定标准

- replay 或节点视图中能看到 `extractorInput.inputSource` 与 `auditRefs`；
- 任何 `readableEvidence.content`、`downstreamEvidenceViews.content` 都不应再带长正文；
- 如果 dev 服务未启动或 task 不存在，文档必须明确写“live 未执行原因”，不能伪造成功结论。

## 回归结果

- `mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,QualityReviewAgentTest,SharedNodeOutputProjectorContractTest,TaskSnapshotCacheServiceTest" test`：通过。
- `mvn -pl backend test`：通过，`Tests run: 673, Failures: 0, Errors: 0, Skipped: 3`。

## live 执行结果

- 2026-06-23 10:59:31 通过 `mvn -pl backend spring-boot:run` 启动 dev 服务，日志文件为 `backend/logs/live-9093-20260623-105931.out.log`；`2026-06-23 11:00:08` 日志确认 `CompetitorAgentApplication` 启动完成，`9093` 已开始监听。
- 首次检查 `taskId=50` 时，`extract_schema` 仍停留在历史失败现场：`status=WAITING_INTERVENTION`、`outputData=null`，因此旧样本本身不具备第三轮字段验证面。
- 2026-06-23 11:02:08 调用 `POST /api/task/50/nodes/extract_schema/rerun` 触发局部重跑；`2026-06-23 11:02:53` 节点转为 `SUCCESS`，并生成新的 `outputData`。
- `/api/task/50/nodes` 中 `extract_schema.outputData` 已验证到：
  - `extractorInput.inputSource=REPOSITORY_BACKED_PORT`；
  - `extractorInput.auditRefs.searchAudit.available=false`，`availabilityReason=COLLECTOR_SHARED_ENVELOPE_MISSING`；
  - `extractorInput.competitors[*].skippedEvidence[*]` 可见 `PROMPT_BUDGET_SKIPPED`；本样本这轮未出现 `THIN_CONTENT_ONLY` 或 `CONTENT_GAP`；
  - `extractorInput.competitors[*].readableEvidence.content`、`evidenceCatalog.content` 与 `downstreamEvidenceViews.content` 最大长度均为 `0`，说明 shared projection / 节点视图已经只保留 trace-only 投影，不再泄漏正文。
- `/api/task/50/replay` 本轮仍未直接暴露 `extractorInput.inputSource` 或 `REPOSITORY_BACKED_PORT` 字样；当前 live 可见面以 `/api/task/50/nodes` 的 `outputData` 为主。
- 结论：第三轮关于 `ExtractorInputPackage.inputSource`、`skippedEvidence` 透出以及正文裁剪的目标已在 live 节点视图中得到验证；但同一历史样本只重跑了 extractor，没有重跑 collector 节点，所以 `auditRefs.searchAudit.available` 诚实地保持为 `false`。若要验证 `available=true`，需要从 collector 节点重跑或重新执行一份全新任务，让当前 collector shared envelope 一并落库。

## 追加排障记录

- 在补做 collector live 重跑后，`collect_sources_01_01` 已成功重新执行，`extract_schema` 也再次成功，但 `extractorInput.auditRefs.collectorEnvelopeCount` 仍然是 `0`，说明问题不在样本本身，而在运行态上下文传递链。
- 根因已定位为 `DagExecutor.forkNodeContext()` 只复制了 `sharedState`，没有把 `sharedOutputEnvelopes` 传给下游节点上下文，导致 extractor runtime 虽然能看到字符串投影，却拿不到 collector shared envelope 元数据。
- 已补回归测试 `DagExecutorTest.shouldPassSharedOutputEnvelopeToDownstreamNodeContext`，先红灯复现“downstream context 缺 envelope”，再用最小修复把 `sharedOutputEnvelopes` 一并传入子上下文。
- 本地验证结果：
  - `mvn -pl backend "-Dtest=DagExecutorTest#shouldPassSharedOutputEnvelopeToDownstreamNodeContext" test`：通过；
  - `mvn -pl backend "-Dtest=DagExecutorTest" test`：通过；
  - `mvn -pl backend "-Dtest=DagExecutorTest#shouldPassSharedOutputEnvelopeToDownstreamNodeContext,RepositoryExtractorInputProviderTest,SchemaExtractorAgentTest,SharedNodeOutputProjectorContractTest,TaskSnapshotCacheServiceTest" test`：通过。
- 第二层根因继续定位后确认：`collect_sources_*` 的真实 collector 输出同时带有 `searchAudit/searchExecutionTrace/selectedTargets` 与 `evidenceFragments/downstreamEvidenceViews`，会同时命中 `SearchSharedNodeOutputProjector` 与 `ExtractSharedNodeOutputProjector` 的 `supports()` 条件；而 `DagExecutor.projectSharedOutput()` 只取第一个命中的 projector，导致 live 环境里 collector 节点可能被错误投影成 `EXTRACT_SHARED_PROJECTION_V1`，从而让 `RepositoryExtractorInputProvider.auditRefs.searchAudit.available` 继续显示为 `false`。
- 已按红绿测试修复该识别边界：
  - 新增 `SharedNodeOutputProjectorContractTest.shouldNotTreatCollectorOutputAsExtractorSharedProjection`，先验证旧实现会误把 collector 输出识别成 extractor projection；
  - 将 `ExtractSharedProjection.supportsExtractorOutput()` 收紧为只接受真正带 `drafts` 或 `extractorInput` 的 extractor 输出，不再把仅携带 `evidenceFragments/downstreamEvidenceViews` 的 collector 输出误判为 extract shared projection。
- 二次回归验证结果：
  - `mvn -pl backend "-Dtest=SharedNodeOutputProjectorContractTest" test`：通过；
  - `mvn -pl backend "-Dtest=DagExecutorTest#shouldPassSharedOutputEnvelopeToDownstreamNodeContext,SharedNodeOutputProjectorContractTest" test`：通过；
  - `mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest,TaskSnapshotCacheServiceTest" test`：通过。
- 最终 live 复验：
  - 2026-06-23 14:00 左右重新启动 `9093` dev 服务，并在 `2026-06-23 14:01:21` 再次触发 `POST /api/task/50/nodes/collect_sources_01_01/rerun`；
  - `collect_sources_01_01` 于 `2026-06-23 14:01:28` 启动并成功，`extract_schema` 于 `2026-06-23 14:01:53` 启动，并在 `2026-06-23 14:02:51` 成功完成；
  - `/api/task/50/nodes` 中 `extract_schema.outputData` 最新验证结果为：
    - `extractorInput.inputSource=REPOSITORY_BACKED_PORT`；
    - `extractorInput.auditRefs.searchAudit.available=true`；
    - `extractorInput.auditRefs.searchAudit.availabilityReason=SEARCH_SHARED_PROJECTION_READY`；
    - `extractorInput.auditRefs.collectionAudit.available=true`；
    - `extractorInput.auditRefs.collectorEnvelopeCount=3`；
    - `extractorInput.auditRefs.projectionTypes=["SEARCH_SHARED_PROJECTION_V1"]`；
    - `readableEvidence.content`、`evidenceCatalog.content` 仍为长度 `0`，`downstreamEvidenceViews.content` 仍保持 trace-only 空内容（序列化后长度 `2`，即 `""`）。
- 结论：第三轮关于 `ExtractorInputPackage.inputSource`、`auditRefs.searchAudit.available`、`skippedEvidence` 透出，以及 shared projection / cache 不携带长正文的目标，已经在 live 节点视图中全部闭环验证。
