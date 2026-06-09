# 2026-06-09 Backend Modular Monolith Refactor Design

## 1. 文档目标

本文档用于定义后端从“按 package 松散分层”演进为“按业务域组织的模块化单体”的重构方案。此次设计不仅关注目录结构，更关注真正可落地的工程边界，目标如下：

- 支持双人按业务域并行开发，尽量减少同文件冲突。
- 让每个模块可以单独优化，不必频繁触碰其他模块内部实现。
- 通过结构约束降低跨模块耦合，而不是只依赖口头约定。
- 为未来是否演进成 Maven 多模块保留空间，但当前阶段不以物理拆分为优先目标。

## 2. 当前主要问题

当前代码已经出现业务域雏形，但真正的工程边界仍然较弱，主要问题如下：

- 关键大类同时承担编排、持久化、外部调用、视图组装等多种职责。
- Agent 运行时抽象与具体业务 Agent 混在一起，导致任务编排和业务优化容易同时改动同一批类。
- `model`、`repository` 仍为全局扁平结构，跨模块直接访问实体和仓储的成本过低。
- 采集、证据、报告、知识、RAG 等能力边界部分存在“语义分开、实现混用”的问题。
- 如果直接拆成 Maven 多模块，极容易演化成“大 shared 包 + 到处互相 import”的假模块化。

当前最容易引发双人冲突的热点类包括：

- `AnalysisTaskService`
- `DagExecutor`
- `SearchExecutionCoordinator`
- `CollectorAgent`
- `ReportService`

## 3. 设计结论

### 3.1 总体结论

- 当前阶段采用“按业务域组织的模块化单体”。
- 当前阶段优先做职责拆分和结构约束，不优先做大规模文件搬迁。
- 当前阶段先用 package 边界 + ArchUnit 模拟模块边界，不直接拆成多个 Maven 子模块。

### 3.2 关键修正结论

- `agent-runtime` 不再放在后续阶段抽取，而是前置为“阶段 1 前置基线”。
- 跨模块调用只允许依赖对方 `application facade`，不允许直接依赖对方 `domain`。
- 将原本偏大的 `governance-platform` 拆分为：
  - `governance-platform`
  - `ai-platform`
- 证据能力采用明确归属：当前阶段证据归 `collection-intelligence`，`report-delivery` 只读 evidence facade。
- `knowledge-intelligence` 当前可以先承载抽取与分析，但必须设定未来拆分触发条件。

## 4. 重构目标与非目标

### 4.1 重构目标

- 建立业务域级别的清晰边界。
- 让跨模块依赖收敛到稳定应用接口。
- 让 `task-orchestration`、`collection-intelligence` 等热点领域可独立演进。
- 让后续架构演进建立在可验证的结构规则上。

### 4.2 非目标

- 本阶段不做微服务化。
- 本阶段不以 Maven 模块数量作为成功指标。
- 本阶段不一次性搬迁全部包、DTO、Entity、Repository。
- 本阶段不允许把共享问题简单转移到 `shared-kernel`。

## 5. 目标模块划分

当前阶段建议收敛为 9 个模块：

| 模块 | 核心职责 | 当前主要映射 |
| --- | --- | --- |
| `shared-kernel` | 最小共享契约、错误码、trace、通用分页、极少量稳定跨模块结果对象 | `common`、少量稳定 `dto/enum` |
| `governance-platform` | 配额、审计、策略、安全约束、运行治理 | `governance`、`security`、部分 `log` |
| `ai-platform` | 模型网关、provider、routing、prompt、embedding、rerank、AI 客户端配置 | `llm`、部分 `config` |
| `agent-runtime` | Agent SPI、执行上下文、执行结果、能力注册、运行时抽象 | `agent/Agent*`、`BaseAgent`、`agent/capability` |
| `task-orchestration` | 任务创建、执行、恢复、回放、事件流、DAG 编排、节点控制 | `task`、`workflow`、`event`、`controller/Task*` |
| `collection-intelligence` | 搜索、候选源筛选、采集、证据沉淀、采集质量优化 | `search`、`source`、`agent/collector`、`EvidenceSourceRepository` 相关能力 |
| `knowledge-intelligence` | 知识接入、RAG、记忆、上下文装配、结构化抽取、竞品分析 | `knowledge`、`rag`、`memory`、`context`、`agent/extractor`、`agent/analyzer` |
| `report-delivery` | 报告生成、质量复核、导出、交付 | `report`、`agent/writer`、`agent/reviewer` |
| `conversation-entry` | 对话入口、意图识别、表单草稿、动作翻译、解释型问答 | `conversation`、`controller/ConversationController`、`agent/conversation` |

