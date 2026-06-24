# AI 竞品分析 Agent 协作系统业务全景与功能优化路线图设计

## 总览层

### 文档目的

本文档用于给当前工程建立一张可持续演进的总控母图，避免后续继续以“哪个类先坏了就修哪个类”的方式碎片化推进。

本文档回答四个核心问题：

1. 当前系统完整业务全景到底由哪些主业务链路组成。
2. 每条链路分别由哪些执行引擎承接。
3. 所有链路共同受哪些跨链路红线与流程纪律约束。
4. 每条链路当前处于诊断、方案、实施、实链验证中的哪个阶段，以及专题文档挂载在哪里。

### 当前结论

1. 当前系统不应再被理解为“若干 Agent 的堆叠”，而应被理解为：
   `9 条主业务链路 + 4 个业务执行引擎 + 6 个平台底座`。
2. 业务链路、执行引擎、平台底座三者不是对等关系，而是：
   `业务链路 > 执行引擎 > 平台底座`。
3. `搜索与采集` 链路已经形成了高密度问题样板，[CollectorAgent.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/problem/CollectorAgent.md) 应作为本设计中的第一份深度专题基线，而不是被重新发明。
4. 后续优化的正确顺序不是“先平台、后业务”，而是：
   `先收敛业务链路语义 -> 再收敛执行引擎边界 -> 最后下沉到底座治理`。
5. 本文档短期服务于“竞品分析平台”主航道，长期预留为“通用研究任务操作系统”能力骨架。

一句话工作法：
`先把 specs 变回地图；对每条链路先诊断，后方案，再实施，最后做实链验证。`

---

## 1. 总控层

### 1.1 主业务链路总图

当前系统的主战场应被拆成以下 9 条业务链路：

1. 任务定义与编排
2. 搜索与采集
3. 提取结构化
4. 分析推理
5. 报告写作
6. 质量审查
7. 修订与重写
8. 对话协同
9. 交付与审计

### 1.2 主业务链路表

| 业务板块名称 | 核心功能组件 | 业务痛点 / 潜在瓶颈 | 与执行引擎的映射关系 |
| --- | --- | --- | --- |
| 任务定义与编排 | `CreateTaskRequest`、`TaskDefinitionAppService`、`WorkflowFactory`、`WorkflowPlan`、`SearchExecutionPlan`、计划预览 | `🟡` 历史存量问题已在现有方案与实现中部分显化，待正式诊断文档收口 | `任务执行引擎` |
| 搜索与采集 | `CollectorAgent`、`SearchExecutionCoordinator`、`CandidateVerifier`、`CollectionTargetSelector`、`BrowserSearchRuntimeService`、`SourceDiscoveryService`、`SourceCollector` | 搜索补源质量波动大；动态抓取与反爬对业务结果影响极大；候选验证、正式采集、搜索现场审计和恢复语义存在断层 | `搜索执行引擎` |
| 提取结构化 | `SchemaExtractorAgent`、`CompetitorKnowledge`、`ExtractResult`、`CompetitorKnowledgeDraft`、共享追溯契约（`EvidenceFragment` / `SectionEvidenceBundle`） | 运行时输出契约与持久化知识快照两套世界并存；任务抽取结果与领域记忆实体混层；字段级与章节级追溯契约属于跨链路共享对象，owner 尚未收口 | `任务执行引擎` + `知识摄取 / RAG / 记忆` |
| 分析推理 | `CompetitorAnalysisAgent`、`AnalysisResult`、分析覆盖对象、知识草稿 | `⬜` 待诊断，当前描述不作为正式结论 | `任务执行引擎` |
| 报告写作 | `ReportWriterAgent`、`Report`、章节证据束、报告正文和摘要 | `⬜` 待诊断，当前描述不作为正式结论 | `任务执行引擎` |
| 质量审查 | `QualityReviewAgent`、`QualityCheckpoint`、`QualityDiagnosis`、`RevisionPlan`、`ReportDiagnosisAssembler` | `⬜` 待诊断，当前描述不作为正式结论 | `质量回流引擎` |
| 修订与重写 | `CompensationGraphAssembler`、`DynamicTaskGraphService`、`TaskRuntimeCommandAppService`、`RecoveryEngine`、`RevisionDirective` | `⬜` 待诊断，当前描述不作为正式结论 | `质量回流引擎` |
| 对话协同 | `ConversationService`、`IntentRecognitionService`、`ModeRouter`、`TaskActionTranslator`、`ConversationAgent` | `⬜` 待诊断，当前描述不作为正式结论 | `对话动作引擎` |
| 交付与审计 | `ReportService`、`ExportPackageService`、`ReportExportRenderer`、`TaskEventReplayService`、`TaskSseHub`、`AIAuditLogger` | `⬜` 待诊断，当前描述不作为正式结论 | `任务执行引擎` + `质量回流引擎` |

### 1.3 业务执行引擎表

| 执行引擎 | 主要承接对象 | 当前痛点 / 潜在瓶颈 |
| --- | --- | --- |
| 任务执行引擎 | DAG、节点状态、动态补图、重跑、恢复、人工接管、任务推进 | 当前大量语义集中在 `DagExecutor`、`WorkflowFactory`、`TaskRuntimeCommandAppService`；技术节点语义和业务阶段语义尚未彻底分层 |
| 搜索执行引擎 | 搜索计划、候选生成、候选验证、补源顺序、正式选源、搜索现场回放 | 搜索计划和运行时行为已经明显是业务规则，但仍常被当作平台杂项；恢复、缓存、回放、观察的契约未完全稳定 |
| Agent 协作编排引擎 | Orchestrator Agent、协作目标、协作计划、角色分工、Agent 建议、质量诊断、编排决策、决策校验、协作轨迹 | 当前系统已有多 Agent 能力模块和 DAG 执行底座；P1 已把终审失败回流纳入 Orchestrator 决策链路，P2 已把规则优先前置协作规划映射到标准 DAG，并把抽取后证据缺口转成受策略保护的决策输入；后续仍需在 P3 收口分析、写作、对话和 Citation Agent 的协作边界 |
| 质量回流引擎 | 质量问题 -> 补证 / 重写 / 重跑 / 人工接管 的动作转换 | `质量审查` 与 `修订与重写` 之间的桥梁已经存在，但缺少独立引擎视角；质量结果驱动后续图的规则仍分散 |
| 对话动作引擎 | 意图识别、模式路由、动作预览、确认执行、任务控制提交 | 对话目前更像“大服务聚合层”，缺少动作执行协议、风险确认协议和正式命令协议的清晰边界 |

