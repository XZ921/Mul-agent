# 工程整体优化方针

> **适用范围：** AI 竞品分析 Agent 协作系统全工程  
> **文档目标：** 统一后续优化方向，作为多人协作、任务拆分、GitHub 分支规划和阶段实施的基础依据  
> **当前状态：** 已结合当前代码基线修订，可直接作为后续设计与实施的上位约束

---

## 1. 总体判断

当前工程后续不应继续按照“普通前后端业务系统”方式演进，而应明确升级为：

**一个可恢复、可审计、可扩展、可协作的 Agent 任务平台。**

后续所有优化都围绕以下四个核心目标展开：

1. **模块边界清晰化：** 避免核心逻辑继续堆积在少数巨型 Service 或执行器中。
2. **运行时可观测化：** 让任务计划、执行进度、节点状态、失败原因、恢复动作全部可追踪。
3. **结果可信化：** 让分析结论具备 `sourceUrls`、证据链、审计能力，降低幻觉风险。
4. **协作与产品化：** 支持多人并行开发、GitHub 合并治理，以及前端任务工作台演进。

---

## 2. 优化总原则

### 2.1 架构原则

- 严格按职责收敛 `task`、`workflow`、`agent`、`source`、`search`、`report` 等核心模块。
- `AnalysisTaskService`、`DagExecutor` 一类核心入口逐步退化为编排门面，不再持续承载细节实现。
- 优先清理残留冗余、统一契约、补边界测试，再做下一轮拆分，避免为“继续提取”而提取。

### 2.2 工程原则

- 优化顺序优先级：**边界稳定 > 运行时稳定 > 质量治理 > 功能扩展 > 体验美化**。
- 每次优化只解决一类问题，不把架构调整、功能新增、UI 改版混在同一个 PR 中。
- 每个阶段都必须有可验证产物：文档、测试、契约、事件结构或可视化结果。

### 2.3 Agent 治理原则

- 所有外部模型调用、外部抓取、搜索工具调用必须具备异常处理、重试和审计能力。
- 所有分析结果必须可溯源，核心数据结构必须保留 `sourceUrls`。
- Agent 输出应逐步标准化为契约化结构，避免 prompt 变动导致下游逻辑脆弱。

### 2.4 协作原则

- 默认采用 GitHub `main + feature branch + Pull Request` 协作流。
- 每个分支只解决单一主题，避免一个分支横跨后端架构、前端展示和数据治理三类问题。
- 先按模块或链路拆分任务，再并行协作，尽量避免多人同时重改同一核心类。

### 2.5 当前代码基线原则

- 本文档以 **当前代码已完成 Phase 1 主要拆分** 为前提，不再把已落地拆分写成“近期建议”。
- 后续 Phase 1 工作重点是：**清理残留冗余、补边界测试、固化运行时契约、明确兼容策略**。
- 对于尚未通过测试验证的风险，统一按“待验证风险”记录，不把推测直接写成既定缺陷。

---

## 3. 阶段一：平台内核收敛与稳定化

### 3.1 阶段目标

在 Phase 1 已完成主要职责拆分的基础上，继续稳定工程核心边界、运行时契约和兼容行为，形成可长期演进的内核基线。

### 3.2 当前 Phase 1 已完成基线

以下拆分与引入已经在当前代码中落地：

1. `TaskQueryAppService` 已承接只读任务查询链路。
2. `TaskNodeViewAssembler` 已承接任务与节点视图组装职责。
3. `TaskDefinitionAppService` 已承接任务创建、预览、删除等定义期命令。
4. `TaskRuntimeCommandAppService` 已承接启动、续跑、重试、节点干预等运行时命令。
5. `AgentCapabilityRegistry` / `SpringAgentCapabilityRegistry` 已接入 `DagExecutor`。
6. `RuntimeStateRefresher`、`RuntimeEventEmitter`、`DynamicPlanAppender` 已作为 `DagExecutor` 运行时协作者存在。
7. `AnalysisTaskService` 当前已经基本收敛为门面层。
8. `BackendModuleDependencyTest`、`AnalysisTaskServiceTest`、`TaskControllerTest`、`DagExecutorTest`、`DagExecutorWorkflowEventTest` 已存在。

### 3.3 当前 Phase 1 剩余工作

当前剩余工作不再是“继续提取服务”，而是以下四类：

1. **残留冗余清理**
   - 清理 `DagExecutor` 中仍然存在的重复快照构建、重复快照发布、残留事件分发和动态计划收尾逻辑。
   - 清理已迁移职责留下的重复 JSON 解析、状态摘要和快照刷新路径。

2. **边界测试补强**
   - 扩展 ArchUnit 规则，覆盖 `controller`、`agent`、`workflow`、`report`、`repository` 的核心边界。
   - 对历史耦合使用具名豁免，不允许用宽泛规则掩盖问题。

