# 11 Technical Foundation And Platform Architecture

## 文档目的

本文档用于补齐系统从“业务蓝图”走向“企业级可生产运行系统”所必须具备的工程底座视角，定义多智能体调度系统在异步解耦、缓存与并发控制、实时推送、可观测性、AI 网关治理与恢复能力方面的长期稳定架构。

本文档关注的是：

1. 系统如何稳定运行，而不只是业务链路如何成立。
2. 长耗时 Agent 工作流如何被解耦、削峰、恢复与追踪。
3. 前端如何实时观察任务状态，而不是依赖高成本轮询。
4. 平台如何在大模型、工具调用、消息系统和任务恢复之间建立统一治理边界。

## 本能力域在系统中的职责

本能力域负责：

1. 定义企业级 Agent Operating System 的底层平台组件分层。
2. 定义任务执行事件、状态推进、恢复语义和实时回传的基础设施载体。
3. 定义跨 Agent、跨节点、跨外部依赖的可靠性、并发控制与容灾机制。
4. 定义与 `Agent Runtime`、`infra-ai`、质量审计体系之间的工程协作边界。

## 业务目标与用户价值

1. 让长耗时研究任务从“同步阻塞流程”升级为“可排队、可恢复、可追踪”的生产级异步系统。
2. 让用户可以低延迟观察 Agent 思考过程、节点执行进度和错误原因。
3. 让平台在高并发、多任务、多模型供应商场景下保持稳定与可控成本。
4. 让故障排查从“人工翻日志猜问题”升级为“跨任务、跨节点、跨模型调用可追踪”的工程体系。

## 当前结论

企业级多智能体系统不能只依赖“业务 DAG + 数据库存状态”的轻量实现。

长期稳定蓝图应明确形成以下技术底座：

1. `RocketMQ` 承载任务事件流、长耗时节点异步调度、削峰与死信隔离。
2. `Redis` 承载中间态缓存、热点数据保护、幂等控制和分布式锁。
3. `SSE` 承载任务进度、日志、思考过程和人工干预结果的单向实时推送。
4. `Observability Platform` 承载全链路 Trace、Metric、Log 与告警。
5. `Model Gateway` 承载大模型统一路由、限流、熔断、降级与成本治理。
6. `Workflow Persistence And Recovery Layer` 承载事件落库、状态快照、恢复点和任务回放。

这些底座与业务能力同等重要，且应被视为长期稳定骨架，而不是后期补丁。

## 目标能力

1. 长耗时抓取、抽取、分析、写作、质检节点全面异步化。
2. 节点状态推进基于事件驱动而非大量同步串联调用。
3. 任务执行进度、日志和 Agent 输出支持秒级实时透传到前端。
4. 系统支持节点幂等执行、重复消费防护和局部恢复。
5. 系统支持高并发下的热点保护、缓存回源保护和分布式锁控制。
6. 模型调用、工具调用、消息消费和任务执行具备统一可观测性。
7. 平台具备 `DLQ`、熔断、降级、补偿和人工介入能力。

## 技术底座全景图

### 分层视图

长期建议形成如下分层：

1. `Interaction Layer`
   负责前端工作台、统一对话入口、`SSE` 实时事件订阅。
2. `Task Control Layer`
   负责任务 API、任务状态查询、人工干预命令、进度聚合。
3. `Agent Runtime Layer`
   负责 `Planner`、`Collector`、`Extractor`、`Analyzer`、`Writer`、`Reviewer` 等 Agent 编排与上下文装配。
4. `Workflow Orchestration Layer`
   负责 `DAG / Plan Graph` 执行、节点状态机、恢复点、事件发布与消费。
5. `Platform Foundation Layer`
   负责 `RocketMQ`、`Redis`、`SSE Hub`、可观测性、模型网关、配置中心与告警。
6. `Persistence Layer`
   负责任务主表、节点状态表、事件表、审计表、证据表、日志索引与缓存副本。

### 长期稳定原则

1. `执行与传输分离`
   节点业务逻辑不直接承担跨组件通知职责，状态推进通过统一事件机制传播。
2. `状态与日志分离`
   任务最终状态进入权威存储，过程日志与推送通道允许独立优化。