### 1.4 平台底座表

| 平台底座 | 核心功能组件 | 业务痛点 / 潜在瓶颈 |
| --- | --- | --- |
| 知识摄取 / RAG / 记忆 | `KnowledgeIngestionService`、`TaskRetrievalService`、`TaskRetrievalIndexService`、`MemoryFusionService`、`MemoryWritebackService` | 资料接入、检索、记忆、复用都在演进，但任务级 / 领域级 / 组织级边界仍需要更正式的契约 |
| AI 与工具治理 | `ModelGateway`、`ProviderRegistry`、`RoutingPolicy`、`BudgetGuard`、`PromptTemplateService`、`AIAuditLogger` | 已经有统一网关雏形，但策略治理、预算门槛、工具权限和降级语义仍需继续收口 |
| 可观测 | `TaskEventPublisher`、`TaskSseHub`、`AgentLogService`、任务快照、搜索进度事件 | 实时观察能力已经存在，但任务级视角、节点级视角、搜索现场视角还未完全统一 |
| 恢复与回放 | `TaskRecoveryService`、`RecoveryEngine`、`TaskEventReplayService`、`TaskSnapshotCacheService`、检查点对象 | 当前更偏“恢复语义存在”，还没完全做到“恢复现场可解释”；搜索现场和任务现场的恢复粒度不一致 |
| 质量 / 配额 / 安全治理 | `OrganizationQuotaPolicy`、`TaskQuotaCoordinator`、`UrlSecurityUtils`、`HttpUrlOnlyValidator`、质量规则对象 | 质量治理、预算治理、安全治理虽然可合并讨论，但职责 owner 长期不应混在一个心智模型里 |
| 多渠道资料接入 | `KnowledgeController`、`KnowledgeIngestionRequest`、`ConnectorSyncService`、`ConnectorDefinition` | 当前能力已经显露入口，但“上传资料 / API 资料 / 认证资料 / 未来连接器”尚未被提升成正式产品能力线 |

### 1.5 跨链路约束层

| 共享契约 / 红线 | 统一规则 | 落地载体 | 当前违规状态 |
| --- | --- | --- | --- |
| Facade 互调规则 | Controller 只依赖 Facade / AppService；跨链路正式调用优先走 Facade，不直接越过边界直连底层服务或仓储 | `TaskRuntimeFacade`、`TaskQueryFacade`、`ReportQueryFacade`、未来各链路 Facade | `🟡` 历史存量仍需按专题逐步收口，未确认全面执行 |
| Agent 持久化红线 | Agent 只负责结构化业务结果，不直接承担正式持久化职责；历史白名单只能收缩不能扩散 | `BackendModuleDependencyTest`、`ArchitectureWhitelist` | `🟡` 已有边界规则，但仍依赖历史白名单约束 |
| `sourceUrls` 可追溯红线 | 所有关键结构化对象、诊断对象、导出对象都必须可回指来源；缺少来源只能显式标记缺口，不能静默省略 | `EvidenceFragment`、`QualityDiagnosis`、`RevisionDirective`、`ReportResponse` | `🟡` 已知 Collector 历史输出与部分存量对象仍未做到全覆盖 |
| 预览态 / 运行态分离 | 计划预览、运行快照、节点详情、搜索现场不能共用“看起来一样但语义不同”的弱契约 | `TaskNodeResponse`、计划预览 DTO、运行态 DTO | `🟡` 任务定义与编排链路已推进，但真实链路尚未完成全面验收 |
| 共享上下文裁剪规则 | 共享上下文只注入稳定业务事实，不回灌整包节点原始 JSON；恢复复用字段和调试字段必须分层 | `AgentContext`、`TaskSnapshotCacheService`、`DagExecutor` | `🟡` 搜索链路仍有大对象回灌与恢复字段混层的历史负担 |
| Golden Master 命名规范 | 对抽取、分析、写作、质检、交付等结构化输出，统一使用 `*GoldenMasterTest` 或 `*SnapshotContractTest` 命名，避免快照测试随意散落 | 后续各链路专题测试资产 | `⬜` 多数链路尚未建立正式快照测试资产 |
| ArchUnit 边界规则 | 新增专题时必须先决定边界规则，再决定白名单；白名单必须记录原因、回收阶段和 owner | `BackendModuleDependencyTest`、`ArchitectureWhitelist` | `🟡` 边界规则已存在，但 owner / 回收阶段记录仍待逐步补齐 |
| PR 合并顺序 | 统一按 `契约 -> 引擎 -> Agent / Service -> 查询投影 / UI` 顺序合并，避免先改页面再倒推底层语义 | 研发流程约束 | `🟡` 当前作为流程纪律存在，后续需在专题实施中持续执行 |
| 专题文档命名规范 | 每条主业务链路都应最终形成一份独立专题文档，建议沿用 `docs/superpowers/<专题>/problem/*.md` 或等价统一命名 | 当前基线：`CollectorAgent.md` | `🟡` 当前仅搜索链路具备正式基线，其余链路待补齐 |

### 1.6 流程纪律

