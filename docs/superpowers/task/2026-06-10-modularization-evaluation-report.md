# 2026-06-10 Modularization Evaluation Report

## 0. Data Collection Steps

1. 运行 `mvn "-Dtest=BackendModuleDependencyTest" test`，确认当前 ArchUnit 基线是否存在新增违规。
2. 读取 `BackendModuleDependencyTest`、`ArchitectureWhitelist`、`2026-06-10-architecture-whitelist-ledger.md`，统计规则数、白名单数和白名单集中热点。
3. 读取 `phase1` 至 `phase4b` progress 文档，确认各阶段 facade、cleanup port、contract mapping 和阶段收尾结论。
4. 扫描当前遗留白名单类的 repository import 数，确认剩余跨模块持久化直连是否已经收敛到可物理拆分水平。
5. 对比最近三次阶段合入提交 `4c74ce8`、`3988435`、`c2aed84` 的文件列表，确认是否仍反复修改同一热点文件。

---

## 1. Current Boundary Status

- agent-runtime baseline status:
  - `phase1` 已完成并合入 integration。
  - runtime baseline 相关规则通过，最小运行时基线未新增 repository / entity 依赖。
  - source:
    - `docs/superpowers/task/2026-06-10-phase1-agent-runtime-baseline-progress.md`
    - `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`

- task-orchestration facade status:
  - `phase3a` 已完成并合入 integration。
  - `TaskQueryFacade`、`TaskRuntimeFacade` 已建立，task cleanup 协调已从 task 主路径收口。
  - source:
    - `docs/superpowers/task/2026-06-10-phase3a-task-orchestration-progress.md`

- collection-intelligence facade status:
  - `phase3b` 已完成并合入 integration。
  - `CollectionEvidenceFacade` 与 `CollectionArtifactCleanupPort` 已建立，`EvidenceQueryService` 已成为稳定证据投影视图入口。
  - source:
    - `docs/superpowers/task/2026-06-10-phase3b-collection-evidence-progress.md`

- knowledge-intelligence facade status:
  - `phase4a` 已完成并合入 integration。
  - `KnowledgeRetrievalFacade` 已建立，`AgentContext` 运行时边界与 knowledge contract 归属已锁定。
  - source:
    - `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-progress.md`
    - `docs/superpowers/task/2026-06-10-knowledge-contract-mapping.md`

- report/conversation facade status:
  - `phase4b` 已完成并合入 integration。
  - `ReportQueryFacade` 已建立，`ReportService` 已切到稳定证据投影视图，`ConversationService` 已迁移到 task / knowledge / report facade。
  - source:
    - `docs/superpowers/task/2026-06-10-phase4b-report-conversation-progress.md`

---

## 2. Metrics And Sources

- ArchUnit rule count:
  - current value: `7`
  - status: `全部通过，无新增违规`
  - source:
    - `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`
    - `mvn "-Dtest=BackendModuleDependencyTest" test`

- whitelist item count:
  - current value: `7`
  - concentration:
    - `6` 条集中在 `agent_classes_should_not_access_task_repositories`
    - `1` 条集中在 `workflow_should_not_depend_on_business_agent_implementations`
  - source:
    - `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitectureWhitelist.java`
    - `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md`

- remaining cross-module repository direct dependencies:
  - current value: `6` 个仍被白名单保留的业务 Agent 类
  - classes:
    - `BaseAgent`
    - `CompetitorAnalysisAgent`
    - `CollectorAgent`
    - `SchemaExtractorAgent`
    - `QualityReviewAgent`
    - `ReportWriterAgent`
  - repository import depth:
    - `BaseAgent`: `1`
    - `CompetitorAnalysisAgent`: `2`
    - `CollectorAgent`: `3`
    - `SchemaExtractorAgent`: `3`
    - `QualityReviewAgent`: `4`
    - `ReportWriterAgent`: `3`
  - interpretation:
    - task / collection / knowledge / report / conversation facade 已建立，但核心 Agent 执行层仍保留 repository 直连，说明逻辑边界已稳定，物理边界尚未完成最后一公里。
  - source:
    - `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitectureWhitelist.java`
    - 代码扫描：`backend/src/main/java/cn/bugstack/competitoragent/agent/**`

- shared hotspots:
  - current hotspots:
    - `BaseAgent`
    - `WorkflowFactory`
    - `workflow.contract.ExtractResult`
    - `workflow.contract.CompetitorKnowledgeDraft`
    - `workflow.contract.AnalysisResult`
    - `AgentContext` / `AgentContextAssembler`
    - `EvidenceQueryService`
  - interpretation:
    - facade 层已经把上层读写边界收口，但底层仍存在跨阶段共享热点，尤其是 `BaseAgent` 和 `workflow.contract` 仍然承载多阶段协同语义。
  - source:
    - `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md`
    - `docs/superpowers/task/2026-06-10-knowledge-contract-mapping.md`
    - `docs/superpowers/task/2026-06-10-phase3a-task-orchestration-progress.md`
    - `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-progress.md`