3. **运行时契约固化**
   - 不再把“统一运行时模型”当作一个笼统任务推进。
   - 改为分别固化：
     - `Runtime Snapshot Contract`
     - `Workflow Event Contract`
     - `Search Progress Event Contract`
     - `Node Execution Attempt / Log Contract`

4. **兼容行为锁定**
   - 锁定 `/api/task`、SSE、恢复/重试/重跑行为不变。
   - 用测试验证 `createTask` 治理链路不会引入重复占位回归。
   - 明确 Phase 1 不做破坏性 DTO 删除、不做数据库迁移。

### 3.4 阶段验收标准

阶段一完成时，至少满足以下可检查项：

1. `AnalysisTaskService` 只保留 facade 和必要事务入口，不再回流业务细节。
2. `DagExecutor` 不再保留已迁出职责的重复实现：
   - 任务级快照构建与发布不再在多个私有 helper 中重复出现。
   - 节点完成后的事件 payload 组装统一由 `RuntimeEventEmitter` 承担。
   - 动态计划追加实现只保留在 `DynamicPlanAppender`。
3. `BackendModuleDependencyTest` 覆盖以下边界并保持绿灯：
   - `controller -> workflow/repository`
   - `agent -> repository`
   - `report -> source/search`
   - `workflow -> controller`
   - 其他新增规则若存在历史耦合，必须使用具名豁免记录。
4. 聚焦回归测试通过：
   - `AnalysisTaskServiceTest`
   - `TaskControllerTest`
   - `DagExecutorTest`
   - `DagExecutorWorkflowEventTest`
   - `BackendModuleDependencyTest`
5. `mvn test` 通过，或仅保留已知且已记录的无关失败。
6. `/api/task`、SSE 事件类型、任务恢复/重试/重跑行为保持兼容。
7. Phase 1 不引入数据库 schema migration，不删除现有对外 DTO 字段。

### 3.5 模块命名与边界修订

当前代码结构中，证据能力并没有独立的 `evidence` package，实际边界如下：

- `source`：来源发现、候选源、采集入口、Provider 实现。
- `search`：搜索执行、搜索轨迹、进度快照、浏览器搜索运行时。
- `report`：报告组装、证据查询、导出与诊断。
- `repository.EvidenceSourceRepository`：证据相关持久化入口之一。
- `report.EvidenceQueryService`：证据查询与交付层能力之一。

因此，当前阶段中的 “evidence” 应被理解为：

**一个跨 `source / search / report / repository` 的数据与交付能力，而不是当前已存在的独立包。**

Phase 1 不强制新建 `evidence` 模块；后续仅在跨模块数据模型和调用边界明确失控时，再评估独立模块化。

### 3.6 兼容性策略

为了避免 Phase 1 的结构清理破坏现有行为，统一采用以下兼容策略：

1. **老任务数据兼容**
   - 允许旧任务中的 JSON 字段为空、缺失或历史形态不完整。
   - 解析逻辑优先兼容读取，不在 Phase 1 做历史数据回填迁移。

2. **SSE payload 兼容**
   - 保留当前事件类型：
     - `TASK_SNAPSHOT`
     - `TASK_STATUS`
     - `NODE_STATUS`
     - `SEARCH_PROGRESS`
     - `AGENT_OUTPUT`
     - `DIAGNOSIS`
   - Phase 1 只允许**加字段**，不允许删字段、改字段语义、改事件类型名。

3. **前端接口兼容**
   - 前端按渐进式迁移消费新字段。
   - 不允许为了后端清理而直接破坏任务详情、时间线或恢复入口。

4. **DTO 兼容**
   - Phase 1 不删除 public API 字段。
   - 如需新增字段，必须保持旧字段仍可被前端继续消费。

5. **数据库兼容**
   - Phase 1 不做 schema migration。
   - 若发现字段不足，优先通过运行时计算、兼容解析或测试锁定解决。

### 3.7 当前最真实的冗余与风险

基于当前代码现状，近期最值得优先处理的风险如下：

1. `DagExecutor` 虽已引入运行时协作者，但仍保留多处任务快照保存与发布路径，存在重复维护风险。
2. `DagExecutor` 中仍同时存在协作者委托和直接 `TaskEventPublisher` 事件触发，需要进一步明确“谁负责组装、谁负责触发”。
3. `TaskDefinitionAppService.createTask` 当前治理链路需要用回归测试锁定，避免未来修改时引入重复 reserve 或漏标记问题。
4. JSON 解析、状态摘要、快照语义仍分散在 `TaskDefinitionAppService`、`TaskRuntimeCommandAppService`、`RuntimeStateRefresher`、`TaskProgressSnapshot` 等位置。
5. `source / search / report` 的证据边界已有实际实现，但尚未通过文档和 ArchUnit 规则完全固化。
6. `AnalysisTaskService` 当前已基本干净，不应再被当作近期主要治理对象；近期重点应转向 `DagExecutor` 和运行时契约。

### 3.8 当前最优先的执行顺序