1. 链路推进顺序固定为 `诊断 -> 方案 -> 实施 -> 实链验证`；历史存量允许补课，但状态表不得提前打 `✅`。
2. `specs` 只写地图、红线、纪律与专题索引；未经诊断支撑的逐链路优化方案不得回灌到本文件。
3. `plans` 只写已确认范围；未明确非目标和验收口径的计划，不得标记为 `方案完成`。
4. 自动化测试通过只代表 `实施` 可复核，不代表 `实链验证` 完成。
5. 每补齐一条链路专题，都必须回链更新本看板，避免文档状态与代码状态脱节。
6. 决策速查统一遵循一句话工作法：`先把 specs 变回地图；对每条链路先诊断，后方案，再实施，最后做实链验证。`
7. 链路、执行引擎、平台底座三层采用不同追踪方式：链路看板追踪 `诊断 / 方案 / 实施 / 实链验证`，执行引擎追踪 `成熟度`，平台底座追踪 `基线状态`，三者不得混用。

---

## 2. 链路状态看板

本节用于标记每条主业务链路当前所处阶段。其目的不是统计“做了多少代码”，而是明确该链路是否已经完成从诊断、方案、实施到真实场景验收的闭环。

### 2.1 状态列定义

| 列 | 含义 | 通过条件 |
| --- | --- | --- |
| 诊断 | 已有问题证据、边界判断、blocking 项归类 | `docs/superpowers/<专题>/problem/*.md` 或等价统一命名的诊断文档存在，且内容覆盖该链路主路径 |
| 方案 | 已形成被确认的目标边界和改造思路 | `docs/superpowers/<专题>/plan/*.md` 存在，且明确写出做什么、不做什么与验收口径 |
| 实施 | 代码已落地，自动化测试通过，必要文档已更新 | 关联实现已落地到目标工作分支，单元测试 / 集成测试 / 架构测试通过，相关契约与文档同步更新 |
| 实链验证 | 真实场景端到端验收 | 同时满足下述 4 条硬条件 |

状态符号统一解释如下：

- `✅`：完全满足该列通过条件。
- `🟡`：已有部分成果，但未满足该列全部通过条件。
- `⬜`：尚未形成正式资产，或尚未开始。

### 2.2 实链验证 4 个硬条件

缺一个都不能标记 `✅`：

1. **真实场景**：跑的是代表性真实链路，不是只跑 mock 或纯构造输入。
2. **产出正确**：主路径产出与预期一致，不只是“没报错”或“没异常日志”。
3. **过程可观测**：关键中间状态（事件、进度、审计快照、检查点）完整可见。
4. **恢复 / 回放 / 重跑**：涉及恢复、回放、重跑的链路，必须验证这些行为至少一次。

### 2.3 含义硬约束

- 自动化测试（单元测试、集成测试、契约测试、架构测试）归 `实施`，不归 `实链验证`。
- `实链验证` = 真实运行，不是自动化测试的别名。
- 未完成真实端到端验收的链路，即使代码已写、测试已补，也不得标记为“已完成”。
- 历史存量如果先有方案或实现，允许补课，但状态表不得倒推出不存在的 `诊断` 或 `实链验证`。

### 2.4 当前状态

| 链路 | 诊断 | 方案 | 实施 | 实链验证 |
| --- | --- | --- | --- | --- |
| 任务定义与编排 | 🟡 历史存量，待补正式诊断文档 | ✅ [2026-06-11-task-definition-and-orchestration-contract.md](/E:/java_study/Mul-agnet/docs/superpowers/task-definition-and-orchestration-contract/plan/2026-06-11-task-definition-and-orchestration-contract.md) | ✅ 2026-06-12 已按 3.1 实施封板条件复核 | 🟡 dev live 已验证 rerun / resume 入口可调度，完整任务仍受下游提取链路阻塞 |
| 搜索与采集 | ✅ [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/problem/CollectorAgent.md) | ✅ [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/plan/2026-06-12-search-and-collection-execution-engine.md) 已按诊断继承、阻塞分级、优化波次重写，旧 Task 轴方案不再作为正式依据；同时最终 `架构 1` 已由 [2026-06-17-search-and-collection-architecture-design.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/specs/2026-06-17-search-and-collection-architecture-design.md) 冻结为“家族驱动的分层采集架构”，明确 `source family` 才是业务语义，`PUBLIC_SEARCH` 只是发现工具层，`JinaReader -> Playwright` 是网页采集主备路径，`RSS / GitHub API` 是正式 structured owner。第四轮联合实施进一步把统一 discovery 口径、候选元数据标准化、`Wave 6` owner 边界收口、`Wave 7` 最小采集执行接缝和首个 GitHub API 结构化采集执行器一起落地，第五轮则继续把 `Wave 8` 承接为 `JinaReader` 主路径 + `Playwright FULL_RENDER` 兜底的正式网页采集契约收口，第六轮再把 `Wave 9` 收口为正式 `collectionAudit / collectionReplayTimeline / collectionAuditCheckpoint / 包级 rerun-resume` 闭环；第七轮已进一步把显式 RSS feed 作为正式采集 owner 接入，并明确普通 news article URL 继续走网页采集，避免把 `News API` 误用为“按 URL 精确提取正文”的伪 owner | 🟡 首轮三个 blocking 收口包、第二轮自动化契约、第四轮 discovery/collection 联合实施、第五轮网页采集加固、第六轮采集审计/回放/恢复闭环与第七轮 RSS 收敛均已落地；`Source Family Catalog`、`CollectionExecutionCoordinator`、`CollectorAgent` 新采集骨架、`GithubApiCollectionExecutor`、`RssFeedCollectionExecutor`、双路径网页采集执行器、正式 `collectionAudit` 子域、稳定来源锚点复用与 `collectionAuditCheckpoint` 回填均已完成自动化收口。当前主线已经从搜索与采集收口切换到 3.3；搜索与采集后续只保留跨重启 replay 底座与 P0/P1 缺陷修复，不再阻塞 3.3 启动 | 🟡 2026-06-12 搜索段已通过 dev live 验收（补源/采集/回放/rerun/resume）；2026-06-15 任务 `39` 已补跑第二轮 preview/create/execute/replay/rerun/resume smoke，搜索事实源、回放、重跑与恢复字段通过；2026-06-16 第五轮聚合命令与 `mvn -pl backend test` 已通过，证明 `Wave 8` 双路径网页采集在自动化层已收口；2026-06-17 任务 `43` 在 dev live 中进一步验证 `Wave 9`：`collect_sources_01_02` 首轮暴露 `collectionAudit.summary.totalPackages=4 / successCount=1 / failedCount=3 / status=PARTIAL_SUCCESS`，节点级 `rerun` 后 `collectionAuditCheckpoint` 成功回填进 `nodeConfig`，并得到 `reusedCount=1`，其中 `collect_sources_01_02#002` 明确标记 `reusedFromCheckpoint=true` 与 `checkpointSource=collectionAuditCheckpoint`。同日第七轮 RSS convergence live smoke 继续验证了三条关键事实：显式 feed URL 在正常配置下走 `RSS/API_DATA` 并保留 `feedUrl + itemUrl`；普通 news article URL 在 live rerun 中保持 `WEB_PAGE` 路由与原始 article `resourceLocator`，没有误走 `RSS`；使用正确覆盖键 `--collection.rss-feed.enabled=false` 后，显式 feed URL 会返回 `TOOL_UNAVAILABLE_FAST_FAIL`，且不会降级成网页采集。当前主停点已从采集与 extractor 兼容性故障迁移到 3.3 提取与下游质量边界；因此 3.2 可以冻结，但不会把整链路提前标绿 |
| 提取结构化 | ✅ [ExtractionStructured.md](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md) | ✅ [架构规格](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md) + [实施总计划](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md) | 🟡 第八轮第一阶段已落地 `DownstreamEvidenceView`、`TASK` 快照边界和 `0` 业务字段阻断；2026-06-21 已完成 P0（Prompt 分层、字段指引、0 字段语义重试、结构块型证据入口、`schemaId / dimensions` 注入）自动化收口，P1 再收 analyzer 优先级与运行态边界 | ⬜ 待在真实采集 -> 真提取 -> 真分析 -> 真报告链路上完成闭环验收 |
| 分析推理 | ⬜ | ⬜ | ⬜ | ⬜ |
| 报告写作 | ⬜ | ⬜ | ⬜ | ⬜ |
| 质量审查 | ✅ [QualityReview.md](/E:/java_study/Mul-agnet/docs/superpowers/quality-review/problem/QualityReview.md) | ✅ [2026-06-24-quality-review-closure-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/quality-review/plan/2026-06-24-quality-review-closure-plan.md) | 🟡 P1 已完成 Reviewer / Orchestrator 职责边界收口，质量事实输出、兼容修订建议和终审失败回流 smoke 已有自动化证据 | ⬜ 待补真实外部中间件 / LLM live 证据包 |
| 修订与重写 | ✅ [RevisionAndRewrite.md](/E:/java_study/Mul-agnet/docs/superpowers/revision-and-rewrite/problem/RevisionAndRewrite.md) | ✅ [2026-06-24-revision-and-rewrite-closure-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/revision-and-rewrite/plan/2026-06-24-revision-and-rewrite-closure-plan.md) | 🟡 P1 已把终审失败动态补图升级为 `OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation -> DynamicTaskGraphService`，并保留 `RevisionDirective` 兼容输入 | ⬜ 待补真实动态补图 live 证据包 |
| 对话协同 | ⬜ | ⬜ | ⬜ | ⬜ |
| 交付与审计 | ⬜ | ⬜ | ⬜ | ⬜ |