## 6. 模块职责边界

### 6.1 shared-kernel

`shared-kernel` 只允许进入以下内容：

- 不可变值对象
- 错误码
- 通用异常
- trace / correlation id
- 通用分页对象
- 跨模块 command / result 基类
- 3 个以上模块稳定消费的极少量结果对象

明确禁令如下：

- JPA Entity 永远不能进入 `shared-kernel`
- Request / Response DTO 默认不能进入 `shared-kernel`
- 任意模块内部领域模型不能进入 `shared-kernel`
- Repository、Adapter、Service、Facade 都不能进入 `shared-kernel`

新增 shared 对象时必须补充一条说明：

- 为什么它不能归属某个业务模块

### 6.2 governance-platform

`governance-platform` 只负责治理与约束，不持有 AI 平台实现细节。范围包括：

- 配额与预算控制
- AI 调用审计
- 组织级策略
- 运行时安全约束
- URL 安全、权限、治理默认值

该模块不负责：

- 模型 provider 客户端
- prompt 资源管理
- embedding / rerank / routing 实现

### 6.3 ai-platform

`ai-platform` 负责 AI 平台能力，范围包括：

- `ModelGateway`
- provider 注册与调用
- routing policy
- prompt template
- embedding client
- rerank client
- AI 调用相关运行配置

该模块可以被业务模块依赖，但它不负责配额、组织策略与安全审批。

### 6.4 agent-runtime

`agent-runtime` 只承载 Agent 运行时抽象，不承载具体业务 Agent 逻辑。范围包括：

- `Agent`
- `AgentContext`
- `AgentResult`
- `BaseAgent`
- `AgentCapability`
- `AgentCapabilityRegistry`
- `AgentExecutionRequest`
- `AgentExecutionResponse`

该模块是阶段 1 前置基线，必须先冻结边界，再允许任务域与采集域分别重构。

### 6.5 task-orchestration

负责：

- 任务定义
- 任务执行
- DAG 编排
- 节点控制
- 恢复与回放
- 事件流
- 状态机推进

不负责：

- 搜索内部策略
- 证据验证实现
- 报告内部组装细节
- 具体 Agent SPI 设计

### 6.6 collection-intelligence

负责：

- 搜索计划与执行
- 候选源发现、评分、选择、验证
- 网页抓取与浏览器运行时
- 采集结果结构化
- 证据沉淀与 evidence facade
- `CollectorAgent`

当前阶段明确约束：

- 证据归 `collection-intelligence`
- `report-delivery` 不允许直接查 `EvidenceSourceRepository`
- 其他模块通过 evidence facade 或 query result 读取证据视图

### 6.7 knowledge-intelligence

负责：

- 知识接入
- RAG 检索链路
- 记忆写回与复用
- 任务上下文装配
- 结构化抽取
- 竞品分析
- `SchemaExtractorAgent`
- `CompetitorAnalysisAgent`

当前阶段允许抽取与分析共处一个模块，但必须设定拆分触发条件：

- 分析规则开始独立演进
- `CompetitorAnalysisAgent` 复杂度持续上升
- schema 抽取与知识检索频繁相互影响

触发条件成立后，再考虑独立出 `analysis-intelligence`。

### 6.8 report-delivery

负责：

- 报告组装
- 质量复核
- 导出
- 正式交付记录
- `ReportWriterAgent`
- `QualityReviewAgent`

该模块只读取：

- 任务视图
- knowledge 视图
- evidence facade 返回的证据视图

该模块不允许：

- 直接访问采集仓储
- 直接依赖 `search/source` 内部实现

### 6.9 conversation-entry

负责：

- 会话入口
- 意图识别
- 任务表单草稿
- 动作翻译
- 解释型问答
- `ConversationAgent`

该模块只调用其他模块暴露的应用接口，不直接操作运行时内部对象。

## 7. 模块内部统一分层

每个模块内部采用：

- `api`
- `application`
- `domain`
- `infrastructure`

### 7.1 api

只负责：

