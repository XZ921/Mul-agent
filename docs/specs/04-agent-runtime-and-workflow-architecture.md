# 04 Agent Runtime And Workflow Architecture

## 文档目的

本文档用于定义系统如何从当前固定角色 + 固定 DAG 的实现，演进为具备规划、并行、回流和局部恢复能力的统一 Agent Runtime 与任务图执行体系。

## 本能力域在系统中的职责

本能力域负责：

1. 定义系统内部有哪些长期稳定的智能体职责。
2. 定义任务如何被规划成执行图。
3. 定义节点执行、并行分支、回流修订和人工干预的长期语义。

## 业务目标与用户价值

1. 让复杂任务不再被固定串行链路限制。
2. 让证据缺口、质量问题和人工干预能够影响后续执行路径。
3. 让恢复、重跑和复用成为系统级能力。

## 当前结论

最终 Agent 层不应是若干写死的类集合，而应是统一的 `Agent Runtime`。

核心组成长期包括：

1. `Planner Agent`
2. `Collector Agent`
3. `Extractor Agent`
4. `Analyzer Agent`
5. `Writer Agent`
6. `Reviewer Agent`
7. `Conversation Agent`
8. `Intent Router`
9. `Context Builder`
10. `Memory Manager`
11. `Tool Router`
12. `Guardrail Engine`

## 目标能力

1. 任务启动前自动生成执行图
2. 执行中可动态补图
3. 支持 map-reduce 风格并行
4. 评审失败后可回流到指定节点
5. 支持局部重跑而不是整图重跑
6. Agent 执行前后具备上下文与记忆装配能力

## 关键场景

1. 按竞品和来源类型并行采集。
2. 因关键章节缺证据而增开补证分支。
3. 因表达问题只触发重写，而不是重新采集。
4. 用户从某个节点重跑，系统自动识别受影响下游。
5. 执行失败后复用成功节点产物继续推进。

## 核心对象 / 核心模块

1. `Task Planner`
2. `Plan Graph / DAG Executor`
3. `Node Dispatcher`
4. `Context Engine`
5. `Memory Engine`
6. `Tool Orchestrator`
7. `Guardrail Engine`
8. `Recovery Engine`

## 核心流程 / 状态流 / 编排关系

### 终态流程

1. 用户定义任务。
2. `Planner Agent` 生成初始 `Plan Graph`。
3. `Dag/Graph Executor` 按依赖和并行策略分发节点。
4. 每个 Agent 执行前由 `Context Builder` 装配上下文。
5. Agent 在受控边界内调用工具，产出结构化结果并写回记忆。
6. `Reviewer Agent` 判断质量与证据覆盖。
7. 若失败，则由 `Recovery / Quality Loop` 回流到补证或重写节点。
8. 最终形成正式交付与审计数据。

### 与当前工程的演进关系

1. 当前 `DagExecutor` 是执行图引擎的第一代基础。
2. 当前多角色 Agent 是未来统一 Runtime 的能力原型。
3. 演进方向是“抽象和增强”，不是简单推倒重写。

## 关键约束与设计原则

1. `Agent 输出必须结构化`
   不能把关键节点退化成不可机读的大段文本。
2. `节点状态必须可恢复`
   节点执行语义要天然支持恢复与重跑。
3. `质量结果必须能驱动后续图`
   评审不是展示动作，而是编排动作。
4. `任务图必须允许演进`
   最终不能把所有节点都写死在静态代码里。

## 与其他能力域的边界

1. 本文档定义 Agent Runtime 与工作流，不展开数据底座，详见 `03-domain-model-and-data-architecture.md`。
2. 本文档依赖上下文、RAG 和记忆能力，详见 `05-knowledge-ingestion-rag-and-memory.md`。
3. 对话与意图入口详见 `06-conversation-intent-and-task-orchestration.md`。

## 扩展预留

1. 未来可引入更多专门化 Agent，如连接器 Agent、监控 Agent、模板 Agent。
2. 未来可支持更强的 Planner 自主性与任务图自适应生成。

## 待确认问题

1. 何时从“增强型 DAG”升级为更通用的 `Plan Graph` 执行框架。
2. 是否需要支持多任务共享子图或长期监测型循环图。

## 本阶段不约束的内容

1. 不规定当前必须一次性实现全动态规划。
2. 不规定当前必须把所有 Agent 立即抽象到统一运行时接口。