---

## 3. 链路详情与专题索引

本节只承担“专题挂载与当前阶段说明”职责，不预写未经诊断支撑的逐链路优化方案。某条链路只有在完成正式诊断后，才允许补入对应方案链接。

### 3.1 任务定义与编排

- 诊断：`🟡` 当前属于历史存量，待补正式诊断文档。
- 方案：`✅` 已有方案文档 [2026-06-11-task-definition-and-orchestration-contract.md](/E:/java_study/Mul-agnet/docs/superpowers/task-definition-and-orchestration-contract/plan/2026-06-11-task-definition-and-orchestration-contract.md)。
- 实施：`✅` 已按本看板封板条件复核。
  复核证据：`TaskDraft -> TaskDefinition -> ExecutionPlanDefinition -> WorkflowPlan` 正式链路已落地；预览态返回 `TASK_PLAN_PREVIEW_V1`，运行态节点返回 `TASK_NODE_RUNTIME_V1`；创建任务时写入 `currentPlanVersionId/currentPlanVersion`，`rerun / resume` 继续沿用同一计划版本语义；`TaskDefinitionContractTest`、`TaskDefinitionAppServiceTest`、`TaskControllerTest`、`WorkflowFactoryTest`、`WorkflowPlanValidatorTest`、`TaskRuntimeCommandAppServiceTest`、`AnalysisTaskServiceTest`、`TaskPlanVersionerTest`、`BackendModuleDependencyTest`、`TaskDefinitionVerificationIntegrationTest`、`Phase1WorkflowIntegrationTest`，以及前端 `TaskCreatePage.test.tsx`、`taskNodeInsights.test.ts` 已于 2026-06-12 本地 PASS。
- 实链验证：`🟡` 已完成 dev live app 验收，rerun / resume 入口已不再被本地异步编排阻塞，但完整业务质量闭环仍未通过。
  当前证据：2026-06-12 以真实 Spring Boot 应用（`dev` profile，PostgreSQL / Redis / RocketMQ 端口可用）完成 `POST /api/task/preview`、`POST /api/task/create`、`POST /api/task/{id}/execute`、`POST /api/task/{id}/resume`、`POST /api/task/{id}/nodes/{nodeName}/rerun`、`GET /api/task/{id}`、`GET /api/task/{id}/nodes` 验收，确认正式异步编排入口可调度，`rerun / resume` 返回 200。
  当前证据补充：2026-06-15 使用 User 级 `DEEPSEEK_API_KEY`（后缀 `e66d2c7b`）重启 dev live app 后，任务 `37` 通过 `/api/task/37/resume` 从 `extract_schema` 检查点恢复，提取、分析、撰写、初审、改写、终审节点均执行到 `SUCCESS`，原 LLM token blocker 已解除。
  当前阻塞：完整任务最终总状态仍为 `FAILED`，原因是最终质量门禁未通过（`qualityScore=61`、`qualityPassed=false`），不是本地异步编排入口不可用，也不是 LLM token 鉴权失败。