3. `实时展示与持久化分离`
   `SSE` 负责快速送达，事件存储负责断线补偿和审计回放。
4. `业务幂等优先于基础设施重试`
   不允许把“消息至少一次投递”直接暴露为业务重复执行风险。

## 核心基础设施设计

### 1. 异步解耦与削峰：RocketMQ

#### 1.1 引入目标

`RocketMQ` 主要解决以下问题：

1. 页面抓取、搜索补源、`LLM` 分析与报告生成天然是长耗时任务，不适合同步阻塞 HTTP 请求。
2. `DAG` 节点之间目前容易形成强耦合同步调用，导致局部失败放大为整链路抖动。
3. 高峰期多个任务同时触发时，需要具备队列削峰与消费隔离能力。

#### 1.2 建议承载的事件类型

建议把以下事件正式化：

1. `TaskCreated`
2. `PlanGenerated`
3. `NodeReady`
4. `NodeStarted`
5. `NodeHeartbeat`
6. `NodeCompleted`
7. `NodeFailed`
8. `NodeRetryScheduled`
9. `NodeCompensationRequested`
10. `TaskProgressUpdated`
11. `HumanInterventionRequested`
12. `HumanInterventionResolved`
13. `TaskFinished`
14. `TaskTerminated`

#### 1.3 主题与消费组规划原则

长期不建议按“单个 Agent 一个随意 Topic”粗放设计，而应按职责分层：

1. `task-lifecycle-topic`
   承载任务级生命周期事件。
2. `workflow-node-topic`
   承载节点状态推进和调度事件。
3. `agent-execution-topic`
   承载具体 Agent 执行请求。
4. `task-progress-topic`
   承载进度快照与前端订阅源数据。
5. `human-intervention-topic`
   承载人工介入、恢复、重跑和审批类事件。

消费组建议按执行责任隔离，例如：

1. `workflow-dispatcher-group`
2. `collector-agent-group`
3. `analyzer-agent-group`
4. `writer-agent-group`
5. `progress-aggregator-group`
6. `recovery-engine-group`

#### 1.4 节点状态流转模型

节点长期建议采用统一状态机：

1. `PENDING`
2. `READY`
3. `DISPATCHED`
4. `RUNNING`
5. `WAITING_RETRY`
6. `WAITING_INTERVENTION`
7. `SUCCEEDED`
8. `FAILED`
9. `COMPENSATED`
10. `SKIPPED`

状态推进原则：

1. 只有 `Workflow Orchestration Layer` 有权更新节点权威状态。
2. Agent 执行器只上报执行结果事件，不直接改写最终状态。
3. 下游节点的 `READY` 必须建立在上游权威状态确认之后。

#### 1.5 重试与 DLQ 容错策略

对异步任务，必须把“可重试失败”和“不可重试失败”明确区分：

1. `可重试失败`
   包括模型超时、瞬时网络错误、限流退避、页面短时不可访问。
2. `不可重试失败`
   包括输入结构非法、关键配置缺失、权限被拒绝、页面永久 `404`、业务前置条件不满足。

`DLQ` 策略建议：

1. 同一消息超过最大重试次数后进入 `DLQ`。
2. `DLQ` 中必须保留原始事件、任务 ID、节点 ID、错误分类、最近一次堆栈摘要和重试历史。
3. `Recovery Engine` 必须支持基于 `DLQ` 触发三类动作：
   1. 人工确认后重投。
   2. 修改配置后补偿执行。
   3. 标记为不可恢复并推动任务进入降级完成或失败终态。

#### 1.6 与 DAG / Plan Graph 的关系

长期形态不是“MQ 替代 DAG”，而是：

1. `DAG / Plan Graph` 定义依赖关系与执行语义。
2. `RocketMQ` 提供状态传播、异步调度与执行隔离。
3. `Workflow Persistence And Recovery Layer` 提供权威状态与恢复基线。

三者共同构成生产级编排底座。

### 2. 多级缓存与并发控制：Redis

#### 2.1 引入目标

`Redis` 主要解决以下问题：

1. 热点任务详情、节点进度、报告诊断结果被前端频繁访问时的数据库压力。
2. 长耗时节点中间态重复计算导致的成本放大。
3. 多实例调度器并发推进同一 `DAG` 节点时的重复执行与状态覆盖。

