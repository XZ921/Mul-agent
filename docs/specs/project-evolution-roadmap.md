# 项目演进路线图

> 从 git 历史、specs、plans、progress 文档中还原的全周期升级路径。

---

## 总览

```
2026-05-29 ──────────────────────────────────────────────────────→ 2026-06-25

  Phase 1-5          2.1.2          3.2              3.3           3.4
  基建期            任务编排      搜索与采集        提取结构化     Agent 协作编排
  ──────────→      ──────→      ──────────→       ────────→     ──────────────→
  造零件            定产线        第一条深度链路    第二条链路      跨链路横切层
```

每一次升级都回答了同一个问题，但答案逐层加深：

| 阶段 | 回答的问题 |
|------|-----------|
| Phase 1-5 | "系统有哪些零件？"（Agent 能力模块、DAG 执行、RAG、会话） |
| 2.1.2 | "零件怎么串成产线？"（任务定义→计划→执行→回放） |
| 3.2 | "单条链路能走多深？"（搜索与采集 9 轮迭代） |
| 3.3 | "链路之间怎么接？"（提取→分析→写作 下游消费协议） |
| 3.4 | "谁在上面看全局？"（Orchestrator 协作决策层） |
| **未来** | **"产线能自己变吗？"**（动态编排、Agent 协商） |

---

## 第一阶段：基建期（Phase 1-5，约 5/29 – 6/11）

```
Phase 1: Agent 运行时
  Collector / Extractor / Analyzer / Writer / Reviewer 五角色
  DagExecutor 并发调度、节点状态、ForkJoinPool

Phase 2: 架构边界
  ArchUnit 包依赖规则、白名单机制、模块边界锁

Phase 3a: 编排切换
  从自由调度切换到串行固定计划执行

Phase 3b: 证据边界
  EvidenceFragment / SectionEvidenceBundle 共享追溯契约
  collection evidence 边界收口

Phase 4a: RAG / 检索
  Task RAG MVP、检索门面边界、知识摄取

Phase 4b: 报告 / 会话
  报告会话边界、对话统一入口雏形

Phase 5: 模块化重构
  后端模块化单体设计、包结构重组
```

**关键产出：** 5 个 Agent 能力模块 + DAG 执行底座 + RAG + 会话入口。系统**能跑**了，但各模块之间靠隐式约定协作，没有统一的协作协议。

**核心问题（当时未解决）：**
- Reviewer 既判断质量又决定编排动作（`RevisionDirective.orchestrationAction`）
- 编排逻辑分散在 7 个不同位置
- `sourceUrls` 红线没有跨链路强制执行

---

## 第二阶段：任务编排契约（2.1.2，6/11 – 6/12）

```
commit: 030723e (6/11) 总蓝图设计
commit: 8aeed2d (6/12) 2.1.2 完成
```

**标志性事件：** [2026-06-11-business-landscape-and-optimization-roadmap-design.md]() 总蓝图发布。

这是整个项目最重要的**认知拐点**——系统不再被理解为"若干 Agent 的堆叠"，而是：

```
9 条主业务链路 + 4(→5) 个业务执行引擎 + 6 个平台底座
```

同时建立了流程纪律：`诊断 → 方案 → 实施 → 实链验证`，每个环节有明确的通过条件，不再"哪个类坏了修哪个类"。

**落地内容：**
- `TaskDraft → TaskDefinition → ExecutionPlanDefinition → WorkflowPlan` 正式链路
- 预览态 / 运行态分离（`TASK_PLAN_PREVIEW_V1` / `TASK_NODE_RUNTIME_V1`）
- rerun / resume 语义确定
- 自动化测试 + dev live 验证

**关键认知升级：** 从"写代码"到"管链路"——每条链路的四阶段状态可见、可追踪。

---

## 第三阶段：搜索与采集深度优化（3.2，6/12 – 6/18）

```
commit: 7ae94b6 (6/12)  首轮搜索与采集迭代
commit: b573952 (6/16)  第四轮
commit: 14770e3 (6/16)  第四轮
commit: 2d9cee7 (6/17)  第五轮
commit: 087f933 (6/17)  第六轮（family-first discovery）
commit: d923c94 (6/17)  第七轮（RSS owner 收敛）
commit: ad11f45 (6/18)  第八轮（site discovery / deep collection）
```

**这是项目的第一条"深度链路"**——总共 9 轮迭代（Wave 1-9），从止血到架构重构：

```
Wave 1-3: 阻塞修复（执行真相收口、质量止血、连续性事实源）
Wave 4:   自动化契约（attemptedTargets、discardedCandidates、replay timeline）
Wave 5:   对象瘦身
Wave 6:   统一 discovery 边界（Source Family Catalog、family-first）
Wave 7:   RSS owner 正式接入
Wave 8:   JinaReader + Playwright 双路径网页采集
Wave 9:   collectionAudit / replayTimeline / checkpoint 闭环
```

**关键架构产出：** [2026-06-17-search-and-collection-architecture-design.md]() — "家族驱动的分层采集架构"

核心洞察：`source family` 才是业务语义，`PUBLIC_SEARCH` 只是发现工具层。这条原则后来影响了 3.3 和 3.4 的设计——把"来源"提升为一等概念。

**关键认知升级：** 单条链路可以挖得很深，但深度优化会暴露**跨链路的协议缺口**——采集的 `qualitySignals / structuredBlocks / sourceUrls` 需要被下游正式消费。