### 3.2 搜索与采集

- 诊断：`✅` 已有诊断文档 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/problem/CollectorAgent.md)。
- 方案：`✅` 方案文档已重写完成，[2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/plan/2026-06-12-search-and-collection-execution-engine.md) 已明确废止旧 Task 轴结构，改为按 `诊断继承 -> 阻塞分级 -> 优化波次 -> 首轮实施裁剪 -> 统一 discovery / 采集边界闭环` 推进，并显式继承“source family 才是业务语义、PUBLIC_SEARCH 只是发现工具层、structured owner 直接承接正式采集”的原则。对应的最终冻结稿见 [2026-06-17-search-and-collection-architecture-design.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/specs/2026-06-17-search-and-collection-architecture-design.md)。方案同时把官网、新闻、GitHub 等纳入 `Source Family Catalog` 配置架构，并将 `Wave 6` 改写为“family-first discovery 解释 + 主辅语义审计 + discovery/collection owner 边界”，不再要求额外补做 GitHub vertical discovery provider。旧稿不再作为正式方案依据。
- 实施：`🟡` 首轮三个 blocking 收口包（执行真相收口、质量止血、连续性事实源）已于 2026-06-12 落地；第二轮自动化契约收口已于 2026-06-15 完成，覆盖 attemptedTargets、discardedCandidates、稳定 replay timeline、collector insight 直出、preview/runtime source family 同构和排序质量硬化。封板条件中的策略入口统一、HEURISTIC 清理、模板治理、SearchKeywordPolicy、searchAudit、Source Family Catalog 配置骨架，以及 `Wave 5` 对象瘦身、`Wave 6` 统一 discovery 边界收口均已到位。第七轮已把显式 RSS feed 正式接入统一采集执行体系，并锁定“普通新闻文章 URL 继续走网页采集、News API 后移到独立 news discovery 专题”的 owner 边界。当前 3.2 已冻结并转为 3.3 输入契约基线；后续只保留跨重启 replay 持久化和 P0/P1 缺陷，不再继续作为主线专题扩张。
  当前证据：`SearchAuditTimelineContractTest, SearchPreviewRuntimeHomologyContractTest, SearchExecutionCoordinatorTest, SearchAuditSnapshotCompatibilityTest, CollectionTargetSelectorTest, SourceCandidateRankerTest, HeuristicSourceDiscoveryServiceTest, BrowserPreviewSearchSourceProviderTest, WorkflowFactoryTest, RuntimeEventEmitterTest, TaskReplayProjectionServiceTest, TaskEventReplayServiceTest, TaskNodeViewAssemblerTest, SearchAndCollectionGoldenMasterTest` 聚合命令通过 49 tests；`mvn -pl backend test` 通过 438 tests。
- 实链验证：`🟡` 搜索段已于 2026-06-12 通过 dev live 验收；2026-06-17 任务 `43` 已进一步把 `Wave 9` 尾证推进到真实质量门禁，但该结果现在用于证明“3.2 可支撑 3.3”，不再作为继续扩搜索采集主线的理由。
  已通过：① 真实场景（dev 环境任务 `33`，4 个 COLLECTOR 节点，14 个 sourceUrls）；② 搜索段产出正确（searchAudit 完整，回放接口返回 4 条 searchReplays）；③ 过程可观测（采集节点均含 searchAudit，rerun 后保持 searchAuditCheckpoint=SELECT_TARGETS）；④ rerun / resume 返回 200，入口可调度；⑤ 任务 `43` 中 `collectionAuditCheckpoint` 回填、包级复用与 `collect_sources_01_02#002 reusedFromCheckpoint=true` 已在真实 rerun 生效，修复 extractor 的 `PricingItem.hasFreeTier` 兼容性后，`extract_schema -> analyze_competitors -> write_report -> quality_check -> rewrite_report -> quality_check_final` 节点均执行到 `SUCCESS`。
  补证：`GET /api/agent-log/task/43/agent/REVIEWER` 显示初审与终审节点虽执行成功，但都返回 `passed=false`、`requiresHumanIntervention=true`、`score=0`；问题集中在 `unsupported_claim`、`missing_evidence`、`STRUCTURE_COMPLETENESS`、`EVIDENCE_TRACEABILITY`、`ACTIONABILITY`，且 reviewer 明确要求补齐 `pricing`、`产品概览`、`市场定位`、`目标用户`、`核心能力`、`优势判断`、`短板与风险` 等字段证据。
  未通过：第二轮新增 attempted / discarded / replay timeline / insight / homology / ranking 事实已完成自动化复核，并于 2026-06-15 通过任务 `39` 补跑 dev live API smoke；第七轮 RSS 自动化收口也已完成，但第七轮 dev live 验收、跨重启 replay 持久化与最终业务质量门禁仍未完成。当前全链路未升绿的主因已从采集与 extractor 兼容性故障转为真实证据质量 / 报告质量门禁问题，因此搜索与采集专题仍不能整体升 `✅`。

### 3.3 提取结构化

