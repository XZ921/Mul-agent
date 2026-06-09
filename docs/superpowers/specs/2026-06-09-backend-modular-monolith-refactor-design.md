# 2026-06-09 Backend Modular Monolith Refactor Design

## 1. 文档目标

本文档用于确定后端从“按 package 松散分层”演进为“按业务域组织的模块化单体”的重构方案，目标是同时满足以下诉求：

- 工程结构清晰，模块职责边界稳定。
- 模块之间低耦合，跨模块依赖可被测试约束。
- 每个模块可以单独优化，不必牵一发而动全身。
- 支持双人并行开发，降低大类冲突概率。
- 为后续是否拆成 Maven 多模块保留空间，但当前不强制执行物理拆分。

## 2. 当前问题

当前后端已经出现明显的业务域雏形，但整体仍以单 `backend` 工程内的平铺 package 为主，典型问题如下：

- 核心流程类过大，且同时承担编排、持久化、视图聚合、外部能力调用等多种职责。
- 业务 Agent、Agent 运行时抽象、任务运行时、采集能力、报告能力之间存在直接穿透式依赖。
- 多个模块直接访问共享 `repository` 与 `entity`，导致代码边界主要依赖约定，而非结构约束。
- 目前最容易引发双人冲突的类集中在以下几个热点：
  - `AnalysisTaskService`
  - `DagExecutor`
  - `SearchExecutionCoordinator`
  - `CollectorAgent`
  - `ReportService`
- 如果直接迁移为 Maven 多模块，很容易因为边界未收敛而退化为“大 shared 包 + 到处互相引用”的假模块化。

## 3. 设计结论

本次重构采用以下结论：

- 采用“按业务域组织的模块化单体”，暂不直接拆成多个 Maven 子模块。
- 先用 package 边界 + ArchUnit 规则模拟模块边界，再逐步搬迁代码。
- 每个模块内部采用统一的 `api / application / domain / infrastructure` 分层。
- 新增 `agent-runtime`，将 Agent 运行时抽象与具体业务 Agent 解耦。
- `SchemaExtractorAgent` 与 `CompetitorAnalysisAgent` 在当前阶段先归入 `knowledge-intelligence`，不单独拆出 `analysis-intelligence`。
- 模块之间只通过稳定的 `DTO / Command / Query / Result / Port / Facade` 交互。
- 严禁跨模块直接访问他人的 `repository`、复用他人的内部 `entity`、依赖他人的基础设施实现。

## 4. 重构目标与非目标

### 4.1 重构目标

- 建立业务域级别的工程边界，而非继续沿用技术层级为主的包组织。
- 让每个业务域都可以指定 owner，并独立推进性能、规则和质量优化。
- 让任务编排、采集、知识、报告等能力可以并行演进。
- 让未来的工程拆分建立在稳定接口之上，而不是建立在目录搬迁之上。

### 4.2 非目标

- 本阶段不做微服务化。
- 本阶段不一次性重命名或搬迁所有包。
- 本阶段不将所有 DTO、Entity、Repository 统一下沉到 shared。
- 本阶段不以 Maven 多模块数量作为成功标准。

## 5. 目标模块划分

最终采用 8 个模块：

| 目标模块 | 核心职责 | 当前主要代码映射 |
| --- | --- | --- |
| `shared-kernel` | 最小共享契约、异常、通用标识与少量稳定 DTO | `common`、少量稳定 `dto/enum` |
| `governance-platform` | 配额、预算、AI 审计、安全、运行约束、底层配置边界 | `governance`、`security`、部分 `config`、部分 `llm port` |
| `agent-runtime` | Agent SPI、能力注册、执行上下文、执行返回、运行时治理 | `agent/Agent*`、`BaseAgent`、`agent/capability` |
| `task-orchestration` | 任务定义、运行、DAG 编排、恢复、回放、事件流、节点控制 | `task`、`workflow`、`event`、`controller/Task*` |
| `collection-intelligence` | 搜索计划、候选源筛选、网页抓取、采集编排、采集质量优化 | `search`、`source`、`agent/collector` |
| `knowledge-intelligence` | 知识接入、RAG、记忆、索引、结构化抽取、分析能力 | `knowledge`、`rag`、`memory`、`context`、`agent/extractor`、`agent/analyzer` |
| `report-delivery` | 报告生成、证据查询、质量复核、导出与交付 | `report`、`agent/writer`、`agent/reviewer` |
| `conversation-entry` | 对话入口、意图识别、任务表单草稿、动作翻译、解释型问答 | `conversation`、`controller/ConversationController`、`agent/conversation` |

