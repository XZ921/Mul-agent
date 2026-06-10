# Phase 3B Collection Evidence Progress

- 当前阶段：Phase 3B Collection Evidence
- 当前 Task：Phase 3B 提交与合入 integration
- 当前 Step：Step 1 重新验证并执行提交，随后合入 `integration/backend-modular-monolith-refactor`
- 状态：IN_PROGRESS
- 已完成：4 / 4
- 剩余步骤：提交当前 phase3b 未提交变更；把 `a/phase3b-collection-evidence` 合入 `integration/backend-modular-monolith-refactor`；随后基于最新 integration 创建 `phase4a` 新 worktree / 分支
- 阻塞项：当前 `a/phase3b-collection-evidence` 与 `integration/backend-modular-monolith-refactor` 的 HEAD 同为 `4f87a6e`，`git rev-list --left-right --count integration/backend-modular-monolith-refactor...a/phase3b-collection-evidence` 结果为 `0 0`；这说明 phase3b 成果仍全部停留在未提交工作区，尚未形成可合入提交，因此流程上还不能进入 `phase4a` 代码开发
- 执行工作区：`E:\java_study\Mul-agnet\.worktrees\a-phase3b-collection-evidence`
- 执行分支：`a/phase3b-collection-evidence`
- 结构化执行计划：

| Step | 核心目标 | 预期耗时 | 前置条件 | 状态 |
| --- | --- | --- | --- | --- |
| Task 1 / Step 1 | 定义 `CollectionEvidenceFacade` 最小接口，并先补 `EvidenceQueryServiceTest` 红灯基线 | 45 分钟 | phase3a 已完成；新 worktree 已从 integration 拉出 | 已完成 |
| Task 1 / Step 2 | 创建 `CollectionEvidenceFacadeImpl`，先包装 `EvidenceQueryService`，不改变现有 report 行为 | 45 分钟 | Step 1 红灯基线已建立 | 已完成 |
| Task 1 / Step 3 | 在 `EvidenceQueryService` 增加 task 级与 node 级证据读取能力，并保持当前 evidenceId 前缀算法语义一致 | 60 分钟 | Step 2 完成；不得改动 `BaseAgent` / `DagExecutor` | 已完成 |
| Task 1 / Step 4 | 运行 `mvn -Dtest=EvidenceQueryServiceTest test`，记录 Task 1 验证结果并回写 progress | 30 分钟 | Step 1 - Step 3 完成 | 已完成 |
| Task 2 / Step 1 | 创建 `CollectionArtifactCleanupPort` 并接入 task cleanup coordinator | 60 分钟 | Task 1 已完成 | 已完成 |
| Task 2 / Step 2 | 把 `TaskArtifactCleanupService` 中的 evidence 删除职责迁出到 collection cleanup port，回收 task 侧对 `EvidenceSourceRepository` 的历史直连 | 45 分钟 | Step 1 完成 | 已完成 |
| Task 2 / Step 3 | 新增前缀 contract test，锁定 `CollectorAgent` evidenceId 编码与节点级删除使用同一算法 | 45 分钟 | Step 1 - Step 2 完成 | 已完成 |
| Task 2 / Step 4 | 运行 `mvn -Dtest=CollectorAgentTest,EvidenceQueryServiceTest test` 并同步更新白名单台账 | 30 分钟 | Step 1 - Step 3 完成 | 已完成 |
| Task 3 / Step 1 | 补 `SearchExecutionCoordinatorTest`，锁定候选验证 / 补源 / 目标选择职责边界 | 60 分钟 | Task 2 已完成 | 已完成 |
| Task 4 / Step 1 | 运行 collection 线聚焦测试并核对 phase3b 未误改 `BaseAgent` / `DagExecutor` | 45 分钟 | Task 3 已完成 | 已完成 |

- 当前阶段状态播报：
  - 当前阶段：Phase 3B 已完成，evidence facade、cleanup port、前缀 contract 与搜索职责边界均已收口，等待切换到 `phase4a / Task 1`
  - [x] 隔离工作区准备：已完成
  - [x] 红灯测试：已完成
  - [x] 最小实现：已完成
  - [x] Task 1 验证与回写：已完成
  - [x] Task 2 cleanup port：已完成
  - [x] Task 3 搜索职责边界：已完成
  - [x] Task 4 阶段收尾：已完成
- Task 4 执行备注：
  - 本轮收尾只做 phase3b 文档要求的聚焦验证与边界复核，不引入新的功能改动。
  - 复核重点是两项：collection 线回归测试必须 fresh 通过；`BaseAgent` 与 `DagExecutor` 在当前 worktree 中不得出现误改。