- recent stage merge hotspot overlap:
  - compared commits:
    - `4c74ce8 feat(collection): complete phase3b evidence boundary`
    - `3988435 feat(knowledge): complete phase4a retrieval facade boundary`
    - `c2aed84 feat(report): complete phase4b report conversation boundary`
  - overlap result:
    - `phase3b` vs `phase4a`: `0`
    - `phase3b` vs `phase4b`: `2`
    - `phase4a` vs `phase4b`: `0`
  - repeated files:
    - `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
    - `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
  - interpretation:
    - 最近三次阶段合入已经没有大范围三方热点冲突，但 `EvidenceQueryService` 仍然是跨阶段反复修改的共享接缝。
  - source:
    - `git show --name-only --format=oneline 4c74ce8 3988435 c2aed84`

---

## 3. Decision Rules

- If whitelist item count > 5, do not split Maven modules yet.
- If remaining cross-module repository direct dependencies > 0, do not split Maven modules yet.
- If `BaseAgent` and `workflow.contract` still hold more than 2 shared hotspots, delay physical split.
- If recent stage merges still repeatedly touch the same hotspot files, delay physical split unless the repeated file has already been reduced to a stable shared view seam.
- If task / collection / knowledge / report / conversation all expose stable facades and ArchUnit shows no new violations, partial split can be considered, but only after whitelist concentration and repository direct dependencies drop below the delay threshold.

---

## 4. Rule Evaluation

- Rule: whitelist item count > 5
  - result: `命中阻塞`
  - evidence:
    - current whitelist count = `7`
    - `6 / 7` 白名单集中在 `agent_classes_should_not_access_task_repositories`

- Rule: remaining cross-module repository direct dependencies > 0
  - result: `命中阻塞`
  - evidence:
    - current remaining direct dependencies = `6` 个白名单 Agent 类

- Rule: `BaseAgent` and `workflow.contract` still hold more than 2 shared hotspots
  - result: `命中阻塞`
  - evidence:
    - `BaseAgent`
    - `WorkflowFactory`
    - `ExtractResult`
    - `CompetitorKnowledgeDraft`
    - `AnalysisResult`

- Rule: recent stage merges still repeatedly touch the same hotspot files
  - result: `部分命中`
  - evidence:
    - 最近三次阶段没有出现跨三阶段重复修改同一核心文件
    - 但 `EvidenceQueryService` 及其测试仍在 `phase3b` 和 `phase4b` 之间重复成为共享接缝

- Rule: all major business slices expose stable facades and ArchUnit shows no new violations
  - result: `满足前置条件，但不足以直接物理拆分`
  - evidence:
    - `TaskQueryFacade` / `TaskRuntimeFacade`
    - `CollectionEvidenceFacade`
    - `KnowledgeRetrievalFacade`
    - `ReportQueryFacade`
    - `ConversationService` 已切到 facade
    - `BackendModuleDependencyTest` 通过

---

## 5. Final Summary

- decision:
  - `暂缓 Maven 多模块物理拆分，继续维持模块化单体`

- why:
  - 当前逻辑边界已经比 phase1 初期稳定很多，facade 和 ArchUnit 守卫也已经建立。
  - 但白名单数量仍为 `7`，且 `6` 条集中在业务 Agent 对 repository 的历史直连，这说明“逻辑模块化”已经成立，“物理模块化”还缺少足够干净的依赖面。
  - 如果现在拆 Maven 模块，最可能的结果不是获得清晰模块边界，而是把白名单、共享 contract 和基础执行热点转移为跨模块循环依赖或大规模 `api` / `spi` 透传。

- what blocked a different decision:
  - 为什么不是“现在就拆 Maven 多模块”：
    - 白名单数量超过阈值；
    - 剩余 repository 直连不为零；
    - `BaseAgent` 与 `workflow.contract` 仍是共享热点。
  - 为什么不是“先局部拆分 task / collection / knowledge / report / conversation”：
    - facade 已稳定，但 Agent 执行层与历史 contract 还没有跟着收口；
    - 直接物理拆分会把当前的文档边界问题，升级成编译期依赖治理问题。

- next re-evaluation checkpoint:
  - 当以下条件同时满足时，重新评估是否进入 Maven 多模块拆分：
    - whitelist item count `<= 3`
    - `agent_classes_should_not_access_task_repositories` 白名单降到 `<= 2`
    - `WorkflowFactory` 历史豁免被移除或替换为稳定 adapter / registry 接口
    - `BaseAgent` 与 `workflow.contract` 不再同时承担超过 `2` 个共享热点
    - 连续一个阶段的合入中，不再重复触碰 `EvidenceQueryService` 这类共享接缝文件