## 6. 模块职责边界

### 6.1 shared-kernel

`shared-kernel` 只能放“跨模块稳定且不可避免共享”的内容：

- `ResultCode`
- `BusinessException`
- `ApiResponse` 类通用响应封装
- `TraceId` 等基础追踪标识
- 通用分页对象
- 少量跨模块 `Result DTO`
- 通用枚举与值对象

明确禁止进入 `shared-kernel` 的内容：

- JPA `entity`
- 具体业务 `repository`
- 任意模块的内部领域模型
- 为了省事而塞进去的临时 DTO

### 6.2 governance-platform

`governance-platform` 作为底座模块，负责：

- 配额与预算控制
- AI 调用审计
- 模型路由与安全约束
- 运行时安全校验
- 与底层运行配置相关的治理规则

该模块可以被业务域依赖，但不应反向依赖任何业务域。

### 6.3 agent-runtime

`agent-runtime` 只负责 Agent 运行时抽象，不负责承载具体业务 Agent。该模块包含：

- `Agent`
- `AgentContext`
- `AgentResult`
- `BaseAgent`
- `AgentCapability`
- `AgentCapabilityRegistry`
- 统一执行上下文与能力注册机制

该模块的价值是让 `task-orchestration` 调用的是 Agent 运行时能力，而不是直接知道具体业务 Agent 的实现细节。

### 6.4 task-orchestration

`task-orchestration` 是系统主控域，负责：

- 任务创建、预览、执行、恢复、重试、停止
- DAG 编排、节点状态推进、回放、恢复建议
- 任务事件流、进度快照、任务详情视图
- 任务生命周期上的应用编排

该模块不应直接深入其他模块的持久化层，而应通过应用接口或 port 驱动外部能力。

### 6.5 collection-intelligence

`collection-intelligence` 负责采集闭环：

- 搜索计划生成
- 搜索执行协调
- 候选源发现与过滤
- 候选验证、评分、排序
- 页面抓取、浏览器运行时、采集结果结构化
- `CollectorAgent`

该模块可以被单独优化，特别适合围绕“搜索质量、证据质量、采集稳定性”持续演进。

### 6.6 knowledge-intelligence

`knowledge-intelligence` 负责知识与分析闭环：

- 组织知识接入
- RAG 索引、切片、召回、重排
- 任务记忆写回与复用
- 结构化抽取与分析
- `SchemaExtractorAgent`
- `CompetitorAnalysisAgent`

当前阶段将抽取与分析保留在同一模块内，是为了减少迁移成本，后续若分析复杂度继续增长，再考虑独立出 `analysis-intelligence`。

### 6.7 report-delivery

`report-delivery` 负责交付闭环：

- 报告聚合与生成
- 证据查询与证据追溯
- 报告质量复核
- 导出与正式交付记录
- `ReportWriterAgent`
- `QualityReviewAgent`

该模块只消费任务、证据和知识视图，不应反向依赖 `search/source` 内部实现。

### 6.8 conversation-entry

`conversation-entry` 负责统一对话入口：

- 会话处理
- 意图识别
- 模式路由
- 表单草稿生成
- 任务动作翻译
- 解释型问答与研究型回复
- `ConversationAgent`

该模块可以调用其他模块暴露的应用接口，但不应绕过接口直接操纵运行时内部对象。

## 7. 模块内部统一分层

每个业务模块内部采用统一结构：