- Task 4 完成记录：
  - 已执行 `git diff --name-only -- backend/src/main/java/cn/bugstack/competitoragent/agent/BaseAgent.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`，结果为空，确认 phase3b 未误改 `BaseAgent` / `DagExecutor`。
  - 已执行 Task 4 文档指定验证：`mvn "-Dtest=SearchExecutionCoordinatorTest,CandidateVerifierTest,SourceCandidateRankerTest,BrowserSearchRuntimeServiceTest,CollectorAgentTest,EvidenceQueryServiceTest" test`，结果 `Tests run: 43, Failures: 0, Errors: 0`。
  - 当前仍保留的历史边界说明：phase3b 不承诺清理 `CollectorAgent` 继承 `BaseAgent` 带来的 runtime / repository 历史依赖，本阶段只完成 evidence 边界收口与搜索职责锁定。
- Task 3 执行备注：
  - 本轮只锁定 `SearchExecutionCoordinator` 的职责顺序与兜底语义，没有做大规模重构，也没有改动 `BaseAgent` / `DagExecutor`。
  - 红灯首先命中真实边界缺口：`BROWSER_ONLY` 模式此前只看规划候选数量，未把“验证后是否仍不足”纳入补源决策，导致验证失败时也会误跳过浏览器补源。
  - 现已补齐三类职责测试：候选验证先于目标选择、验证足够时跳过补源、补源为空时保留规划候选；并在协调器中补充中文职责注释。
- Task 3 完成记录：
  - `SearchExecutionCoordinatorTest` 新增 3 组边界用例，并补 1 组 `BROWSER_ONLY` 验证失败后仍需浏览器补源的回归测试。
  - `SearchExecutionCoordinator.shouldSupplement(...)` 已调整为“启用结果页验证时优先以验证达标与否决定是否补源”，避免候选数量充足但验证全失败时被误判为无需补源。
  - `SearchExecutionCoordinator` 已补中文职责注释，明确“先验证、再补源、最后选目标”的边界顺序。
  - 先执行红灯基线：`mvn "-Dtest=SearchExecutionCoordinatorTest" test`，失败原因为 `shouldTriggerBrowserSupplementInBrowserOnlyModeWhenPlannedCandidatesFailVerification` 断言未满足，暴露 `BROWSER_ONLY` 模式补源决策缺口。
  - 红灯修复后验证通过：`mvn "-Dtest=SearchExecutionCoordinatorTest" test`，结果 `Tests run: 13, Failures: 0, Errors: 0`。
  - Task 3 文档指定验证通过：`mvn "-Dtest=SearchExecutionCoordinatorTest,CandidateVerifierTest,SourceCandidateRankerTest,BrowserSearchRuntimeServiceTest" test`，结果 `Tests run: 29, Failures: 0, Errors: 0`。
- 现状备注：phase3b Task 文档要求后续用 contract test 锁定 evidence 前缀算法；当前仓库里的既有前缀实现位于 `TaskArtifactCleanupService.buildEvidencePrefix(...)`，本次 Task 1 只做同义读取收口，不提前改动 cleanup 路径。
- Task 1 完成记录：
  - 新增 `CollectionEvidenceFacade` / `CollectionEvidenceFacadeImpl`，先以 `EvidenceQueryService` 作为稳定读取实现。
  - `EvidenceQueryService` 新增 `listTaskEvidence(Long)` 与 `listEvidencesByNode(Long, String)`，节点过滤前缀与现有 cleanup 编码保持同义。
  - `ArchitecturePackageMapping.COLLECTION_PACKAGES` 已纳入 `cn.bugstack.competitoragent.collection..`，避免 phase3b 新包游离在边界测试之外。
  - 先执行红灯基线：`mvn "-Dtest=EvidenceQueryServiceTest" test`，失败原因为缺少 `listTaskEvidence(Long)` / `listEvidencesByNode(Long, String)`。
  - Task 1 验证通过：`mvn "-Dtest=EvidenceQueryServiceTest" test`，结果 `Tests run: 6, Failures: 0, Errors: 0`。
  - 补充验证通过：`mvn "-Dtest=EvidenceQueryServiceTest,CollectionEvidenceFacadeImplTest" test`，结果 `Tests run: 8, Failures: 0, Errors: 0`。
  - 边界回归通过：`mvn "-Dtest=BackendModuleDependencyTest,ArchitectureWhitelistTest" test`，结果 `Tests run: 9, Failures: 0, Errors: 0`。
- Task 2 执行备注：
  - 当前实现现状显示：若不把 `TaskArtifactCleanupService` 中的 evidence 删除迁出，仅新增 port 仍无法满足“task 侧不再直碰 `EvidenceSourceRepository`”目标，也无法回收 phase2 遗留白名单。
  - 因此本轮 Task 2 同时落地 cleanup port、前缀 contract test，并回收了 `TaskArtifactCleanupService` 的 `task_should_not_depend_on_evidence_repository_directly` 豁免。