- controller
- request / response adapter
- 协议与序列化边界

### 7.2 application

负责：

- command / query / use case
- 模块 facade 接口与实现
- 事务边界
- 跨模块调用编排

约束如下：

- 跨模块调用只允许依赖对方 `application facade`
- 不允许绕过 facade 直接依赖对方 `domain`

### 7.3 domain

负责：

- 本模块规则
- 领域模型
- 领域服务
- 本模块内部 port

约束如下：

- `domain port` 由本模块定义，供外部 adapter 实现
- 其他模块不应直接 import 本模块 `domain`
- 禁止将 `domain` 当作另一种共享模型

### 7.4 infrastructure

负责：

- repository 适配
- 外部客户端
- 缓存、消息、浏览器、AI 客户端实现
- 持久化转换

## 8. 模块依赖规则

### 8.1 顶层依赖规则

- `shared-kernel` 不依赖业务模块
- `governance-platform` 只依赖 `shared-kernel`
- `ai-platform` 可依赖 `shared-kernel`，但不依赖业务域
- `agent-runtime` 可依赖 `shared-kernel` 与必要平台接口
- 业务模块之间只依赖对方 `application facade`

### 8.2 强制禁令

- 不允许跨模块直接访问别人的 `repository`
- 不允许跨模块复用内部 `entity`
- 不允许跨模块直接 import 对方 `domain`
- 不允许跨模块依赖对方 `infrastructure`
- `controller` 不允许直接依赖 `workflow` 等运行时内部实现

### 8.3 Evidence 依赖规则

- evidence 当前归 `collection-intelligence`
- 其他模块读取证据只能通过 evidence facade
- `report-delivery` 不允许直接依赖 `EvidenceSourceRepository`

## 9. 包级落地策略

当前阶段先做“职责拆分”，再做“包搬迁”。优先级如下：

1. 删除冗余代码
2. 抽 `facade / port`
3. 增加 ArchUnit
4. 固化跨模块 DTO
5. 再搬 package

第一轮不要追求大规模改包名，因为这会显著提高 merge 冲突概率。

## 10. 当前扁平 model / repository 的过渡策略

当前项目的 `model`、`repository` 是全局扁平结构，因此不能用“理想规则”直接硬切。必须采用过渡策略：

### 阶段 A：先标记违规，允许白名单

- ArchUnit 先识别跨模块 `repository / entity` 访问
- 对现有历史依赖设置显式白名单
- 白名单必须写明原因和目标删除阶段

### 阶段 B：先建模块级 facade / query result

- 用模块 facade 和 query result 替代直接读取 `TaskNode`、`AnalysisTask`、`Report`、`EvidenceSource`、`KnowledgeDocument` 等实体

### 阶段 C：repository 逐步内聚到模块 infrastructure

- 历史 `repository` 按归属逐步迁回业务域
- 迁移时同步收紧调用入口

### 阶段 D：删除白名单

- 历史跨模块直连被替换完毕后，删除对应白名单

## 11. 关键代码映射建议

### 11.1 agent-runtime 前置基线

必须先抽出以下类并冻结接口：

- `Agent`
- `AgentContext`
- `AgentResult`
- `BaseAgent`
- `AgentCapability`
- `AgentCapabilityRegistry`
- `SpringAgentCapabilityRegistry`
- `AgentExecutionRequest`
- `AgentExecutionResponse`

冻结完成后：

- `task-orchestration` 只依赖 `agent-runtime`
- 各业务 Agent 再回归各自模块

### 11.2 task-orchestration

优先整理：

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

### 11.3 collection-intelligence

优先整理：

- `SearchExecutionCoordinator`
- `CollectionTargetSelector`
- `CandidateVerifier`
- `SourceDiscoveryService`
- `SourceCandidateRanker`
- `PlaywrightPageCollector`
- `BrowserSearchRuntimeService`
- `CollectorAgent`
- 证据 facade 与 query result

### 11.4 knowledge-intelligence

优先整理：

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

### 11.5 report-delivery

优先整理：

- `ReportService`
- `EvidenceQueryService`
- `ReportDiagnosisAssembler`
- `ExportPackageService`
- `ReportExportRenderer`
- `ReportWriterAgent`
- `QualityReviewAgent`

### 11.6 conversation-entry

优先整理：