- 诊断：`✅` 已有诊断文档 [ExtractionStructured.md](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md)。该文档是本链路的正式诊断基线，负责回答当前停点、已收口事实、P0/P1/P2 blocking 与验收口径；它必须继续存在，不由架构规格或实施计划替代。
- 继承前提：`✅` 已有 [3.2 继承前提基线](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/problem/2026-06-20-extraction-structured-3.2-inheritance-baseline.md)。当前结论是 3.2 搜索与采集已经足以支撑 3.3 启动；`sourceUrls / qualitySignals / structuredBlocks / evidenceCoverage / TASK` 快照边界是 3.3 的既有输入契约，不再把“继续扩搜索采集能力”写成本链路前置条件。
- 方案：`✅` 已有 [3.3 架构规格](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md) 与 [3.3 实施总计划](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md)。2026-06-21 已将旧计划重写为 `P0 -> P1 -> P2` 分轮推进：P0 只修 extractor 主停点，P1 收运行态边界，P2 拆下游失败与 live 验证。
- 已收口事实：`🟡` 第八轮第一阶段已经落地 `DownstreamEvidenceView`、`ExtractResult / drafts / sourceUrls / issueFlags / evidenceFragments / sectionEvidenceBundles` 输出、`CompetitorKnowledge TASK` 快照边界和 `0` 业务字段阻断。2026-06-21 完成 P0 后，2026-06-22 又继续落地了 P1 第一版：`ExtractorInputProvider / ExtractorInputPackage`、analyzer drafts 优先、extract shared output sanitizer/轻量投影均已接线，P2 的 workflow 下游失败汇总也已开始进入 `DagExecutor`。
- 当前 P0：`✅` 自动化收口已完成。验证证据包括 `SchemaExtractorAgentTest` 新增红灯转绿、`mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test` 通过，以及后续 backend 全量回归。
- 下一步 P1：`🟡` Provider 第一版、analyzer 优先级和 shared output 轻量化已经落地；下一步收口点转为 Provider 内部数据源统一、原始 node output/replay 长期边界，以及更多 live 质量观察。
- 后续 P2：`🟡` `evidenceCoverage` 细化已经落地，`DOWNSTREAM_CONSUMPTION_GAP` 已覆盖终审 `passed=false` 与初审明确要求人工补证据两类 reviewer 阻断；后续仍需把 workflow 汇总继续泛化到更多 analyzer / writer / reviewer / delivery 失败形态。
- 实链验证：`🟡` 2026-06-22 task `50` 已完成真实 rerun 验收并最终通过终审，但 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 等新状态仍缺 live 样本。后续 live 验证仍需记录 rerun 前现场、节点级输出、报告证据视图和失败层分类，不能只看任务最终 `SUCCESS/FAILED`。
- 当前不做：不回头扩搜索发现、采集路由、RSS owner、Playwright 并发池；不把 `SchemaService` 拉入 extractor 热路径；不先改前端或报告 DTO 扩字段；不一次性重构完整 `CompetitorKnowledge` 或 `SchemaExtractorAgent`。

### 3.4 Agent 协作编排层

- 定位：`Agent 协作编排层` 不是第十条业务链路，而是横跨 `任务执行引擎`、`质量回流引擎` 和 `对话动作引擎` 的协作决策层。
- 方案：`✅` 已新增架构规格 [2026-06-23-agent-collaboration-orchestration-architecture-spec.md](/E:/java_study/Mul-agnet/docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md)。
- P1 实施：`✅` 终审失败回流 MVP 已完成自动化收口，[2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md](../superpowers/agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md) 已把动态补图从 `Reviewer revisionDirectives` 直接驱动升级为 `OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation -> DynamicTaskGraphService`。
- P2 实施：`✅` 前置协作规划与抽取后证据缺口决策已完成自动化收口，[2026-06-24-agent-collaboration-orchestration-p2-collaboration-plan-implementation-plan.md](../superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p2-collaboration-plan-implementation-plan.md) 已落地 `CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint / AgentSuggestion`，并验证 `WorkflowFactory / ExecutionPlanDefinitionBuilder / replay / DagExecutor` 只消费受控协作元数据、不生成自由 DAG；P3 的分析、写作、对话和 Citation 仍不在本轮范围内。
- P3-1 实施：`✅` Analyzer 分析缺口已接入标准 `AgentSuggestion -> OrchestrationDecision` 协作决策链路；当 `analyze_competitors` 缺核心分析维度时，不再只依赖节点失败字符串阻断，而是记录可审计的分析缺口建议、来源状态和 Orchestrator 决策。`DagExecutor` 已在 `WAIT_FOR_HUMAN` 场景下阻断 Writer，并补齐 trace / replay / smoke / backend 全量回归验证；Writer、Conversation、Citation 仍留在后续 P3 子阶段。
- 阶段边界：3.4 应在 `3.3 提取结构化` 证据边界基本稳定后进入；它采用 Orchestrator-first 双阶段架构，先冻结前置 `CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint` 与运行期 `AgentSuggestion / QualityDiagnosis / OrchestrationDecision / DecisionPolicyRuleSet / DecisionPolicyResult / DecisionExecutorAdapter / DynamicPlanMutation / DecisionTrace / OrchestratorCheckpoint` 等协作协议，再以终审失败后的质量回流作为 MVP 闭环，不提前把所有节点改成 LLM 自治调度。
- 风险红线：Reviewer 只输出质量事实，Orchestrator 输出编排决策，DAG / DynamicTaskGraphService 负责安全执行；所有新协议必须保留 `sourceUrls` 或显式 `evidenceState` 证据缺口状态，Citation Agent 可以后移，但来源追溯不能后移。
- 当前不做：不推倒 `DagExecutor`，不把 `ExecutionPlanDefinitionBuilder` 改成自由智能规划器，不让 Orchestrator 自由生成任意节点，不用 Orchestrator 替代 Citation Agent，不引入新的 Python Agent 运行时作为前置条件。

### 3.5 其他链路专题索引

| 链路 | 诊断文档 | 方案文档 | 备注 |
| --- | --- | --- | --- |
| 分析推理 | ⬜ 待写 `docs/superpowers/<专题>/problem/AnalysisReasoning.md` 或等价专题文档 | ⬜ | 先诊断，后方案 |
| 报告写作 | ⬜ 待写 `docs/superpowers/<专题>/problem/ReportWriting.md` 或等价专题文档 | ⬜ | 先诊断，后方案 |
| 质量审查 | ✅ [QualityReview.md](/E:/java_study/Mul-agnet/docs/superpowers/quality-review/problem/QualityReview.md) | ✅ [2026-06-24-quality-review-closure-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/quality-review/plan/2026-06-24-quality-review-closure-plan.md) | 已收口 Reviewer 与 Orchestrator 职责边界；live 证据后续补齐 |
| 修订与重写 | ✅ [RevisionAndRewrite.md](/E:/java_study/Mul-agnet/docs/superpowers/revision-and-rewrite/problem/RevisionAndRewrite.md) | ✅ [2026-06-24-revision-and-rewrite-closure-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/revision-and-rewrite/plan/2026-06-24-revision-and-rewrite-closure-plan.md) | 已收口动态补图由 Orchestrator 决策驱动；live 证据后续补齐 |
| 对话协同 | ⬜ 待写 `docs/superpowers/<专题>/problem/ConversationCollaboration.md` 或等价专题文档 | ⬜ | 先诊断，后方案 |
| 交付与审计 | ⬜ 待写 `docs/superpowers/<专题>/problem/DeliveryAndAudit.md` 或等价专题文档 | ⬜ | 先诊断，后方案 |