#### 2.2 建议缓存对象

长期建议至少缓存以下对象：

1. `TaskProgressSnapshot`
   用于前端详情页和 `SSE` 断线重连快速补齐。
2. `NodeExecutionContext`
   用于节点恢复、重试和上下文复用。
3. `SearchAndCollectionIntermediateResult`
   用于防止搜索与采集链路重复消耗外部资源。
4. `ReportDiagnosisView`
   用于高频查看的报告诊断聚合结果。
5. `AgentOutputDraft`
   用于长文本流式生成中的中间态暂存。

#### 2.3 防击穿与中间态保护

缓存策略建议：

1. `Cache Aside + Logical Expire`
   适用于任务详情、报告视图等读多写少聚合对象。
2. `Single Flight / Mutex Rebuild`
   热点 Key 过期时只允许一个实例回源重建。
3. `Jitter TTL`
   防止大量 Key 同时失效导致雪崩。
4. `Stale-While-Revalidate`
   对进度类弱一致展示允许短时间返回旧值，同时后台异步刷新。

中间态缓存原则：

1. 中间态缓存必须带任务 ID、节点 ID、版本号，避免旧结果覆盖新执行。
2. 中间态缓存必须可被显式失效，不能只依赖 `TTL` 被动淘汰。
3. 涉及 `sourceUrls`、质量诊断、执行计划的关键聚合对象必须保留版本来源。

#### 2.4 分布式锁与幂等控制

对于复杂 `DAG` 调度，长期需要两类控制：

1. `调度锁`
   确保同一时刻只有一个调度器实例推进某个节点从 `READY` 到 `DISPATCHED`。
2. `执行锁`
   确保同一节点不会被多个消费者重复真正执行。

建议锁键模型：

1. `dag:schedule:task:{taskId}:node:{nodeId}`
2. `dag:execute:task:{taskId}:node:{nodeId}`

建议同时配套：

1. `Idempotency Key`
   基于 `taskId + nodeId + attemptNo + version` 生成。
2. `Compare-And-Set`
   节点状态更新必须校验版本，防止后写覆盖先写。
3. `Lease + Heartbeat`
   长任务执行锁必须支持续约，避免锁自然过期后出现并发重入。

#### 2.5 Redis 在本系统中的边界

1. `Redis` 用于高频读、短期中间态、并发控制。
2. `Redis` 不是任务权威状态存储。
3. 任何恢复、审计、最终状态判断都不能只依赖 `Redis`。

### 3. 单向实时数据流：SSE

#### 3.1 结论

长期建议以 `SSE` 彻底替换当前任务观察场景中的 HTTP 轮询。

原因包括：

1. 任务执行是典型“服务端持续产出、客户端被动接收”的单向流。
2. 本系统当前与中长期的任务观察、执行日志、思考过程透传，本质上都属于单向事件流，`SSE` 在协议语义上天然匹配，不引入 `WebSocket`。
3. 与轮询相比，`SSE` 可以显著降低无效请求和状态感知延迟。

#### 3.2 建议推送事件模型

建议统一输出以下 `SSE` 事件：

1. `task-status`
2. `task-progress`
3. `node-status`
4. `node-log`
5. `agent-thought`
6. `quality-diagnosis`
7. `intervention-requested`
8. `intervention-resolved`
9. `task-finished`

#### 3.3 与 Agent 输出规范的衔接

`SSE` 不是单纯传字符串，而应传结构化 payload，长期与 Agent 运行时输出规范对齐，例如：

1. `currentStage`
2. `completedSteps`
3. `totalSteps`
4. `stepStatus`
5. `message`
6. `nodeId`
7. `taskId`
8. `eventTime`

这样前端既能显示自然语言日志，也能稳定渲染：

1. 当前阶段
2. 已完成步骤占比
3. 剩余步骤
4. 执行状态

#### 3.4 断线重连与补偿策略

`SSE` 长期必须支持：

1. `Last-Event-ID`
   客户端断线后带上最后事件游标重连。
2. `Progress Snapshot`
   重连时先从 `Redis / 持久化层` 返回最新快照。
3. `Event Replay Window`
   在限定窗口内从事件存储回放丢失事件。