- `ConversationService`
- `IntentRecognitionService`
- `ModeRouter`
- `ClarificationOrchestrator`
- `TaskActionTranslator`
- `FormDraftBuilder`
- `ConversationAgent`

## 12. 双人并行开发策略

### 12.1 前置基线

第一步不是直接拆 task 和 collection，而是先冻结 `agent-runtime` 边界。

原因如下：

- `DagExecutor` 已依赖 `AgentCapabilityRegistry`
- `CollectorAgent`、`ReportWriterAgent`、`QualityReviewAgent` 等都绕不开 Agent SPI
- 如果不先冻结 Agent 运行时接口，两个人重构时仍会同时改 Agent 相关抽象

### 12.2 第一条并行主线

在 `agent-runtime` 基线稳定后，双人第一轮并行建议如下：

- 开发者 A：`task-orchestration`
- 开发者 B：`collection-intelligence`

### 12.3 第二条并行主线

第一轮稳定后再推进：

- `knowledge-intelligence`
- `report-delivery`
- `conversation-entry`

## 13. 迁移计划

### 阶段 0：确认模块边界

- 固化模块划分
- 固化 evidence 归属
- 固化 `shared-kernel` 准入标准

### 阶段 1：agent-runtime 前置基线

- 抽出 Agent SPI 与能力注册
- 冻结 `Agent / AgentContext / AgentResult / Registry` 边界
- 让业务 Agent 不再承担运行时抽象职责

### 阶段 2：ArchUnit 建立硬约束

至少覆盖以下 4 类禁令：

- `repository`
- `entity`
- `infrastructure`
- `controller`

当前阶段允许显式白名单。

### 阶段 3：双线并行拆分 task 与 collection

- `task-orchestration`：拆命令、查询、状态机、回放、恢复
- `collection-intelligence`：拆验证、选择、补源、证据 facade

### 阶段 4：收口 knowledge / report / conversation

- 统一 facade
- 替换跨模块实体读取
- 收敛 query result

### 阶段 5：评估是否拆 Maven 多模块

只有在模块边界稳定后再考虑。

## 14. 可执行验收标准

### 14.1 结构验收

- ArchUnit 至少覆盖 `repository / entity / infrastructure / controller` 四类禁令
- 历史跨模块访问有显式白名单，并记录计划删除阶段
- `report-delivery` 不再直接访问证据仓储

### 14.2 类级验收

- `AnalysisTaskService` 行数下降到 200 行以内，只保留 facade / 治理入口
- `DagExecutor` 不再包含搜索事件 payload 构造、动态计划追加细节
- `SearchExecutionCoordinator` 拆出候选验证、目标选择、补源策略
- `ReportService` 拆出报告组装、覆盖率诊断、导出逻辑

### 14.3 协作验收

- 双线分支连续合并 3 次无同文件冲突
- 新增跨模块调用默认落在 facade，而不是直接读取实体

## 15. 风险与控制

### 15.1 主要风险

- `shared-kernel` 膨胀为大杂烩
- `governance-platform` 与 `ai-platform` 边界重新混回去
- `knowledge-intelligence` 长期不拆，变成第二个大模块
- 迁移初期为了追求“目录漂亮”而大规模改包名

### 15.2 控制策略

- shared 对象新增必须说明归属理由
- `agent-runtime` 必须前置冻结
- evidence 必须先定归属再拆报告
- 第一轮优先拆职责，不优先搬文件
- 为 `knowledge-intelligence` 记录未来拆分触发条件

## 16. 最终结论

当前最稳妥的方案是：

- 采用模块化单体
- 先冻结 `agent-runtime`
- 先用 ArchUnit + 白名单建立过渡边界
- 先让 `task-orchestration` 与 `collection-intelligence` 双线并行
- 证据归 `collection-intelligence`
- 将治理与 AI 平台拆边界

最终模块收敛为：

- `shared-kernel`
- `governance-platform`
- `ai-platform`
- `agent-runtime`
- `task-orchestration`
- `collection-intelligence`
- `knowledge-intelligence`
- `report-delivery`
- `conversation-entry`

只要严格执行以下规则，双人并行和模块独立优化才能真正成立：

- 跨模块调用只依赖 `application facade`
- 不允许跨模块直接访问 `repository`
- 不允许跨模块复用内部 `entity`
- 不允许跨模块直接 import 对方 `domain`
