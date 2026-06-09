# 03 Domain Model And Data Architecture

## 文档目的

本文档用于定义系统长期稳定的核心数据对象、对象关系、状态流和可追溯原则，为业务与技术设计提供统一数据语言。

## 本能力域在系统中的职责

本能力域负责定义：

1. 什么是系统里的核心事实对象。
2. 它们如何关联成任务、证据、报告、对话和审计体系。
3. 哪些数据是长期稳定底座，哪些是可扩展运行时对象。

## 业务目标与用户价值

1. 让系统输出可审计、可追溯、可恢复。
2. 让后续新增能力能够在统一对象体系下持续扩展。
3. 避免报告文本成为唯一业务资产。

## 当前结论

最终系统的核心数据对象至少包括：

1. `Task`
2. `TaskPlan`
3. `TaskNode`
4. `EvidenceSource`
5. `KnowledgeDocument`
6. `CompetitorKnowledge`
7. `QualityCheckpoint`
8. `RevisionPlan`
9. `AgentExecutionLog`
10. `ToolExecutionTrace`
11. `MemorySnapshot`
12. `ConversationSession`
13. `IntentDecision`
14. `FormDraft`
15. `RetrievalChunk`
16. `RetrievalIndex`
17. `ExportArtifact`

## 目标能力

1. 事实层与交付层解耦
2. 运行态对象与长期沉淀对象分层
3. 证据、RAG、记忆、对话与审计数据可共存
4. 为未来新增任务类型保留统一数据边界

## 关键场景

1. 一个任务拥有多个计划版本与节点执行历史。
2. 一个报告章节可回指多个证据来源。
3. 一段对话既影响表单草稿，也可能触发任务动作。
4. 一条 RAG 召回片段最终仍要能回指到文档或来源。
5. 一次重跑或恢复需要复用已有成功产物并保留审计链。

## 核心对象 / 核心模块

### 任务与计划对象

1. `Task`
   表示用户定义的一次研究任务。
2. `TaskPlan`
   表示某次任务对应的规划版本。
3. `TaskNode`
   表示规划图中的节点及其执行状态。

### 知识与证据对象

1. `EvidenceSource`
   表示可回指的原始来源。
2. `KnowledgeDocument`
   表示已采集、清洗和解析后的文档载体。
3. `CompetitorKnowledge`
   表示抽取后的结构化业务知识。
4. `RetrievalChunk`
   表示用于召回的知识分片。
5. `RetrievalIndex`
   表示检索索引和分层知识底座。

### 质量与审计对象

1. `QualityCheckpoint`
   表示质量检查产物。
2. `RevisionPlan`
   表示系统输出的修订动作建议。
3. `AgentExecutionLog`
   表示 Agent 执行留痕。
4. `ToolExecutionTrace`
   表示工具调用轨迹。
5. `ExportArtifact`
   表示正式导出物。

### 对话与记忆对象

1. `ConversationSession`
   表示任务型对话上下文载体。
2. `IntentDecision`
   表示一次自然语言请求的意图判定。
3. `FormDraft`
   表示任务创建期的结构化草稿。
4. `MemorySnapshot`
   表示短期或长期记忆快照。

## 核心流程 / 状态流 / 编排关系

1. `Task` 创建后产生 `TaskPlan`。
2. `TaskPlan` 拆解为多个 `TaskNode`。
3. 节点执行产生 `EvidenceSource`、`KnowledgeDocument`、`CompetitorKnowledge` 等对象。
4. `Analyzer`、`Writer`、`Reviewer` 基于知识与证据对象生成报告、质量检查和修订计划。
5. `ConversationSession` 与 `IntentDecision` 会影响 `FormDraft` 或 `Task` 动作。
6. `MemorySnapshot` 与 `RetrievalIndex` 为跨节点和跨任务复用提供基础。

## 关键约束与设计原则

1. `Evidence 是事实底座`
   报告不是底层事实，证据与知识对象才是。
2. `RAG Chunk 不是最终事实`
   召回片段只是检索载体，必须能够回指到文档或来源。
3. `对话不是普通聊天记录`
   对话是任务上下文的一部分，需要纳入正式数据模型。
4. `审计对象应一等公民化`
   意图判定、工具调用和 AI 调用都应成为正式对象或等价持久化数据。

## 与其他能力域的边界

1. 本文档只定义对象边界，不展开工作流执行语义，详见 `04-agent-runtime-and-workflow-architecture.md`。
2. RAG 与记忆的具体分层能力详见 `05-knowledge-ingestion-rag-and-memory.md`。
3. 质量对象的业务语义详见 `09-quality-evidence-and-audit.md`。

## 扩展预留

1. 未来可加入权限、组织空间、连接器授权和资产库对象。
2. 未来可引入“长期监测任务快照”“订阅规则”“通知事件”等对象。

## 待确认问题

1. 是否需要把 `TaskPlan` 与 `TaskBlueprint` 分离成“长期模版”和“单次实例”两层。
2. 是否需要为“用户提供资料”和“AI 发现资料”使用更严格的来源类别模型。

## 本阶段不约束的内容

1. 不强制要求当前数据库已经具备全部终态表结构。
2. 不规定最终必须采用单库还是多存储形态。