---

## 第四阶段：提取结构化 + 下游消费协议（3.3，6/19 – 6/23）

```
commit: ce50dbb (6/19)  3.3 启动
commit: add87de (6/20)  第八轮第一阶段
commit: e719890 (6/21)  P0 自动化收口
commit: 8c0c91e (6/22)  P1/P2 持续推进
commit: 5ded0f5 (6/23)  第三轮 live 验证
```

**关键架构产出：** [2026-06-21-extraction-structured-architecture-spec.md]()

核心设计：
- `DownstreamEvidenceView`：统一的下游证据消费接口
- `TASK` vs `DOMAIN` 快照分层：任务现场抽取结果不再伪装成领域记忆
- `0` 业务字段阻断：空抽取结果必须显式失败，不能静默传递

**关键认知升级：** 链路不是独立的——3.3 的改造直接证明了"上游的输出契约就是下游的输入契约"。提取做不到的事，分析就会瞎猜；分析瞎猜的结论，Writer 就会写出不可追溯的报告。

这也直接催生了 3.4 的需求：需要有一个横跨链路的协调者。

---

## 第五阶段：Agent 协作编排层（3.4，6/23 – 6/25 进行中）

```
commit: 5ded0f5 (6/23)  P0 规格冻结 + P1 终审回流 MVP
commit: a669f37 (6/24)  P2 前置协作规划
commit: cc24e45 (6/24)  P3-1 Analyzer 缺口决策
commit: 13413ad (6/24)  P3-2 Writer 引用缺口
commit: f3645f5 (6/25)  P3-3 Conversation 动作预览
待执行:                 P3-4 Citation Agent
```

**这是项目当前的最高层级**——不是第十条业务链路，而是横跨三条执行引擎的协作决策层。

**关键架构产出：** [2026-06-23-agent-collaboration-orchestration-architecture-spec.md]()

核心设计决策：
```
Orchestrator 负责协作规划和反馈决策
DAG 负责可恢复执行
Evidence 契约负责无幻觉追溯
```

分阶段落地：
```
P0: 规格与契约冻结          ✅  12 个核心契约全部定义
P1: 终审失败回流 MVP        ✅  动态补图从 Reviewer 指令升级为 Orchestrator 决策
P2: 前置协作规划 + 证据缺口  ✅  CollaborationPlan → InitialPlanReview → DAG 映射
P3-1: Analyzer 缺口决策    ✅  分析缺口接入 AgentSuggestion → OrchestrationDecision
P3-2: Writer 引用缺口      ✅  章节引用缺口接入同一协作链路
P3-3: Conversation 预览    ✅  对话入口展示 OrchestrationDecision
P3-4: Citation Agent      ←  当前待执行
```

**关键认知升级：** 协作编排不是一条新链路，而是一个横切层。它的边界由三件事定义：
1. Reviewer 只输出质量事实，Orchestrator 输出编排决策（职责拆分）
2. 所有新协议必须保留 `sourceUrls` 或显式缺口状态（跨链路红线）
3. 固定 DAG 模板，不自由生成节点（安全围栏）

---

## 升级规律的总结

从五个阶段的演进中能看出三条规律：

### 1. 每次升级都是"先立后破"

```
Phase 1-5: 先把零件造出来，能跑就行
2.1.2:     用总蓝图把隐式认知显式化（9 链路 + 5 引擎 + 6 底座）
3.2:       在一条链路上证明"深度优化"的方法论有效
3.3:       在第二条链路上证明"链路间消费协议"的必要性
3.4:       当多条链路都指向同一类编排问题时，把横切层抽出来
```

没有一步是推倒重来，每次都是在既有基础上加一层抽象。

### 2. 引擎升级靠链路诊断驱动，不等链路全绿

总蓝图 Section 4 的核心逻辑：

> 执行引擎的优化需求，是当多条链路的诊断共同指向同一类引擎问题时，才逐步浮现并收口。

这条规则已经被验证过一次：3.4 的启动不是因为所有链路都做好了，而是因为 3.2（采集）+ 3.3（提取）两条深度链路都暴露了同一类问题——"Agent 产出建议后谁来决定下一步"。

### 3. 从纵到横，从链路到引擎

```
Phase 1-5    →   纵向：造零件
2.1.2        →   纵向：定产线模板
3.2          →   纵向：第一条深度链路
3.3          →   纵向：第二条 + 下游消费
3.4          →   横向：协作编排横切层 ← 当前在这里
────────────────────────────────────────────
未来 3.5      →   纵向：补诊断空白链路
未来 4.x      →   横向：真正的架构重构
```

当前系统正处于**第一个横切层刚建立**的节点——纵向上有 3 条链路到位（采集、提取、编排），还有 4 条全白（分析推理、报告写作、对话协同、交付审计）。

---

## 下一步

```
当前 ──→ 完成 P3-4（Citation Agent）
              │
              ▼
        3.5 补诊断 ──→ AnalysisReasoning / ReportWriting /
                       ConversationCollaboration / DeliveryAndAudit
                      各写诊断文档，不需要全实现
              │
              ▼
        找收敛点 ──→ 多份诊断是否指向同一类引擎问题？
              │
              ▼
        4.x 架构重构 ──→ 从固定 DAG 到真正的 AI 驱动协作
```

**核心原则：不需要等 3.5 全部优化完。诊断先于实现。多个诊断收敛时，就是架构重构的信号。**