---

## 4. 执行引擎成熟度看板

执行引擎是跨链路的横切层。它的优化需求通常不是先写一份独立诊断文档再开始，而是当多条链路的诊断共同指向同一类引擎问题时，才逐步浮现并收口。

### 4.1 成熟度定义

| 状态 | 含义 | 通过条件 |
| --- | --- | --- |
| ⬜ 隐式存在 | 语义散落在具体服务、私有方法或混合对象里 | 尚无独立边界定义，优化需求仍需从链路诊断中抽取 |
| 🟡 已识别 | 有明确边界定义，但代码未独立 | 已能说明“它是什么、不是什么”，但仍混在既有服务中 |
| ✅ 独立子域 | 有独立包 / 模块、独立契约、独立测试 | 代码和契约已从具体链路实现中抽出，能够作为正式横切能力被多个链路消费 |

### 4.2 当前状态

| 引擎 | 成熟度 | 当前状态 | 主要来源 |
| --- | --- | --- | --- |
| 任务执行引擎 | 🟡 已识别 | 边界已在任务定义与编排链路中被显式识别，但 `WorkflowFactory`、`DagExecutor`、`TaskRuntimeCommandAppService` 仍混合承载计划生成、调度、事件、快照与动态补图职责 | [2026-06-11-task-definition-and-orchestration-contract.md](/E:/java_study/Mul-agnet/docs/superpowers/task-definition-and-orchestration-contract/plan/2026-06-11-task-definition-and-orchestration-contract.md) |
| 搜索执行引擎 | 🟡 已识别 | 优化需求已从搜索与采集链路中浮现；`Wave 6-9` 已把 family-first discovery 解释、`CollectionExecutionCoordinator`、正式 `collectionAudit` 子域、`collectionReplayTimeline`、`collectionAuditCheckpoint` 与包级复用语义收口进统一执行骨架，第七轮又进一步把 RSS owner 纳入同一执行与审计体系；但当前语义仍散落在 `CollectorAgent`、`SearchExecutionCoordinator`、`CandidateVerifier`、`CollectionTargetSelector`、`BrowserSearchRuntimeService` 等协作者中；`Source Family Catalog` 已有骨架，后续重点已转为跨重启 replay 持久化、下游业务质量闭环，以及如有需要再单开 news discovery 专题 | [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/problem/CollectorAgent.md)、[2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/plan/2026-06-12-search-and-collection-execution-engine.md)、[2026-06-17-search-and-collection-architecture-design.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/specs/2026-06-17-search-and-collection-architecture-design.md) |
| Agent 协作编排引擎 | 🟡 已识别 | 已通过 3.4 架构规格明确其不是第十条业务链路，而是让业务 Agent、DAG 执行层、质量回流和对话动作入口形成统一协作决策的横切引擎；当前代码中的 `RevisionDirective.orchestrationAction`、`CompensationGraphAssembler`、`DynamicTaskGraphService`、`ClarificationOrchestrator` 等仍存在隐式编排语义，后续需按协议逐步迁移 | [2026-06-23-agent-collaboration-orchestration-architecture-spec.md](/E:/java_study/Mul-agnet/docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md) |
| 质量回流引擎 | ⬜ 隐式存在 | 系统中已存在“质量问题 -> 补证 / 重写 / 重跑 / 人工接管”的隐含转换，但仍待质量审查与修订重写两条链路共同诊断后再收口为独立引擎边界 | 当前总控层映射与现有运行时实现 |
| 对话动作引擎 | ⬜ 隐式存在 | 统一入口已经形成，但动作预览、确认、正式执行的边界尚未通过正式链路诊断固化为独立引擎语义 | 当前总控层映射与现有会话实现 |

执行引擎升 `✅` 的硬条件：

1. `任务执行引擎`：计划生成、运行调度、事件投递、快照裁剪、动态补图已从混合服务中拆清；存在独立契约与独立测试，不再由 `WorkflowFactory` / `DagExecutor` / `TaskRuntimeCommandAppService` 交叉承载。
2. `搜索执行引擎`：`SearchPolicyResolver` 成为唯一策略入口，`SearchExecutionCoordinator` 不再自行推导 fallback 顺序，搜索审计快照与恢复检查点契约稳定；至少一个真实 `PRIMARY_VERTICAL` source family 已通过正式 collection owner 进入真实链路，`resolveProviderRole` 能区分业务家族与公网辅助 discovery provider，搜索相关协作者已形成独立子域边界。
3. `Agent 协作编排引擎`：`CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint / AgentSuggestion / QualityDiagnosis / OrchestrationDecision / DecisionPolicyRuleSet / DecisionPolicyResult / DecisionExecutorAdapter / DynamicPlanMutation / DecisionTrace / OrchestratorCheckpoint` 等协议稳定；任务开始时 Orchestrator 能输出可审计协作计划，运行中 Reviewer 不再直接输出最终编排动作，Orchestrator 决策必须经过策略校验后由 DAG / 动态图服务执行，并能在任务回放中追溯 `sourceUrls / evidenceState`、计划原因、决策原因、执行结果和恢复游标。
4. `质量回流引擎`：质量问题分类、回流动作协议、受影响范围分析、动态补图模板已经独立成正式协作者，并由质量审查与修订重写两条链路共同消费。
5. `对话动作引擎`：模式识别、动作预览、确认执行、正式命令桥接已形成独立契约与独立测试，不再散落在会话服务内部字符串判断。

