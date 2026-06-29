# Task 66 有序执行路线图

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 为 Task 66 修复提供唯一有序入口，确保执行者按依赖顺序执行 Tavily 相关计划。

**架构：** 本路线图协调 5 份实施计划。第 1 阶段先修复下游字段契约错位，第 2 阶段增加证据可用性门禁，第 3 阶段加固 Tavily 预抓正文和 fast-lane 审计，第 4 阶段建立公开证据补采底座，第 5 阶段在底座上接入 repair、证据提升和回归测试。每个阶段都必须产出可单独验证的中间状态。

**技术栈：** Java 17、Spring Boot、Jackson、JPA Repository、JUnit 5、Mockito、AssertJ、Maven。

---

## 为什么拆分

本规格横跨 5 类关注点，不应该混在一个大分支里一次性实现：

- 覆盖契约传递会影响工作流计划、节点配置、Extractor 和 Reviewer。
- 证据质量门禁会影响采集结果、评分语义、拦截信号和 EvidenceSource 入库。
- Tavily 结构化块会影响 `TavilyPrefetchedExecutor`、结构块分类和 fast-lane 审计。
- 公开证据补采底座会影响 selected target 安全、中介页过滤、同域补采、公开壳兜底和入库安全。
- repair/regression 集成会影响候选修复状态、证据提升和分阶段测试，避免过早用完整商业报告标准误判 task66。

执行顺序很重要：

1. `CoverageContract` 必须先落地，否则 Reviewer 的行为调整没有可信依据。
2. `EvidenceQualityGate` 必须先落地，否则结构化块和 repair 无法可靠识别导航壳、鉴权墙和低可用正文。
3. Tavily 结构化块可以并行开发，但在 `CoverageContract` 生效前不能作为最终通过标准。
4. 公开证据补采底座必须先落地，否则 Tavily repair 只能提出 query，或重新退化成 sourceType-first 采集。
5. repair/regression 必须最后执行，因为它依赖覆盖契约、证据门禁、Tavily fast-lane 审计和公开补采底座。

---

## 计划索引

### 第 1 阶段：覆盖契约传递

**计划：** `docs/Tavily/plan/2026-06-28-01-task66-coverage-contract-plan.md`

**目标：** 创建唯一权威的 `CoverageContract`，持久化到任务计划快照，通过统一 Provider 暴露，并让 Extractor/Reviewer 消费同一份契约。

**完成标准：**

- `WorkflowPlan` and `ExecutionPlanDefinition` carry a top-level `coverageContract`.
- Collector node config carries only a contract reference or compact view.
- Extractor no longer treats `pricing/weaknesses` as required for `CAPABILITY_INTRO`.
- Reviewer uses `blockingLevel` instead of fixed field rules for blocker decisions.

### 第 2 阶段：证据质量门禁

**计划：** `docs/Tavily/plan/2026-06-28-02-task66-evidence-quality-gate-plan.md`

**目标：** 在 EvidenceSource 成为报告级证据之前，增加采集后证据可用性评分和负信号封顶。

**完成标准：**

- Navigation shell, auth/captcha gate, root entry, link farm, duplicate content, and weak content are detected.
- Chinese blocked/auth signals are configuration-bound.
- High source trust no longer overrides low content usability.
- `SCORE_CONTRADICTION_DETECTED` appears when high candidate score conflicts with weak collection signals.

### 第 3 阶段：Tavily 结构化块与 fast-lane 审计

**计划：** `docs/Tavily/plan/2026-06-28-03-task66-tavily-structured-fastlane-plan.md`

**目标：** 让 Tavily 预抓正文产出可解释的结构化块，并补齐可追踪的 fast-lane 消费审计。

**完成标准：**

- `TavilyPrefetchedExecutor` no longer always emits `structuredBlocks=[]`.
- Structured block classification has anti-noise rules and confidence reasons.
- Fast-lane 消费审计必须证明 `prefetchedContentRef -> TAVILY_PREFETCHED -> evidence_source` 链路成立。
- 缺失或未消费的预抓正文不能被普通 Web fallback 静默掩盖。

### 第 4 阶段：公开证据补采底座

**计划：** `docs/Tavily/plan/2026-06-28-04-task66-search-collection-public-evidence-recovery-plan.md`

**目标：** 加固搜索采集链路，让弱入口、鉴权墙、公开壳页面和未覆盖字段证据路径能够生成已验证公开替代证据，同时防止中介页进入正式证据。

**完成标准：**

- `CollectionTargetSelector` 会拒绝未验证的中介页和搜索认证页进入正式证据。
- `CandidateOwnershipPolicy` 能识别中介页、企业信息页、鉴权页、验证码页和工具页。
- `PublicEvidenceRecoveryService` 接收带有 `fieldName / evidencePathKey / queryIntents` 的 `RecoveryContext`。
- Search trace 记录已尝试 URL、已尝试证据路径、字段上下文和补采状态。
- EvidenceSource 不再因为超长 `discoveryReason` 落库失败。

