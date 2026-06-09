# Living Blueprint SDD

## 文档目的

`specs` 目录用于维护本项目的长期产品蓝图、关键能力域边界和必须长期遵守的实施约束，而不是记录某个阶段的临时实现细节。

这套文档的目标是：

1. 描绘项目最终希望演进到的产品形态。
2. 为后续新增任务、需求和子系统提供稳定落点。
3. 在不锁死未来实现路径的前提下，沉淀长期稳定的业务原则、系统边界和产品化约束。
4. 让阶段性路线图和 `plan/` 中的阶段计划始终受蓝图与治理规则约束，而不是由短期实现反向定义产品。

## 文档定位

本目录采用 `Living Blueprint SDD` 写法，所有文档都按以下四层语义组织：

1. `稳定原则`
   长期尽量不变的产品和系统原则，例如证据可追溯、任务可恢复、工作台可理解、Agent 可审计。
2. `目标能力`
   最终产品必须具备的能力版图，例如统一对话入口、动态任务图、RAG、记忆、AI 基础设施与工作台体系。
3. `阶段决策`
   当前对实现路径和阶段优先级的判断，可随着认知深化进行调整。
4. `扩展预留`
   已确认需要预留边界，但暂不锁定具体实现方案的内容。

## 阅读顺序

建议按以下顺序阅读：

1. `00-product-overview.md`
2. `01-business-requirements.md`
3. `02-user-journeys-and-task-modes.md`
4. `03-domain-model-and-data-architecture.md`
5. `04-agent-runtime-and-workflow-architecture.md`
6. `05-knowledge-ingestion-rag-and-memory.md`
7. `06-conversation-intent-and-task-orchestration.md`
8. `07-frontend-workbench-and-delivery-center.md`
9. `08-ai-infrastructure-and-tooling.md`
10. `09-quality-evidence-and-audit.md`
11. `10-phased-roadmap.md`
12. `11-technical-foundation-and-platform-architecture.md`
13. `12-frontend-productization-and-design-governance.md`

## 文档分层

### 一、长期蓝图文档

以下文档主要定义“最终产品是什么”，变更频率应较低：

1. `00-product-overview.md`
2. `01-business-requirements.md`
3. `03-domain-model-and-data-architecture.md`
4. `09-quality-evidence-and-audit.md`

### 二、能力域与体验文档

以下文档主要定义“最终产品如何运作”，会随着认知增加逐步补细：

1. `02-user-journeys-and-task-modes.md`
2. `04-agent-runtime-and-workflow-architecture.md`
3. `05-knowledge-ingestion-rag-and-memory.md`
4. `06-conversation-intent-and-task-orchestration.md`
5. `07-frontend-workbench-and-delivery-center.md`
6. `08-ai-infrastructure-and-tooling.md`
7. `11-technical-foundation-and-platform-architecture.md`

### 三、实施约束文档

以下文档定义“实现这些能力时必须遵守什么规则”，不应被视为可选建议：

1. `12-frontend-productization-and-design-governance.md`

### 四、路线图文档

以下文档主要定义“从现状如何走向蓝图”，更新频率可更高：

1. `10-phased-roadmap.md`
2. `README.md`

## 关键边界约定

1. `00-product-overview.md`
   只承担产品总纲职责，重点维护愿景、长期稳定原则、能力地图和产品边界。
2. `01-business-requirements.md`
   只承担业务契约职责，重点维护任务输入输出模型、业务规则、成功标准和业务非目标。
3. `07-frontend-workbench-and-delivery-center.md`
   负责定义工作台的信息架构、默认展示层级和用户体验主结构。
4. `12-frontend-productization-and-design-governance.md`
   负责定义前端产品化治理、设计落地、调试收纳、评审门槛和验收约束。
5. `10-phased-roadmap.md`
   负责把蓝图与实施约束映射为阶段路线，但不替代 `plan/` 中的阶段计划。
6. 若同一概念已经在上位文档定义，其他文档优先引用，不再展开复制。

## `specs` 与 `plan` 的职责分工

1. `specs`
   负责定义长期蓝图、能力域边界、终态设计原则和必须长期遵守的实施约束。