- Task 2 完成记录：
  - 新增 `CollectionArtifactCleanupPort` 与 `EvidenceIdPrefixContract`，collection 模块现在承接任务级与节点级 evidence 删除。
  - `TaskArtifactCleanupService` 已移除 `EvidenceSourceRepository` 依赖，不再直接承担 evidence 表删除。
  - `CollectorAgentTest` 新增 evidenceId 前缀契约测试，锁定 `CollectorAgent.generateEvidenceId(...)` 与 cleanup 前缀算法保持一致。
  - `ArchitectureWhitelist` 与 `2026-06-10-architecture-whitelist-ledger.md` 已移除 `TaskArtifactCleanupService` 对 evidence repository 的历史豁免。
  - `2026-06-10-phase2-archunit-boundary-progress.md` 已同步去掉 `TaskArtifactCleanupService` 这个遗留白名单集中点。
  - 先执行红灯基线：`mvn "-Dtest=CollectionArtifactCleanupPortTest,CollectorAgentTest" test`，失败原因为缺少 `CollectionArtifactCleanupPort` 与 `EvidenceIdPrefixContract`。
  - Task 2 聚焦验证通过：`mvn "-Dtest=CollectionArtifactCleanupPortTest,CollectorAgentTest" test`，结果 `Tests run: 11, Failures: 0, Errors: 0`。
  - Task 2 文档指定验证通过：`mvn "-Dtest=CollectorAgentTest,EvidenceQueryServiceTest" test`，结果 `Tests run: 14, Failures: 0, Errors: 0`。
  - 白名单与边界回归通过：`mvn "-Dtest=BackendModuleDependencyTest,ArchitectureWhitelistTest" test`，结果 `Tests run: 9, Failures: 0, Errors: 0`。
- 2026-06-10 串行接管补充记录：
  - 当前接管工作区：`E:\java_study\Mul-agnet\.worktrees\a-phase3b-collection-evidence`，已确认这是 linked worktree，不在主工作区直接开发。
  - 已执行 `git status --short` 与 `git diff --stat`，确认 phase3b 成果尚未提交，当前 worktree 相对 `HEAD 4f87a6e` 存在未提交修改与新增文件。
  - 已执行 `git worktree list --porcelain`，确认 `integration/backend-modular-monolith-refactor` 与 `a/phase3b-collection-evidence` 当前 HEAD 同为 `4f87a6e`，说明 phase3b 仍未合入 integration。
  - 本轮先执行合入前检查与门禁确认，只允许更新 progress / task 文档与收尾说明，不提前开始 `phase4a` 代码开发。
- 当前阶段状态播报：
  - 当前阶段：Phase 3B 已完成代码与验证，等待提交并合入 integration
  - [x] 信息采集：已完成
  - [x] 变更范围初检：已完成
  - [x] 合入门禁判断：已完成
  - [ ] phase4a worktree 方案：待执行
- 2026-06-10 fresh 合入前验证记录：
  - 已执行 `mvn "-Dtest=SearchExecutionCoordinatorTest,CandidateVerifierTest,SourceCandidateRankerTest,BrowserSearchRuntimeServiceTest,CollectorAgentTest,EvidenceQueryServiceTest,CollectionArtifactCleanupPortTest,CollectionEvidenceFacadeImplTest" test`，结果 `Tests run: 48, Failures: 0, Errors: 0`。
  - 已执行 `mvn "-Dtest=BackendModuleDependencyTest,ArchitectureWhitelistTest" test`，结果 `Tests run: 9, Failures: 0, Errors: 0`。
  - 已执行 `git diff --check`，未发现空白符错误；仅提示当前工作区若后续由 Git 触碰文件，将把若干文件的行尾统一为 `CRLF`。
  - 已再次执行 `git diff --name-only -- backend/src/main/java/cn/bugstack/competitoragent/agent/BaseAgent.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`，结果为空，确认本轮接管后仍未误改 `BaseAgent` / `DagExecutor`。
  - 结论：phase3b 的代码范围、测试结果与文档边界均满足“可提交 / 可准备合入 integration”的技术条件；但由于当前成果尚未提交、integration 分支也尚未吸收 phase3b 提交，因此仅满足“可提交 / 可合入”，尚不满足“已合入后可进入 phase4a”。
- phase4a 串行准备方案：
  - 等 phase3b 提交并合入 `integration/backend-modular-monolith-refactor` 后，再从最新 integration 创建新分支 `a/phase4a-knowledge-intelligence`。
  - 新 worktree 目录保持同一约定，建议使用：`E:\java_study\Mul-agnet\.worktrees\a-phase4a-knowledge-intelligence`。
  - 进入 phase4a 前，先更新 `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-progress.md`，把当前 Task 固定为 `Task 1 建立 KnowledgeRetrievalFacade`，再开始代码开发。
- 2026-06-10 提交与合入执行计划：
  - Step 1：重新执行 phase3b 聚焦测试与边界测试，确保提交前仍有 fresh 通过证据。
  - Step 2：在 `a/phase3b-collection-evidence` 提交当前全部 phase3b 变更，不回退任何既有修改。
  - Step 3：将 phase3b 提交合入 `integration/backend-modular-monolith-refactor`，并在合并结果上重复关键验证。
  - Step 4：若 integration 合并成功，则基于最新 integration 准备 `a/phase4a-knowledge-intelligence` 新 worktree / 分支，但仍不启动 phase4a 代码开发。
- 最后更新：2026-06-10 20:12