原则上：

1. `SSE` 通道不承担权威存储职责。
2. `SSE` 负责“低延迟送达”。
3. 事件表或日志索引负责“可追溯回放”。

## 关键补充底座

### 4. 可观测性体系：Trace / Metric / Log / Alert

#### 引入理由

多智能体系统的故障通常横跨：

1. HTTP 接口
2. 工作流调度
3. `MQ` 投递与消费
4. `Redis` 锁与缓存
5. `LLM` 调用
6. 外部工具调用

没有统一可观测性，问题会长期停留在“能跑但难排查”的阶段。

#### 长期建议

1. 采用 `OpenTelemetry` 统一 Trace 上下文。
2. 任务 ID、节点 ID、消息 ID、模型调用 ID 必须贯穿为关联字段。
3. 至少建立三类核心指标：
   1. 任务层：成功率、平均完成时长、恢复次数、人工介入率。
   2. 节点层：排队时长、执行时长、失败率、重试率。
   3. `AI` 层：`token` 消耗、供应商错误率、限流命中率、降级次数。
4. 告警必须覆盖：
   1. 消费积压。
   2. `DLQ` 增长。
   3. 锁争用异常。
   4. `SSE` 推送失败率升高。
   5. 指定模型供应商异常抖动。

### 5. 大模型网关与流量治理：Model Gateway

#### 引入理由

`08-ai-infrastructure-and-tooling.md` 已定义 `infra-ai` 的总体方向，但从生产运行角度，还需要更明确的平台治理能力：

1. 高峰时段不同任务类型会竞争有限模型配额。
2. 某个供应商抖动时，不能把失败直接传递给所有业务节点。
3. 研究任务的成本、时延和质量需要统一路由策略，而不是每个 Agent 自行决定。

#### 长期建议

1. 建立统一 `Model Gateway`，作为所有 `LLM`、`Embedding`、`Rerank` 调用入口。
2. 提供：
   1. 租户 / 任务 / 节点级限流。
   2. 并发舱壁隔离。
   3. 熔断与自动故障转移。
   4. 降级策略，例如从高成本模型降到基础模型，或从全量分析降到摘要保底。
   5. 成本预算与超预算保护。
3. 将模型策略从 Agent 代码中抽离，改为平台可配置策略。

### 6. 工作流持久化与调度恢复层：Workflow Persistence And Recovery Layer

#### 引入理由

如果只有 `DAG` 执行器，没有正式的恢复层，就会出现：

1. 节点状态在内存、缓存、数据库之间不一致。
2. 任务中断后无法明确从哪个恢复点继续。
3. 人工重跑、自动补偿、`DLQ` 重投缺少统一语义。

#### 长期建议

1. 为任务与节点建立权威状态表、事件表、恢复点表。
2. 所有关键状态迁移都需要事件留痕，而不是只保留最终字段。
3. 对长耗时节点定期写入 `heartbeat` 和阶段性快照。
4. 任务恢复必须支持：
   1. 从最近成功快照恢复。
   2. 从指定节点局部重跑。
   3. 从 `DLQ` 补偿恢复。
   4. 从人工修正后的状态继续推进。

## 关键状态流与异步编排关系

### 标准任务执行链路

1. 用户发起任务，`Task Control Layer` 写入任务主记录并发布 `TaskCreated`。
2. `Planner / Workflow Layer` 消费后生成执行图，持久化节点并发布首批 `NodeReady`。
3. 调度器抢占 `READY` 节点的调度锁，成功后写入权威状态并发布 `NodeStarted`。
4. 对应 Agent 消费执行请求，执行期间持续上报 `NodeHeartbeat`、日志和阶段进度。
5. `Progress Aggregator` 聚合消息，写入 `Redis` 快照并通过 `SSE` 推送前端。
6. 节点成功后发布 `NodeCompleted`，编排层判断下游依赖并释放新的 `NodeReady`。
7. 节点失败时由恢复层判断：
   1. 是否自动重试。
   2. 是否进入人工干预。
   3. 是否进入 `DLQ`。
8. 最终任务进入 `SUCCEEDED / FAILED / DEGRADED / TERMINATED` 之一，并通过 `SSE` 向前端完成收口。

### 前端观察链路