### 7.1 api

职责如下：

- `controller`
- 入参出参适配
- REST 与序列化边界

禁止事项如下：

- 不在 `api` 中编排核心业务流程
- 不在 `api` 中直接访问 `repository`
- 不在 `api` 中实现跨模块业务逻辑

### 7.2 application

职责如下：

- `command / query / use case`
- 模块 facade 的接口与实现
- 事务边界
- 跨领域编排，但仅基于接口

说明：

- 对外暴露给其他模块的应用接口放在 `application` 更清晰。
- `api` 只负责承接外部请求，不负责对模块外暴露内部应用能力。

### 7.3 domain

职责如下：

- 领域模型
- 规则、策略、领域服务
- 模块内部 port 接口
- 与业务含义强绑定的对象

说明：

- `domain` 不直接依赖具体基础设施实现。
- 所有关键业务规则优先下沉到 `domain`，而不是堆在大 `service` 类中。

### 7.4 infrastructure

职责如下：

- JPA repository 实现与适配
- 外部系统客户端
- 缓存、消息、搜索、浏览器、模型供应商适配器
- 持久化转换

说明：

- `infrastructure` 是实现层，不是对外契约层。
- 其他模块不允许直接依赖某模块的 `infrastructure`。

## 8. 模块依赖规则

### 8.1 顶层规则

- `shared-kernel` 不依赖任何业务模块。
- `governance-platform` 只依赖 `shared-kernel` 与底层技术实现。
- `agent-runtime` 依赖 `shared-kernel` 与必要的治理接口，不依赖具体业务 Agent。
- 业务域之间只依赖对方 `application` 暴露的稳定接口或 `domain` 中的 port 契约。

### 8.2 强制禁令

以下三条为重构硬约束，优先级高于目录命名：

- 不允许跨模块直接访问别人的 `repository`
- 不允许跨模块复用内部 `entity`
- 跨模块只传稳定的 `DTO / Command / Query / Result`

此外继续补充以下禁令：

- 不允许跨模块依赖对方 `infrastructure`
- 不允许 `controller` 直接依赖 `workflow` 等运行时内部实现
- 不允许以“临时方便”为理由直接共享领域模型

### 8.3 建议依赖方向

- `conversation-entry` 可依赖 `task-orchestration`、`knowledge-intelligence`、`report-delivery` 的应用接口。
- `task-orchestration` 可依赖 `agent-runtime` 与各业务模块暴露的应用接口。
- `collection-intelligence`、`knowledge-intelligence`、`report-delivery` 可依赖 `governance-platform`。
- 业务域默认都可以依赖 `shared-kernel`。

## 9. 包级落地策略

本阶段采用“模块化单体”，因此优先做 package 组织，而非 Maven 物理拆分。

建议 package 组织方式保持“模块根包 + 四层子包”的结构，例如：

```text
cn.bugstack.competitoragent.task.api
cn.bugstack.competitoragent.task.application
cn.bugstack.competitoragent.task.domain
cn.bugstack.competitoragent.task.infrastructure
```

其他模块可采用同样规则：

- `cn.bugstack.competitoragent.collection.*`
- `cn.bugstack.competitoragent.knowledge.*`
- `cn.bugstack.competitoragent.report.*`
- `cn.bugstack.competitoragent.conversation.*`
- `cn.bugstack.competitoragent.governance.*`
- `cn.bugstack.competitoragent.agentruntime.*`
- `cn.bugstack.competitoragent.shared.*`

说明：

- 任务域和报告域可以较平滑地从现有包迁移。
- 采集域与知识域需要整合当前分散在 `search/source/agent.*`、`knowledge/rag/memory/context` 的实现。
- 包重构优先目标是建立稳定边界，而不是追求一次性命名完美。

## 10. 现有代码映射建议

### 10.1 第一批关键大类

这些类是第一批必须重点拆解的对象：

- `AnalysisTaskService`
- `DagExecutor`
- `SearchExecutionCoordinator`
- `CollectorAgent`
- `ReportService`

