# 2026-06-21 P1 运行态边界续做记录

## 当前阶段

当前阶段：P1 运行态输入边界与架构收口
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：本次不涉及
- [x] 质量校验：已完成自动化回归

## 本次执行计划

| 步骤 | 核心目标 | 预期耗时 | 前置依赖 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 对照 3.3 计划与当前工作区改动，确认未收口项 | 10-15 分钟 | 读取计划文档与相关代码 | 已完成 |
| 2 | 运行 extractor / analyzer / shared output 定向测试，确认真实断点 | 10-20 分钟 | 步骤 1 | 已完成 |
| 3 | 修复 P1 新增输入 Provider 触发的架构越界问题 | 15-30 分钟 | 步骤 2 找到失败测试 | 已完成 |
| 4 | 回归架构测试、定向测试与 backend 全量测试 | 20-30 分钟 | 步骤 3 | 已完成 |

## 本次完成

1. 确认 `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md` 中 P0 已完成，当前真正需要收口的是 P1 的工程边界一致性，而不是继续补 P0 Prompt / retry 逻辑。
2. 定位到 `BackendModuleDependencyTest.agent_classes_should_not_access_task_repositories` 失败，根因是新加的 `RepositoryExtractorInputProvider` 位于 `..agent..` 包下，导致 ArchUnit 将它视作新增 Agent 侧 repository 依赖。
3. 将以下类型整体迁移到 `cn.bugstack.competitoragent.extractor.input`，把 repository 依赖从 `..agent..` 包边界移出：
   - `ExtractorCompetitorInput`
   - `ExtractorInputPackage`
   - `ExtractorInputProvider`
   - `RepositoryExtractorInputProvider`
4. 同步修正 `SchemaExtractorAgent`、`ExtractSharedOutputSanitizer`、`ExtractSharedProjection`、`SchemaExtractorAgentTest` 与 `RepositoryExtractorInputProviderTest` 的导入路径，保证 P1 输入边界仍然可用。
5. 完成自动化验证：
   - `mvn -pl backend "-Dtest=BackendModuleDependencyTest" test`
   - `mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest,SchemaExtractorAgentTest" test`
   - `mvn -pl backend test`

## 接下来要做什么

1. 如果继续沿 3.3 计划推进，优先把 P1 的“真实链路验证”补齐，确认运行态 `extract_schema -> analyze_competitors` 在真实任务上确实消费的是新的 `ExtractorInputPackage / drafts / lightweight views` 边界，而不是测试里才成立。
2. 评估是否需要把本次 P1 收口结果回写到 `summary` 或单独整理为 P1 小结，避免后续误以为 P1 仍停留在“只做了接口定义”的阶段。
3. 在 P1 明确稳定后，再进入 P2：workflow 失败分层、`evidenceCoverage` 状态细化、task `50` 真实链路验收。

## 2026-06-21 端口纠偏

1. 继续执行 P2 live 验证前置检查时，发现原计划中的 `localhost:8080` 与当前工程配置不一致。
2. 当前 `backend/src/main/resources/application.yml` 明确配置 `server.port=9093`，历史 dev live 日志也显示后端启动在 `9093`。
3. 已将计划文档中的 task `50` live 验证命令从 `localhost:8080` 修正为 `localhost:9093`。
4. 当前 `127.0.0.1:9093` 无监听进程，因此 task `50` 的 live rerun 尚未执行；下一步需要先启动本项目后端，或确认已有 dev 服务地址。

## 还剩什么没做

1. 还没有执行 P2 计划项：
   - workflow 失败分层汇总
   - `evidenceCoverage` 新状态扩展
   - task `50` 真实链路验收
2. 还没有做 task `50` 的 live rerun / report / evidences 验证，因此当前结论仍以自动化测试通过为主。
3. 还没有做提交动作；按当前协作约定，保留给用户自行提交。

## 2026-06-21 task 50 live 验证与修复记录

### 当前阶段

当前阶段：P2 真实链路验收已完成第一轮定位
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：本轮不新增业务报告，只记录验证结论
- [x] 质量校验：已完成自动化回归与 live 验证

### 本次做了什么