1. 前端进入任务详情页，先拉取任务快照。
2. 页面建立 `SSE` 连接订阅任务事件流。
3. 服务器按事件类型推送节点状态、日志、思考过程、质量诊断。
4. 客户端断线重连时携带最后游标，请求补齐缺失事件。

## 关键约束与设计原则

1. `任务状态必须单点收敛`
   不允许多个 Agent 或多个消费者各自随意更新最终状态。
2. `所有长耗时节点默认异步化`
   页面抓取、搜索补源、分析、写作、质检不应设计为强同步 HTTP 事务。
3. `基础设施必须服务可恢复性`
   `MQ`、`Redis`、`SSE` 的引入，不是为了堆技术栈，而是为了确保任务可恢复、可追踪、可降级。
4. `前端实时性不应以轮询换取`
   轮询只可作为 `SSE` 不可用时的兜底方案，而不是主路径。
5. `至少一次投递必须由幂等兜底`
   不能假设 `MQ` 永不重复投递。
6. `缓存一致性优先级低于权威状态一致性`
   展示层允许短暂旧数据，但任务状态判断必须回到权威存储。
7. `AI 网关与工作流调度分层`
   模型调用治理不应侵入节点状态机实现。

## 与其他能力域的边界

1. 本文档定义“平台底座如何稳定承载系统运行”，不替代 [04-agent-runtime-and-workflow-architecture.md](/E:/java_study/Mul-agnet/specs/04-agent-runtime-and-workflow-architecture.md) 中的 Agent 职责与任务图语义。
2. 本文档定义“工程基础设施与运行可靠性”，不替代 [08-ai-infrastructure-and-tooling.md](/E:/java_study/Mul-agnet/specs/08-ai-infrastructure-and-tooling.md) 中 `infra-ai` 的能力接口抽象；二者关系是：
   1. `08` 更偏 `AI` 能力治理接口。
   2. `11` 更偏平台级运行底座与中间件体系。
3. 本文档不替代 [09-quality-evidence-and-audit.md](/E:/java_study/Mul-agnet/specs/09-quality-evidence-and-audit.md) 中的证据、质量与审计业务规则，但为这些规则提供可追踪、可回放的实现底座。
4. 本文档不直接定义阶段任务排期，阶段落地顺序以 [10-phased-roadmap.md](/E:/java_study/Mul-agnet/specs/10-phased-roadmap.md) 为准。

## 阶段映射关系

在长期蓝图下，建议按以下顺序逐步落实：

1. `路线图第一阶段 / plan Phase 2`
   先补齐任务进度事件、节点状态统一语义、`SSE` 基础通道、`Redis` 快照、基础缓存与最小恢复机制。
2. `路线图第二阶段 / plan Phase 3`
   在已有运行底座之上完成产品化工作台、面向用户的运行观察面、统一状态提示和受控高级诊断边界。
3. `路线图第三阶段 / plan Phase 4`
   引入 `RocketMQ` 承载长耗时节点异步化，完善正式事件驱动状态流转、动态补图、回流、重试和 `DLQ`。
4. `路线图第四阶段 / plan Phase 5`
   完成模型网关、跨任务治理、组织级限流和更成熟的恢复、审计与正式交付平台。

这里的阶段映射仅用于说明推荐演进顺序，不改变路线图主文档的阶段定义权。

## 扩展预留

1. 后续可补充 `Config Center / Feature Flag`，用于灰度发布不同调度策略、模型路由策略和恢复策略。
2. 后续可补充 `Tenant Isolation`，面向组织级并发配额、缓存隔离、消息隔离和数据隔离。
3. 后续可补充 `Connector Runtime`，将连接器执行、配额、认证刷新和同步任务纳入统一底座。

## 待确认问题

1. 是否在未来把“组织权限、多租户、配额计费”正式升级为单独一级能力域。
2. 是否需要把 `Config Center / Feature Flag` 在中期提前，作为调度策略和 `AI` 网关治理的必要前提。

## 本阶段不约束的内容

1. 不要求当前版本立刻完成全部中间件接入。
2. 不要求当前就确定 `RocketMQ Topic` 的最终物理命名。
3. 不要求当前就把所有任务场景都切换为统一事件驱动执行。