原因如下：

- 它们既是高频变更点，也是跨职责堆积点。
- 它们是双人并行时最容易产生冲突的入口。
- 拆开后能最快体现模块化收益。

### 10.2 task-orchestration 映射建议

建议优先整理如下类：

- `AnalysisTaskService`
- `TaskDefinitionAppService`
- `TaskRuntimeCommandAppService`
- `TaskQueryAppService`
- `AnalysisTaskRunner`
- `TaskReplayProjectionService`
- `TaskRecoveryService`
- `RecoveryCheckpointService`
- `DagExecutor`
- `WorkflowFactory`
- `RecoveryEngine`
- `TaskEventReplayService`

拆分方向如下：

- `api`：`TaskController`、`TaskReplayController`、`TaskEventStreamController`
- `application`：任务命令、查询、回放、恢复 facade
- `domain`：任务状态机、恢复策略、计划版本规则、编排规则
- `infrastructure`：任务持久化、事件持久化、快照缓存、消息适配

### 10.3 collection-intelligence 映射建议

建议优先整理如下类：

- `SearchExecutionCoordinator`
- `CollectionTargetSelector`
- `CandidateVerifier`
- `SourceDiscoveryService`
- `SourceCandidateRanker`
- `PlaywrightPageCollector`
- `BrowserSearchRuntimeService`
- `CollectorAgent`

拆分方向如下：

- `api`：采集调试、采集洞察类接口
- `application`：搜索执行用例、采集编排用例、采集质量汇总
- `domain`：候选源评分、验证策略、选择规则、搜索计划模型
- `infrastructure`：搜索提供方、浏览器运行时、抓取器实现

### 10.4 agent-runtime 映射建议

建议从现有 `agent` 中抽取：

- `Agent`
- `AgentContext`
- `AgentResult`
- `BaseAgent`
- `AgentCapability`
- `AgentCapabilityRegistry`
- `SpringAgentCapabilityRegistry`
- `AgentExecutionRequest`
- `AgentExecutionResponse`

抽取后保留在各业务域中的具体 Agent 如下：

- `collection-intelligence`：`CollectorAgent`
- `knowledge-intelligence`：`SchemaExtractorAgent`、`CompetitorAnalysisAgent`
- `report-delivery`：`ReportWriterAgent`、`QualityReviewAgent`
- `conversation-entry`：`ConversationAgent`

### 10.5 knowledge-intelligence 映射建议

建议整理如下类：

- `KnowledgeIngestionService`
- `KnowledgeDomainService`
- `KnowledgeDocumentQueryService`
- `TaskRetrievalService`
- `TaskRetrievalIndexService`
- `TaskRerankService`
- `MemoryWritebackService`
- `MemoryFusionService`
- `AgentContextAssembler`
- `TaskRagQueryBuilder`
- `SchemaExtractorAgent`
- `CompetitorAnalysisAgent`

### 10.6 report-delivery 映射建议

建议整理如下类：

- `ReportService`
- `EvidenceQueryService`
- `ReportDiagnosisAssembler`
- `ExportPackageService`
- `ReportExportRenderer`
- `ReportWriterAgent`
- `QualityReviewAgent`

### 10.7 conversation-entry 映射建议

建议整理如下类：

- `ConversationService`
- `IntentRecognitionService`
- `ModeRouter`
- `ClarificationOrchestrator`
- `TaskActionTranslator`
- `FormDraftBuilder`
- `ConversationAgent`

## 11. 双人并行开发建议

### 11.1 第一阶段并行拆分

双人并行的第一阶段建议只选两条主线：

- 开发者 A：`task-orchestration`
- 开发者 B：`collection-intelligence`

原因如下：

- 这两条线最容易互相独立优化。
- 这两条线当前都有明显的大类和职责堆积问题。
- 它们的改造收益最直接，可尽快建立模块化重构信心。

### 11.2 共同遵守的协作边界

两人并行时共同遵守以下原则：