2. `plan`
   负责把这些能力域和约束映射为阶段性闭环，并定义交付包、验收口径和阶段交接口。
3. `task`
   负责把单个阶段计划进一步拆成可执行任务，不得反向定义阶段边界。

因此：

1. 不以“一个 spec 对应一个 phase”来拆计划。
2. 不允许先写 `task` 再倒推蓝图和阶段边界。
3. 若某阶段已经出现体验或实现失控，但 `specs` 中没有对应约束，应先补 `specs` 再补 `plan`，最后才写 `task`。

## 新增需求时如何更新

当后续出现新任务或新需求时，建议按以下顺序处理：

1. 先判断它是否改变长期稳定原则。
2. 再判断它属于哪个能力域。
3. 再判断现有实施约束是否足够，尤其是是否会触碰工作台体验、前端治理、质量呈现或运行底座边界。
4. 若只是阶段计划变化，优先更新 `10-phased-roadmap.md` 与对应 `plan/phase-x-plan.md`。
5. 若引入新的独立能力域，可新增 `13-xx.md` 及后续文档，不必强行塞入既有文件。

## 文档统一写法

为避免模板过重但无法执行，统一章节模板分为三层：

### 必选章节

所有能力域文档至少应包含以下章节：

1. 文档目的
2. 本能力域在系统中的职责
3. 目标能力
4. 关键约束与设计原则
5. 与其他能力域的边界

### 推荐章节

以下章节在大多数文档中建议保留，但允许按文档性质精简：

1. 业务目标与用户价值
2. 当前结论
3. 关键场景
4. 核心对象 / 核心模块
5. 核心流程 / 状态流 / 编排关系
6. 扩展预留
7. 待确认问题

### 可选章节

以下章节只有在当前文档确有必要时再补充：

1. 本阶段不约束的内容
2. 阶段映射关系
3. 风险与回退策略

## 术语对照表

为避免后续文档出现同一概念多种叫法，先统一以下术语：

1. `Agent`
   指系统内部的执行单元或角色能力，例如 `Planner Agent`、`Reviewer Agent`。
2. `对话智能体`
   指用户直接感知到的统一对话入口，不等同于某一个内部 Agent 类。
3. `Agent Runtime`
   指承载 Agent 执行、上下文装配、工具调用、记忆读写和审计留痕的运行时层。
4. `Task`
   指一次研究任务实例。
5. `Task Plan / Plan Graph`
   指某次任务的规划结果与执行图，不等同于页面上的简单步骤列表。
6. `Evidence`
   指系统中的事实与来源回指载体，是报告结论的底层依据。
7. `RAG`
   指检索、召回与重排机制，用于帮助找到可用知识，不直接替代事实对象。
8. `Memory`
   指短期记忆、任务记忆、领域记忆和经验记忆的统称，不等同于单一向量库。

## 当前结论

1. 本项目的长期定位不是“竞品报告生成器”，而是“企业级研究任务 AI Operating System”。
2. 当前工程可以作为蓝图起点，但不能反向限制最终形态。
3. 从 `Phase 3` 开始，前端产品化治理约束不再是可选建议，而是正式验收口径的一部分。
4. 后续所有阶段规划都应以本目录文档为准绳，并允许持续增量演进。

## 扩展预留

1. 后续可新增连接器平台、权限体系、组织级知识中台等独立 spec。
2. 当前已新增 `11-technical-foundation-and-platform-architecture.md` 承接企业级工程底座蓝图，`12-frontend-productization-and-design-governance.md` 承接产品化治理约束；后续如多租户治理、控制台设计规范显著扩展，可继续向后拆分。
3. 文档编号允许向后扩展，不要求一次性规划完所有子系统。

## 待确认问题

1. 未来是否需要把“组织协作与权限体系”提前提升为一级能力域。
2. 是否需要把部分实施约束进一步沉淀为自动化检查。
3. 随着基础设施蓝图细化，是否需要把“多租户与配额治理”单独升级为一级能力域。

## 本阶段不约束的内容

1. 不要求当前代码实现已经完全符合蓝图。
2. 不要求路线图一次性细化到每个迭代工单。