---

## 5. 平台底座基线看板

平台底座是基础设施层。它的优化通常按需触发，当多条链路都依赖某项共享能力，而该能力缺少统一入口或统一契约时，才说明底座基线需要提升。

### 5.1 基线定义

| 状态 | 含义 | 通过条件 |
| --- | --- | --- |
| ⬜ 未建立 | 尚无稳定底座能力，仍靠链路内局部实现硬撑 | 没有统一入口，也没有可复用契约 |
| 🟡 已有基础能力但缺乏统一入口 / 契约 | 已经有可用组件，但消费方式不统一 | 存在能力实现，但尚未形成正式统一入口或未被所有相关链路共同消费 |
| ✅ 统一入口 + 所有链路共同消费 | 已形成正式底座能力 | 有统一入口、统一契约，并被相关链路共同消费 |

### 5.2 当前状态

| 底座 | 基线状态 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| 知识摄取 / RAG / 记忆 | 🟡 已有基础能力但缺乏统一入口 / 契约 | 资料接入、检索、记忆、复用能力已经存在，但任务级 / 领域级 / 组织级边界仍未收口为统一基线 | 尚未成为所有链路共享的正式事实层 |
| AI 与工具治理 | 🟡 已有基础能力但缺乏统一入口 / 契约 | `ModelGateway` 与 Provider 配置能力已存在，但策略治理、预算门槛、工具权限和降级语义仍未统一 | 仍偏“网关雏形”，不是完整治理底座 |
| 可观测 | 🟡 已有基础能力但缺乏统一入口 / 契约 | 任务快照、SSE、Agent 日志、搜索进度事件均已存在，但任务级、节点级、搜索现场级观察口径尚未统一 | 仍缺统一观察视图 |
| 恢复与回放 | 🟡 已有基础能力但缺乏统一入口 / 契约 | 恢复语义与回放能力已存在，但“恢复现场可解释”与“跨重启可回放”仍未形成统一基线；本地异步编排入口也尚未形成统一验证基线 | 仍偏能力存在，不是正式平台 |
| 质量 / 配额 / 安全治理 | 🟡 已有基础能力但缺乏统一入口 / 契约 | 质量规则、预算协调、安全校验均已存在，但三类治理对象仍未形成统一治理面 | 不应继续散落到各链路硬编码 |
| 多渠道资料接入 | 🟡 已有基础能力但缺乏统一入口 / 契约 | 上传资料、认证资料与未来连接器入口已显露，但尚未形成统一资料接入语义 | 仍不是正式产品能力线 |

### 5.3 当前阻塞型底座任务

| 底座任务 | 关联底座 | 状态 | 阻塞范围 | 说明 |
| --- | --- | --- | --- | --- |
| 本地异步编排 / 消息基础设施验证基线 | 恢复与回放 | 🟡 已有 dev live 验证证据，待沉淀统一脚本 | 不再阻塞搜索与采集段实链验证；仍需沉淀为跨链路可复用验收基线 | 2026-06-12 dev live 验收中，`POST /api/task/{id}/nodes/{nodeName}/rerun` 与 `POST /api/task/{id}/resume` 已返回 200，说明本地 RocketMQ / outbox 调度入口在 dev 环境可用。2026-06-15 任务 `37` 从 `extract_schema` 检查点恢复后推进到最终质检，证明原 LLM token blocker 已解除；当前失败来自质量门禁未通过，不属于异步编排入口不可用。 |
## 2026-06-20 第八轮状态回链

- 搜索与采集链路在 2026-06-19 轻量采集收尾后已进入冻结收口状态；当前重点不再是继续扩新的采集 owner，而是让 `qualitySignals / structuredBlocks / sourceUrls / evidenceCoverage` 被提取、分析、写作、质检与交付主路径正式消费。
- 提取结构化链路已从“只有诊断、等待正式入口”推进到“已有实施计划并开始落地”：
  - extractor / analyzer 已共享统一 `DownstreamEvidenceView` 运行期证据边界；
  - extractor 默认不再把任务现场抽取结果伪装成 `DOMAIN` 记忆，而是显式落 `TASK` 快照；
  - reviewer / report / export 已开始区分 `sourceUrls` 缺失、`structuredBlocks` 缺失、`qualitySignals` 失败与 `evidenceCoverage` 缺字段。
- 这一步的直接后续不再是扩采集能力，而是先按 3.3 计划判断 extractor 主停点是否已经转移出“非空业务字段补抽”，再继续围绕 `ExtractResult vs CompetitorKnowledge`、`TASK / DOMAIN` 分层和共享追溯契约收尾第八轮。
- 2026-06-20 live 补证已进一步说明：真实任务 `50` 在最新 backend 上完成 `extract_schema` rerun 后，后续 `analyze_competitors -> write_report -> quality_check -> rewrite_report -> quality_check_final` 可以执行到 `SUCCESS`；但任务总状态是否最终通过，已经不能再笼统归因为“搜索与采集不够好”，而需要按 3.3 主线继续拆解 extractor 与更下游节点的真实停点。
- 2026-06-21 已补齐 3.3 文档链：`ExtractionStructured.md` 作为诊断基线，`2026-06-20-extraction-structured-3.2-inheritance-baseline.md` 锁定继承前提，`2026-06-21-extraction-structured-architecture-spec.md` 收口架构规格，`2026-06-20-extraction-structured-optimization-plan.md` 已重写为 P0/P1/P2 实施总计划。当前总蓝图只记录阶段状态与边界，不复制实施计划中的任务细节。
- 2026-06-21 当日已完成 3.3 P0 自动化收口：Prompt 分层、字段级提取指引、0 字段语义重试、结构块型证据入口和 `schemaId / dimensions` 注入均已落地，并通过 extractor / analyzer 相关回归。主线下一步切到 P1 运行态边界，而不是回头继续扩大搜索采集专题。