- 先确定模块接口，再分别进入模块内部重构。
- 任何跨模块调用先抽 `application facade` 或 `port`。
- 任何新的持久化访问只能写在本模块 `infrastructure`。
- 任何共享对象必须经过 `shared-kernel` 准入判断。

### 11.3 第二阶段协作

第一阶段稳定后再进行：

- 抽取 `agent-runtime`
- 整理 `knowledge-intelligence`
- 整理 `report-delivery`
- 整理 `conversation-entry`

这样可以避免一开始多线改造导致边界频繁变动。

## 12. 迁移计划

### 12.1 阶段 0：确定边界

- 固化本文档中的模块映射与依赖规则
- 明确 `shared-kernel` 准入清单
- 明确具体 Agent 归属

### 12.2 阶段 1：用 ArchUnit 落边界

- 为模块边界增加 ArchUnit 测试
- 先禁止跨模块 `repository` 访问
- 再禁止跨模块 `entity` 访问
- 再限制 `controller -> workflow` 等不合理依赖

### 12.3 阶段 2：先拆 task 与 collection

- `task-orchestration` 整理查询、命令、运行时应用服务
- `collection-intelligence` 整理搜索、验证、抓取、采集闭环
- 两条线只通过接口协作

### 12.4 阶段 3：抽出 agent-runtime

- 提取 Agent SPI 与能力注册
- 将业务 Agent 回归各模块
- 让 `task-orchestration` 面向运行时抽象编排

### 12.5 阶段 4：整理 knowledge、report、conversation

- 建立各自的 `api / application / domain / infrastructure`
- 清理跨模块持久化穿透
- 收敛 DTO 和应用接口

### 12.6 阶段 5：评估是否拆 Maven 多模块

满足以下条件后再考虑 Maven 多模块：

- ArchUnit 边界长期稳定
- 跨模块 DTO 数量收敛
- 主要业务模块 owner 明确
- 模块间依赖图可解释且无大规模循环

## 13. 验收标准

当以下条件满足时，说明本次重构方向成立：

- 关键业务域都具备明确 owner 和模块边界。
- 双人可以分别在 `task-orchestration` 与 `collection-intelligence` 中连续开发而不频繁冲突。
- 新增业务能力默认有明确模块归属，而不是继续塞回大 `service`。
- ArchUnit 可以阻止跨模块 `repository/entity` 穿透。
- `shared-kernel` 没有膨胀为新的大杂烩包。
- 对是否拆为 Maven 多模块的讨论建立在稳定边界基础上，而不是建立在“目录太多太乱”的直觉之上。

## 14. 风险与控制

### 14.1 风险

- `shared-kernel` 失控膨胀。
- 重构初期因为追求目录完整而引发大规模搬迁。
- 在模块接口尚未稳定前，过早拆 Maven 多模块。
- `agent-runtime` 抽取时边界过大，反而重新吸收业务逻辑。

### 14.2 控制策略

- 共享对象引入必须经过 review。
- 优先拆职责，不优先搬文件。
- 用 ArchUnit 做“失败即暴露”的结构约束。
- 保持 `agent-runtime` 只承载运行时抽象，不承载具体业务规则。

## 15. 最终结论

本项目适合采用“按业务域组织的模块化单体”作为当前阶段重构方案。

最终模块收敛为：

- `shared-kernel`
- `governance-platform`
- `agent-runtime`
- `task-orchestration`
- `collection-intelligence`
- `knowledge-intelligence`
- `report-delivery`
- `conversation-entry`

当前阶段最重要的不是马上拆物理模块，而是先建立真实的工程边界。只要严格执行以下三条，后续多人并行和局部优化就能真正成立：

- 不允许跨模块直接访问别人的 `repository`
- 不允许跨模块复用内部 `entity`
- 跨模块只传稳定的 `DTO / Command / Query / Result`

在此基础上，优先并行改造 `task-orchestration` 与 `collection-intelligence`，再逐步抽取 `agent-runtime`，最后视边界稳定度决定是否演进为 Maven 多模块。