1. 按 `server.port: 9093` 启动本地后端，并确认 `9093` 才是本工程服务端口；`8080` 属于本机其他服务，不再用于本任务验证。
2. 对 task `50` 执行节点级 rerun 前置快照、`POST /api/task/50/nodes/extract_schema/rerun`、节点状态轮询、报告与证据接口快照。
3. 首轮 live 发现 `extract_schema` 仍停在 `WAITING_INTERVENTION`，日志显示模型输出 JSON 前两次不完整，第三次 JSON 可解析但在构建 draft 时失败：
   - `pricing` 被模型返回成数组或嵌套数组，导致 `PricingItem` 反序列化失败。
   - `pricing.plans` 中元素可能是对象，导致 `List<String>` 反序列化失败。
   - `FeatureItem / StrengthWeaknessItem` 遇到模型额外字段时失败。
4. 使用 TDD 补充并验证 extractor 单测：
   - `shouldNormalizeArrayPricingWhenBuildingKnowledgeDraft`
   - `shouldConsumeExtractorInputProviderInsteadOfReadingRepositoryDirectly`
5. 修复 extractor 对 live 模型输出形态的容错：
   - 单对象字段转换前允许数组/嵌套数组剥离到第一个有效对象。
   - `PricingItem.plans/evidenceIds/sourceUrls` 允许对象元素，并优先提取 `name/id/url` 等明确字段。
   - `FeatureItem` 与 `StrengthWeaknessItem` 对齐 `PricingItem`，允许忽略模型额外字段。
6. 修复后重新 live rerun task `50`，链路已越过 extractor/analyzer：
   - `extract_schema`: `SUCCESS`
   - `analyze_competitors`: `SUCCESS`
   - `write_report`: `SUCCESS`
   - `quality_check`: `SUCCESS`
   - `rewrite_report`: `SUCCESS`
   - `quality_check_final`: `SUCCESS`
   - task 总状态：`FAILED`

### 当前结论

task `50` 当前失败不再归因于 extractor 或 analyzer 边界。最终失败归类为下游质量闭环未通过：终审输出 `passed=false`、`score=16`、`requiresHumanIntervention=true`，任务错误为 `质量闭环未达到通过条件，请检查评审结果`。

主要质量问题集中在报告建议段落存在无法回指到 `[证据：EID]` 的推演性结论，终审建议包括补充证据、补源后重跑 `extract_schema`、复核终审问题清单，以及删除或降级无法验证的结论。

### 快照目录

1. 初始失败现场：`docs/superpowers/ExtractionStructured/progress/live-task-50-20260621-193832`
2. pricing 数组修复后现场：`docs/superpowers/ExtractionStructured/progress/live-task-50-after-pricing-fix-20260621-194715`
3. pricing 列表对象修复后现场：`docs/superpowers/ExtractionStructured/progress/live-task-50-after-pricing-list-fix-20260621-195343`
4. DTO 忽略未知字段修复后最终现场：`docs/superpowers/ExtractionStructured/progress/live-task-50-after-dto-ignore-fix-20260621-195957`

### 接下来要做什么

1. 进入 P2 下游质量闭环专项：分析 `quality-check-final-outputData.json` 中的 missing evidence 问题，确定是 writer 生成了过多无证据推演、reviewer 门禁过严，还是 evidence bundle 没有被 writer 正确引用。
2. 优先检查 writer/rewrite prompt 是否强制“每个建议结论必须携带 `[证据：EID]` 或显式降级为未验证假设”。
3. 检查 report/evidences 接口是否能稳定展示 `sourceUrls / evidenceFragments / sectionEvidenceBundles / issueFlags`，确认前端或报告层是否仍能追溯。
4. 将 task `50` 的最终失败分类从 extractor/analyzer 移交为 `DOWNSTREAM_CONSUMPTION_GAP` 或更细的质量门禁原因，后续再补 workflow 汇总字段。

### 还剩什么没做

1. 还没有实现 P2 的 workflow 失败分层汇总字段，例如 `DOWNSTREAM_CONSUMPTION_GAP`、`FIELD_MISSING_EVIDENCE` 等。
2. 还没有扩展 `evidenceCoverage` 新状态，如 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT`。
3. 还没有修复 writer/reviewer 对建议段落证据回指的最终质量闭环问题。
4. 还没有提交代码；按协作约定保留给用户自行提交。