如果当前只选择一个突破口，建议优先执行：

**“基于现有拆分成果，完成 Phase 1 收尾清理与契约固化。”**

建议顺序如下：

1. 先补 `createTask`、`/api/task`、SSE 的表征测试与兼容测试。
2. 再清理 `DagExecutor` 残留的重复快照与事件逻辑。
3. 然后补强 ArchUnit 边界。
4. 最后补齐四类运行时契约文档与对应测试。

---

## 4. 阶段二：结果可信度与治理能力建设

### 4.1 阶段目标

在平台内核稳定后，把系统从“能产出结果”升级为“产出结果可信、可审计、可复盘”。

### 4.2 核心优化方向

1. **证据链治理**
   - 强制所有分析结论绑定 `sourceUrls`。
   - 将“证据数据”和“分析结论”分层存储，避免相互覆盖。
   - 对采集结果增加去重、归一化、时间戳和来源状态记录。

2. **质量检查体系**
   - 增加引用缺失检查。
   - 增加来源失效检查。
   - 增加证据冲突检查。
   - 增加报告漏项、结构缺失和结论无支撑检查。

3. **外部依赖治理**
   - 统一外部 LLM、搜索、抓取调用的重试、超时、异常分类和降级处理。
   - 为关键调用增加审计记录，支持问题追溯。
   - 为模型输出增加结构校验和回退策略。

4. **Agent 输出契约化**
   - 逐步定义各 Agent 输入输出 Schema。
   - 减少“自然语言暗约定”对后端逻辑的影响。
   - 为核心节点增加最小评测样例，持续验证输出稳定性。

---

## 5. 阶段三：产品化、协作化与扩展能力建设

### 5.1 阶段目标

在边界稳定和治理能力成熟后，把系统推进到可多人协作、可长期演进的产品平台阶段。

### 5.2 核心优化方向

1. **前端任务工作台升级**
   - 从“结果页展示”升级为“任务驾驶舱”。
   - 增加任务时间线、节点状态流转、失败定位、重试入口、证据预览、报告对照视图。

2. **Agent 能力平台化**
   - 进一步平台化能力注册中心和扩展接入点。
   - 为采集、分析、撰写、质检能力建立统一接入约束。

3. **执行层工程化**
   - 引入更清晰的异步执行、队列、worker、并发控制、限流能力。
   - 补充成本治理、缓存策略和热点路径优化。

4. **多人协作治理**
   - 建立 GitHub 分支与 PR 规范。
   - 按模块拆任务，支持双人或多人并行改造。
   - 补充操作审计、任务归属、权限隔离等平台能力。

---

## 6. 工程化禁止事项

以下规则在 Phase 1 到 Phase 3 全程生效：

1. 每个 PR 只能修改一个链路或一个主题。
2. 不允许同一 PR 同时重改 `controller`、`workflow` 和前端 UI。
3. 不允许无测试删除 public API 字段或改动其语义。
4. 不允许新增跨模块 `repository` 直接访问来“临时打通”功能。
5. 不允许把多模块逻辑抽成泛化大 `Utils` 掩盖边界问题。
6. 不允许用“放宽 ArchUnit 规则”替代真实边界治理。
7. 不允许把尚未验证的推测直接写成既有缺陷。

---

## 7. 双人协作下的任务拆分建议

为了支持后续通过 GitHub 协作开发，建议避免两个人同时重改同一核心文件，优先采用以下拆法：

### 方案 A：按前后端分工

- 成员 A：后端模块边界、运行时模型、任务事件、恢复重试
- 成员 B：前端任务驾驶舱、任务详情页、证据展示、进度可视化

### 方案 B：按链路分工

- 成员 A：查询链路、报告读取链路、边界测试
- 成员 B：执行链路、运行时事件、失败恢复链路

### 方案 C：按治理与产品分工

- 成员 A：架构拆分、Agent 契约、测试与治理
- 成员 B：交互工作台、用户流程、任务可视化体验

推荐优先使用 **方案 A 或方案 B**，因为边界更清晰、冲突更少。

---

## 8. 近期输出物建议

当前建议优先输出以下三类成果，再进入并行开发：

1. **阶段一实施计划**
   - 基于当前代码现状，只覆盖 Phase 1 剩余工作，不重复规划已完成拆分。

2. **运行时契约文档**
   - 分别固化：
     - `Runtime Snapshot Contract`
     - `Workflow Event Contract`
     - `Search Progress Event Contract`
     - `Node Execution Attempt / Log Contract`

3. **GitHub 协作规范**
   - 明确分支命名、PR 流程、Review 责任和合并门槛。

---

## 9. 结论

整个工程未来最正确的优化方向，不是继续堆功能，而是：

**先基于已完成的 Phase 1 拆分成果，把平台内核收尾到“边界清晰、契约稳定、兼容可控”，再逐步做可信治理和产品化扩展。**

后续所有设计、重构和协作规划，均应以此方针为上位约束。