### 第 5 阶段：修复、证据提升与分阶段回归

**计划：** `docs/Tavily/plan/2026-06-28-05-task66-repair-regression-plan.md`

**目标：** 在公开补采底座之上接入 repair 状态、证据提升和 task66 分阶段回归。

**完成标准：**

- Repair states distinguish `REPAIR_QUERY_PROPOSED`, `REPAIR_CANDIDATE_VERIFIED`, and `REPAIR_EVIDENCE_PROMOTED`.
- Root entry evidence is downgraded when stronger repair evidence is promoted.
- Capability-intro task66 passes without pricing/weaknesses blockers, while full-report mode still requires them.

---

## 跨阶段规则

- 第 1 阶段不要为 `CoverageContract` 新增数据库表。第一版通过 `WorkflowPlan.coverageContract` 存入 `TaskPlan.planSnapshot`。
- 不允许 Agent 各自解析临时 JSON。必须使用共享的 `CoverageContractProvider` 和 `CoverageContractResolver`。
- 只生成 query 不能算 repair 完成。完整 repair 必须提升更强证据，或持久化明确失败状态。
- Tavily 结构化块不能覆盖 `EvidenceQualityGate` 的负信号。
- 第 4 阶段公开补采不负责决定字段最终覆盖结论。它只提供候选和审计信号；字段结论由 `DimensionEvidencePlan / FieldEvidenceCoverage` 收口。
- 不放松来源追溯。任何非空抽取字段仍然需要 `sourceUrls` 或 `evidenceIds`。

---

## 建议执行顺序

- [ ] **步骤 1：执行第 1 阶段**

打开 `docs/Tavily/plan/2026-06-28-01-task66-coverage-contract-plan.md`，按任务逐步实现。

- [ ] **步骤 2：验证第 1 阶段**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest,CoverageContractProviderTest,WorkflowPlanCoverageContractTest,SchemaExtractorAgentCoverageContractTest,QualityReviewAgentCoverageContractTest test
```

预期：所有指定测试通过。

- [ ] **步骤 3：执行第 2 阶段**

打开 `docs/Tavily/plan/2026-06-28-02-task66-evidence-quality-gate-plan.md`，按任务逐步实现。

- [ ] **步骤 4：验证第 2 阶段**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest,CollectorAgentEvidenceQualityGateTest test
```

预期：所有指定测试通过。

- [ ] **步骤 5：执行第 3 阶段**

打开 `docs/Tavily/plan/2026-06-28-03-task66-tavily-structured-fastlane-plan.md`，按任务逐步实现。

- [ ] **步骤 6：验证第 3 阶段**

运行：

```powershell
mvn -pl backend -Dtest=TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest test
```

预期：所有指定测试通过。

- [ ] **步骤 7：执行第 4 阶段**

打开 `docs/Tavily/plan/2026-06-28-04-task66-search-collection-public-evidence-recovery-plan.md`，按任务逐步实现。

- [ ] **步骤 8：验证第 4 阶段**

运行：

```powershell
mvn -pl backend -Dtest=CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,PublicEvidenceRecoveryServiceTest,PublicShellRecoveryExtractorTest,PlaywrightPageCollectorTest,EvidenceSourceSanitizerTest,EvidenceSourceMigrationArtifactsTest,SearchExecutionCoordinatorTest test
```

预期：所有指定测试通过。

- [ ] **步骤 9：执行第 5 阶段**

打开 `docs/Tavily/plan/2026-06-28-05-task66-repair-regression-plan.md`，按任务逐步实现。

- [ ] **步骤 10：验证第 5 阶段**

运行：

```powershell
mvn -pl backend -Dtest=PublicEvidenceRecoveryServiceTest,EvidenceRepairStateTest,SearchExecutionCoordinatorRepairAuditTest,Task66CoverageContractRegressionTest test
```

预期：所有指定测试通过。

- [ ] **步骤 11：运行 Tavily 聚焦回归集合**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest,AnalysisDimensionMappingCatalogTest,CoverageContractProviderTest,WorkflowPlanCoverageContractTest,SchemaExtractorAgentCoverageContractTest,QualityReviewAgentCoverageContractTest,EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest,CollectorAgentEvidenceQualityGateTest,FieldEvidenceQueryPlannerTest,TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest,PublicEvidenceRecoveryServiceTest,EvidenceRepairStateTest,SearchExecutionCoordinatorRepairAuditTest,FieldAnswerSynthesizerTest,Task66CoverageContractRegressionTest test
```

预期：所有指定测试通过。
