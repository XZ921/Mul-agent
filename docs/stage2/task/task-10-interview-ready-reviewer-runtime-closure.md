# Task 10：面试版 Reviewer 决策闭环与运行时收口计划

## 1. 任务定位

本任务不是继续扩展 Agent 数量、调整质量分数阈值或反复优化 Prompt，而是在一周内完成一次范围受控的核心收口：

> 让 Reviewer 的质量诊断经确定性归一、ActionMatrix 和 Policy 裁决后形成唯一、可执行、可审计的运行时 mutation，并通过确定性测试、硬预算和一次受限真实 E2E 证明该闭环有效。

项目面试定位调整为：

> 基于证据溯源、质量门禁、受控动态调度和人工接管的半自动竞品分析 Agent 协作系统。

本任务完成后冻结 Stage 2 的核心功能，不再进行没有明确验收收益的局部优化。

## 2. Task 7 Step 4 暴露的基线问题

真实任务基线：`taskId=112`，Task 7 Step 4 状态为 `PASSED_WITH_RECORDED_ISSUES`。

实际执行链路：

```text
Reviewer 初审失败（score=37）
    -> Writer 静态改写
    -> Citation 复核
    -> Reviewer 终审失败（score=38）
    -> WAITING_INTERVENTION
```

关键事实：

- Reviewer 初审已经提出 `SUPPLEMENT_EVIDENCE -> collect_sources` 和 `RERUN_NODE -> extract_schema`。
- Writer 阶段的 Orchestrator 决策两次得到 `READY / APPEND_NODES`，但动态节点实际新增数量为 0。
- Writer 改写前后证据数量均为 9，没有新增证据。
- 报告长度从 4,812 增加到 5,293，质量分仅从 37 提升到 38。
- Citation 能确认已有 `sourceUrls` 的可追溯性，但不能修复证据覆盖缺口。
- 最终正确进入 `WAITING_INTERVENTION`，证明人工止损有效，但自动补证据闭环未生效。
- 整个真实任务记录了 1,731,038 actual token，且 `ai.budgetEnabled=false`，成本保护未生效。

根因结论：

```text
Reviewer 诊断正确
    -> 初审由静态 review_failed 分支解释，终审才进入动态决策，存在两个控制面
    -> Reviewer 建议、Orchestrator 决策和运行时 mutation 之间没有唯一权威命令
    -> APPEND_NODES 的计划保存、节点物化、任务版本切换和 checkpoint 不是原子提交
    -> 补采收益只看证据数量，不能证明指定竞品/字段/来源类型的覆盖缺口已经关闭
    -> 没有新证据时继续产生高成本改写和复审
    -> actual token 只能事后取得，无法单独充当调用前硬门禁
```

本任务必须一次解决上述控制面根因，不允许只给 Reviewer 增加字段、只修改 `review_failed` 条件，
或只补一组围绕当前症状的测试后，把 mutation 原子性、受控重跑、目标覆盖门禁和预算预留继续后移。

## 3. 本任务目标

### 3.1 核心目标

1. Reviewer 每轮只输出质量事实、`issues`、`diagnoses` 和兼容期 `directives`，不直接拥有运行时调度权。
2. Reviewer 输出必须经单一归一化入口进入现有 Orchestrator ActionMatrix、Policy 和 Executor；Policy 通过后的 `DynamicPlanMutation` 是唯一权威运行时命令。
3. 对外展示或审计所需的 `nextAction` 必须由最终 mutation 确定性投影，禁止作为新的 LLM 输出动作模型。
4. 初审、终审和动态复审统一经过同一决策入口；缺证据时必须先补采或受控重跑，目标覆盖门禁通过前禁止进入 Writer。
5. 所有符合策略的 `APPEND_NODES` 由唯一运行时组件物化和执行，计划版本、动态节点、任务当前版本和 checkpoint 原子提交。
6. `CREATE_SUPPLEMENT_BRANCH`、`CREATE_RERUN_BRANCH`、`CREATE_REWRITE_BRANCH` 和 `MARK_WAITING_INTERVENTION` 均具备真实、排他的运行时语义，不允许记录成功但执行为空。
7. 目标覆盖无改善、质量诊断无改善、重复动作或超过预算时，确定性进入 `WAITING_INTERVENTION`。
8. 使用 Fake Provider 覆盖完整闭环，真实 Provider 只允许在全部本地验收通过后执行一次。
9. 所有分析结论继续强制携带 `sourceUrls`，不得通过 Writer 改写伪造证据。

### 3.2 非目标

- 不增加新的 Agent 类型。
- 不重写整个 DAG 执行框架。
- 不通过降低 Reviewer 分数门槛制造通过结果。
- 不以增加报告篇幅作为质量提升指标。
- 不进行前端视觉改造。
- 不进行多轮真实 DeepSeek/Tavily 调参。
- 不处理与本闭环无关的 embedding、read model 或采集质量优化，除非它们直接阻断最终一次受限 E2E。
- 不新建第二套 Reviewer 动作枚举、Policy 或动态 DAG 执行框架；优先补齐现有契约和唯一执行入口。
- 不把 README、架构图或演示材料置于运行时闭环、确定性测试和完整回归之前。

## 4. Reviewer 诊断到权威运行时命令的契约

Reviewer 可以返回多个 `issues`、`diagnoses` 和兼容期 `directives`。Java 归一化器必须把这些质量事实裁决为一个候选动作，
再复用现有 ActionMatrix、Policy 和 Executor 得到唯一权威 mutation：

| 诊断归一结果 | Policy normalizedAction | 唯一权威 mutation | 对外 `nextAction` 投影 |
|---|---|---|---|
| `PASS` | `NO_ACTION` | `NO_MUTATION` | `PASS` |
| `REWRITE` | `CREATE_REWRITE_BRANCH` | `APPEND_NODES` | `REWRITE` |
| `SUPPLEMENT_EVIDENCE` | `CREATE_SUPPLEMENT_BRANCH` | `APPEND_NODES` | `SUPPLEMENT_EVIDENCE` |
| `RERUN_NODE` | `CREATE_RERUN_BRANCH` | `APPEND_NODES` | `RERUN_NODE` |
| `WAITING_INTERVENTION` | `MANUAL_ONLY` | `MARK_WAITING_INTERVENTION` | `WAITING_INTERVENTION` |

`nextAction` 不是 Reviewer 自由生成字段，也不能反向驱动运行时；它只能由已经通过 Policy 的 mutation 和 normalizedAction 投影。

动作优先级：

```text
WAITING_INTERVENTION
    > SUPPLEMENT_EVIDENCE
    > RERUN_NODE
    > REWRITE
    > PASS
```

其中 `WAITING_INTERVENTION` 的最高优先级只适用于预算耗尽、循环上限、重复动作、目标覆盖无收益、mutation 物化失败等 Java 硬门禁。
Reviewer 原始 `requiresHumanIntervention`、低分或自然语言建议只是诊断事实，必须经归一化和 Policy 裁决，不能单独抢占补采或重跑动作。

确定性约束：

- 只有 `passed=true` 才能选择 `PASS`。
- 存在证据覆盖缺口、结构化证据缺失或必须重跑的诊断时，不得选择 `REWRITE`。
- `REWRITE` 只允许修复表达、结构、格式、可读性和已有证据的组织问题。
- `SUPPLEMENT_EVIDENCE` 必须指明竞品、目标字段、所需来源类型和可审计的缺口键；缺少任一项时不得自动补采。
- 当“缺少来源”和“需要重新抽取”同时存在时，统一选择 `SUPPLEMENT_EVIDENCE`，并在受控分支内完成后续抽取，避免同轮产生两个动作。
- `RERUN_NODE` 只处理已有可信来源但结构化抽取缺失或失效的场景，必须命中允许重跑节点白名单，不允许任意回退整个工作流。
- 相同动作指纹重复出现且目标覆盖无增量时，必须进入 `WAITING_INTERVENTION`。动作指纹至少包含 action、targetNode、competitor、targetField 和 requiredSourceType。
- LLM 负责诊断和解释；Java 归一化器负责候选动作优先级；ActionMatrix 与 Policy 保留执行许可；Executor 负责产生唯一 mutation；DAG 只消费 mutation，不重新解释 Reviewer 字段。

统一状态转换表：

| 当前检查点 | 归一条件 | 权威 mutation | 实际下一节点 | 明确禁止 |
|---|---|---|---|---|
| 初审/终审/动态复审 | `passed=true` | `NO_MUTATION` | 正常收口 | 再次改写或补采 |
| 任一 Reviewer | 目标来源缺失 | `APPEND_NODES/CREATE_SUPPLEMENT_BRANCH` | 动态 Collector | 提前进入 Writer |
| 任一 Reviewer | 来源存在但结构化字段缺失 | `APPEND_NODES/CREATE_RERUN_BRANCH` | 白名单内 Extractor | 重跑整图 |
| 任一 Reviewer | 仅表达或组织问题 | `APPEND_NODES/CREATE_REWRITE_BRANCH` | 动态 Writer | 启动采集 |
| 任一检查点 | 硬门禁命中 | `MARK_WAITING_INTERVENTION` | 无自动节点 | fallback 到低优先级动作 |

目标运行链路：

```text
Reviewer: SUPPLEMENT_EVIDENCE
    -> Orchestrator 生成受控动态采集节点
    -> collect_sources
    -> extract_schema
    -> target coverage gate
    -> Writer
    -> Citation
    -> Final Reviewer
```

当补采无效时：

```text
Reviewer: SUPPLEMENT_EVIDENCE
    -> collect_sources
    -> targetCoverageDelta=0
    -> WAITING_INTERVENTION
```

## 5. 一周执行计划

### Day 1：确认目标契约基线、现状差异与迁移门槛

- 当前状态：`BASELINE_CONFIRMED`
- 核心目标：确认 Reviewer 诊断到 Orchestrator/Policy/mutation 的目标映射、动作优先级、输入输出字段和状态转换表，并用生产入口特征测试固定当前实现与目标契约的差异。
- 预期耗时：1 天。
- 前置依赖：Task 7 Step 4 验收记录、现有 Reviewer 输出 Schema、Orchestrator ActionMatrix 与 Policy 契约。
- 任务拆解：
  1. 盘点 Reviewer 初审、Writer 建议、Citation 复核、终审和动态复审的现有决策入口，明确哪些入口可以产生 mutation、哪些只能产生诊断。
  2. 确认 `诊断 -> candidate action -> decisionType/actionType -> normalizedAction -> mutation -> nextAction 投影` 的目标映射，禁止增加并行 Policy 或第二套运行时动作枚举。
  3. 输出动作优先级、状态转换表以及每个状态转换的唯一代码所有者。
  4. 确认动作指纹、循环计数、目标覆盖增量、质量诊断增量和任务预算账本的目标字段定义。
  5. 定义目标运行时不变量，并为当前违反不变量的生产路径增加迁移前特征测试；不得把目标不变量写成已实现能力。
- 完成标准：目标契约能够覆盖任何 Reviewer 输出并确定性归一为一个 candidate action；冲突输入存在明确裁决结果；每个 mutation 有明确的目标所有者；当前生产入口与目标契约的差异已由测试和缺口表固定。生产运行时尚未对齐，Day 1 完成仅表示“基线可执行、差异可复现、Day 2 迁移门槛明确”。

#### Day 1 基线结果：目标单一决策链

```text
QualityReviewAgent
    -> Reviewer 质量事实（passed/reviewStage/diagnoses/sourceUrls）
    -> OrchestrationDecisionAdapter（整轮诊断归一入口，不执行 mutation）
    -> OrchestrationDecisionActionMatrix（decisionType/actionType/target/scope 合法性）
    -> DecisionPolicyService（唯一执行许可与硬门禁）
    -> DecisionExecutorAdapter（唯一 mutation 翻译）
    -> DynamicPlanAppender（唯一 mutation 提交入口）
    -> DagExecutor（只消费已提交节点，不再解释 Reviewer 原始字段）
```

目标约束：

1. Reviewer 的 `issues`、`diagnoses`、`requiresHumanIntervention`、`revisionDirectives` 和模型 `nextActions` 均为诊断或兼容展示字段，不能直接执行。
2. `OrchestrationDecisionAdapter` 计划在 Day 2 扩展为“整轮诊断 -> 单个 candidate decision”的入口；当前生产代码尚未具备该入口，它只支持兼容期单条 `RevisionDirective` 转换。
3. `DecisionPolicyService.allowed=true` 只是执行许可；`DecisionExecutorAdapter` 产出的 mutation 才是唯一运行时命令。
4. 对外 `nextAction` 只能从 Policy 通过后的 normalizedAction 和 mutation 投影，不能读取 Reviewer 自由文本反向驱动运行时。
5. `DagExecutor` 的静态 `review_failed` 条件属于待删除的旧控制面，不是目标契约的一部分。

#### Day 1 基线结果：目标诊断优先级算法

归一化器必须先计算 Java 硬门禁，再从同一轮全部诊断中只选择一个 candidate action：

```text
HARD_STOP
    > SUPPLEMENT_EVIDENCE
    > RERUN_NODE
    > REWRITE
    > PASS
```

| 优先级 | 命中条件 | candidate action | 必填输入 |
|---|---|---|---|
| 1 | 预算/循环上限、重复动作指纹、目标覆盖无收益、mutation 物化失败 | `WAITING_INTERVENTION` | stopReason、resumeCheckpoint |
| 2 | 指定竞品/字段缺少可信来源；或来源类型不满足 | `SUPPLEMENT_EVIDENCE` | competitor、targetField、requiredSourceType、gapKey |
| 3 | 已有可信来源，但指定结构化字段缺失或抽取失效 | `RERUN_NODE` | targetNode=`extract_schema` 或动态 Extractor、sourceSnapshot、gapKey |
| 4 | 仅表达、结构、格式、可读性或已有证据组织问题 | `REWRITE` | targetSection、sourceUrls |
| 5 | `passed=true` 且不存在阻断诊断，最终结论有可信 `sourceUrls` | `PASS` | sourceUrls |

冲突裁决：

- 同一 gapKey 同时命中缺来源和结构化缺失时选择 `SUPPLEMENT_EVIDENCE`，补采分支后续固定包含 Extractor。
- `requiresHumanIntervention=true`、低分或模型自然语言建议不能单独命中 `HARD_STOP`；必须有 Java stopReason。
- `passed=true` 与任一阻断诊断冲突时不得 PASS，按阻断诊断的最高优先级裁决。
- 多个同优先级诊断按稳定键 `competitor + targetField + requiredSourceType + targetNode` 排序后选择第一项，其余诊断继续作为解释信息持久化。

#### Day 1 基线结果：目标字段定义

| 字段 | 目标定义 | 当前实现状态 |
|---|---|---|
| `gapKey` | `normalizedCompetitor|normalizedTargetField|normalizedRequiredSourceType`；禁止使用 URL 或随机 ID 组成 | Day 2 待补 |
| `actionFingerprint` | `candidateAction|targetNode|gapKey`；REWRITE 无 gapKey 时使用 targetSection | Day 4 持久化与比较 |
| `supplementIteration` | 成功提交 `CREATE_SUPPLEMENT_BRANCH` 后加 1；Policy 拒绝、回滚或人工停点不增加 | Day 4 待补 |
| `rewriteIteration` | 成功提交 `CREATE_REWRITE_BRANCH` 后加 1；静态旧 Writer 不再计入新闭环 | Day 4 待补 |
| `reviewIteration` | Reviewer 节点开始执行并持久化 attempt 时加 1，失败重试按真实 attempt 计数 | Day 4 待补 |
| `targetCoverageDelta` | `beforeOpenGapKeys - afterOpenGapKeys` 的大小；新增 URL 数量不参与判定 | Day 2-3 待补 gate |
| `diagnosisDelta` | 父 decision 的阻断诊断稳定键集合减去本轮集合；单纯分数变化不算改善 | Day 4 待补 |
| `projectedNextAction` | 由 allowed Policy result + mutation 确定性投影的只读字段 | Day 2 待补 |

所有新增或扩展的分析、诊断、决策和覆盖快照 Schema 必须保留 `sourceUrls`；缺少来源时显式记录 `MISSING_SOURCE`，不能伪造 URL。

#### Day 1 基线结果：目标所有者与不变量

| 职责 | 唯一所有者 | 不得承担的职责 |
|---|---|---|
| 质量事实与诊断 | `QualityReviewAgent` | 直接调度节点、产生 mutation |
| 整轮 candidate action 归一 | `OrchestrationDecisionAdapter` | Policy 许可、节点物化 |
| LLM 动作合法矩阵 | `OrchestrationDecisionActionMatrix` | 读取任务状态、执行 mutation |
| 执行许可与硬门禁 | `DecisionPolicyService` | 保存计划或节点 |
| decision -> mutation | `DecisionExecutorAdapter` | 提交事务、执行 Agent |
| mutation 原子提交 | `DynamicPlanAppender` 及其事务边界 | 重新解释 Reviewer 诊断 |
| 已提交 DAG 节点执行 | `DagExecutor` | 从 Reviewer 原始字段推导下一步 |
| decision/mutation/checkpoint 审计 | `OrchestrationTraceService` | 改变运行时选择 |

目标不变量（Day 1 尚未实现；Day 2-4 必须逐条转为通过性测试）：

- 同一 Reviewer cycle 最多产生一个 Policy-approved mutation。
- 同一 decisionId 最多成功提交一次 mutation。
- `APPEND_NODES` 成功意味着 `dynamicNodeCount > 0`，且 active plan、task currentPlanVersion、节点和 checkpoint 一致。
- `NO_MUTATION` 不得创建节点、增加 planVersion 或改变任务状态。
- `MARK_WAITING_INTERVENTION` 不得同时创建自动节点。
- Policy blocked 的 decision 只能产生 `NO_MUTATION`，不能由下游 fallback 偷换成自动动作。
- Writer 只能由 `CREATE_REWRITE_BRANCH` 或 target coverage gate 通过后的固定补采链触发。

#### Day 1 基线结果：目标契约与当前生产差异

| 能力 | 当前事实 | Day 2 迁移目标 |
|---|---|---|
| PASS | LLM Matrix/Policy/Executor 可形成 `NO_MUTATION`；无 sourceUrls 时会进入确认停点 | 保留 sourceUrls 红线，并统一初审/终审入口 |
| SUPPLEMENT | 可形成 `CREATE_SUPPLEMENT_BRANCH -> APPEND_NODES` | 增加 gapKey 字段并让初审真实进入该入口 |
| REWRITE | 可形成 `CREATE_REWRITE_BRANCH -> APPEND_NODES` | 删除静态 `review_failed` 的独立裁决 |
| MANUAL | 可形成 `MANUAL_ONLY -> MARK_WAITING_INTERVENTION` | 只允许 Java 硬门禁或 Policy 确认驱动 |
| RERUN | Legacy Adapter/Policy 可归一为 `CREATE_RERUN_BRANCH`，但默认确认后只形成 `MARK_WAITING_INTERVENTION`；LLM Matrix 无 RERUN 规则 | 增加受控 RERUN Matrix/Executor 映射并真实物化白名单 Extractor |
| 原子提交 | 当前派生计划保存早于节点物化，存在半提交窗口 | mutation 提交事务与独立失败收口事务 |
| target coverage gate | 当前动态补采链直接进入 Analyzer/Writer | 在 Extractor 后加入确定性 gate |

Day 1 测试证据分为两类：

1. 目标基线测试 `ReviewerRuntimeClosureDay1ContractTest`：跨越 Adapter、ActionMatrix、Policy 和 Executor，验证已支持映射、非法 pair 阻断、无来源 PASS 停点，并把 RERUN 当前缺口固定为 Day 2 必须迁移的断言。
2. 生产现状特征测试：
   - `DagExecutorTest.shouldAllowRewriteWhenInitialReviewRequiresHumanInterventionButHasNoBlockingDiagnosis` 证明 initial Reviewer 仍由静态 `review_failed` 直接触发 Writer。
   - `DynamicPlanAppenderTest.shouldCharacterizeInitialReviewBypassingRuntimeDecisionPipelineBeforeDayTwoMigration` 证明 initial Reviewer 不进入 RuntimeDecisionService。
   - `DagExecutorTest.shouldCreateDynamicBackflowPlanAndAppendDynamicNodesWhenFinalReviewFails` 从受控 Reviewer JSON 经真实 RuntimeDecisionService 走到动态节点，证明新控制面目前只覆盖终审路径。
   - `DynamicTaskGraphServiceTest.shouldCharacterizePlanPersistenceBeforeEmptyMutationMaterializationGuard` 证明空 RERUN mutation 仍会停用旧计划并保存无动态节点的派生计划，固定原子提交缺口。
   - `DynamicTaskGraphServiceTest.shouldCharacterizeRepeatedDecisionIdCreatingDuplicateDerivedPlansBeforeIdempotencyGuard` 证明相同 decisionId 当前会重复保存派生计划，固定幂等缺口。

这些特征测试用于保护迁移前事实，不代表认可旧行为。Day 2 完成时必须将它们改写为统一入口、拒绝半提交和受控 RERUN 的通过性测试。

### Day 2-3：修复 Reviewer 到运行时的控制闭环

- 当前状态：`DAY2_COMPLETED_DAY3_PENDING`
- 核心目标：让缺证据诊断实际触发补采/重跑，并阻止提前进入 Writer。
- 预期耗时：2 天。
- 前置依赖：Day 1 目标契约基线、生产差异和迁移门槛已确认。
- Day 2 范围说明：已完成 Reviewer 整轮诊断归一、静态 `review_failed` 旧裁决迁移、Policy-approved mutation 唯一命令、受控 RERUN、mutation 原子提交/幂等/失败收口；目标覆盖 gate 与固定 `Collector -> Extractor -> Analyzer -> Writer -> Citation -> Reviewer` 回流链仍保留为 Day 3 范围。
- 任务拆解：
  1. 建立所有 Reviewer 阶段共用的诊断归一化入口，复用现有 ActionMatrix、Policy、Executor 和 `DynamicPlanAppender`，删除初审/终审不同路由语义。
  2. 移除静态 `review_failed` 对 `passed/BLOCKER` 的独立裁决权；静态 DAG 不再直接根据 Reviewer 原始字段进入 `rewrite_report`，只消费 Policy 通过后的 mutation。
  3. 由唯一 mutation executor 处理 `APPEND_NODES` 和 `MARK_WAITING_INTERVENTION`；AgentSuggestion gate、静态 trigger 和其他组件不得部分执行同一命令。
  4. 将“派生计划保存、动态节点物化、节点持久化、旧计划失活、任务当前版本切换、checkpoint 写入”纳入同一 mutation 提交事务；任一步失败时该事务全部回滚。
  5. `APPEND_NODES` 若 nodeTemplates 为空、物化后节点为 0、目标节点不受支持或 checkpoint 写入失败，先回滚 mutation 提交事务，再由独立的失败收口事务幂等写入 `MUTATION_MATERIALIZATION_FAILED`、恢复位置和 `WAITING_INTERVENTION`；禁止静默 `continue` 或保留半成品 active plan。失败收口事务不得写入新 planVersion、动态节点或成功 checkpoint。
  6. 补齐 `CREATE_RERUN_BRANCH` 的真实运行时实现：当前只允许目标 `extract_schema` 或对应动态 Extractor；持久化重跑原因、输入来源快照、影响范围和父决策 ID；禁止重跑 Collector、整图或兄弟分支。
  7. 动态补采节点必须携带 competitor、targetField、requiredSourceType、gapKey、原因、父 decisionId、aiAuditTraceId 和允许来源约束。
  8. 在 Collector/Extractor 与 Analyzer/Writer 之间增加确定性的目标覆盖 gate，比较补采前后未满足 gapKey 集合；只有目标缺口关闭才允许继续分析和写作。
  9. 补采完成后按固定路径 `Collector -> Extractor -> target coverage gate -> Analyzer -> Writer -> Citation -> Reviewer` 回流；RERUN 固定从白名单 Extractor 进入 coverage gate，不允许形成任意图回路。
  10. 持久化 candidate action、Policy 结果、权威 mutation、实际下一节点、动态节点创建结果、事务结果和恢复位置。
- 完成标准：
  - 当投影 `nextAction=SUPPLEMENT_EVIDENCE` 时，权威 mutation 为 `APPEND_NODES/CREATE_SUPPLEMENT_BRANCH`，实际下一节点为动态 Collector，且目标覆盖 gate 通过前 Writer 调用次数为 0。
  - 当投影 `nextAction=RERUN_NODE` 时，实际下一节点为白名单 Extractor，不创建 Collector，不重跑整图。
  - mutation 物化失败时 active plan、task currentPlanVersion、动态节点和 checkpoint 均不产生部分提交。

### Day 4：增加目标收益、循环和任务级预算硬门禁

- 当前状态：`PENDING`
- 核心目标：防止没有目标覆盖收益的补采、重复改写/复审和超预算真实 Provider 调用。
- 预期耗时：1 天。
- 前置依赖：Reviewer 控制闭环可执行。
- 默认验收限制：
  - 最大补采次数：1。
  - 最大 Writer 改写次数：1。
  - 最大 Reviewer 次数：2；如现有初审/终审契约确需第三次，必须显式说明原因并设置上限为 3。
  - 单任务 token 硬预算：不高于 50,000；最终数值需结合当前模型上下文测量后冻结。调用前按 estimated input + max output 预留，调用后用 actual usage 对账；无 actual usage 时保留估算值并标记，不得按 0 处理。
  - 单次输入预估超过现有安全阈值、剩余任务预算不足、预算开关关闭或预算账本不可用时，fail-closed 拒绝调用。
  - 预算覆盖所有业务 Agent、Reviewer、Orchestrator、fallback 和 retry；禁止只保护 shadow 或单一 Provider。
- 必须触发人工介入的条件：
  - `targetCoverageDelta <= 0`，即本轮没有关闭任何父 decision 指定的 gapKey；仅新增 URL 或无关证据不能视为收益。
  - Reviewer 诊断集合没有减少，或只发生分数变化但阻断诊断未改善；质量分仅作为辅助事实，不单独决定收益。
  - 相同动作指纹重复出现。
  - 达到补采、改写、Reviewer 或 token 上限。
- 建议持久化停止原因：

```text
NO_TARGET_COVERAGE_DELTA
NO_DIAGNOSIS_DELTA
REPEATED_ACTION_FINGERPRINT
BUDGET_EXCEEDED
MAX_ITERATIONS_REACHED
MUTATION_MATERIALIZATION_FAILED
```

- 预算执行协议：
  1. 每次 Provider 调用前使用 taskId + callId 原子预留预计 token；预留失败则不发起外部调用。
  2. 成功、异常、超时和取消均在 finally 路径完成 actual 对账或释放未消费预留，重试按独立 attempt 计费。
  3. 任务恢复时从持久化预算账本重建 remaining budget，不允许因进程重启重置额度。
  4. `BUDGET_EXCEEDED` 必须先持久化停止原因和恢复位置，再把当前控制节点置为 `WAITING_INTERVENTION`。
- 完成标准：所有无限循环和无收益循环均能由确定性测试证明会停止；并发、异常、重试和重启均不能突破任务预算，任务记录可以解释停止原因。

### Day 5：Fake Provider 全链路验收

- 当前状态：`PENDING`
- 核心目标：零真实 Provider/Tavily 调用覆盖所有关键分支。
- 预期耗时：1 天。
- 前置依赖：Day 2-4 实现完成。
- 测试场景：
  1. 初审、终审和动态复审通过时均投影 `PASS`，任务正常收口且不产生 mutation。
  2. 仅存在表达问题时产生 `CREATE_REWRITE_BRANCH`，允许 Writer 改写后经过 Citation 和 Reviewer。
  3. 初审缺证据时动态追加 Collector；静态 `review_failed` 不得抢先触发 Writer。
  4. 已有可信来源但 Schema 缺口时产生 `CREATE_RERUN_BRANCH`，只重跑白名单 Extractor，不创建 Collector、不影响兄弟分支。
  5. 补采关闭指定 gapKey 后依次执行 Extractor、coverage gate、Analyzer、Writer、Citation 和 Reviewer。
  6. 补采只新增重复 URL、无关字段或错误竞品证据时，`targetCoverageDelta=0` 并进入 `WAITING_INTERVENTION`。
  7. 同一动作指纹重复、诊断无改善或达到循环上限时进入 `WAITING_INTERVENTION`。
  8. mutation 的节点物化、节点保存、任务版本切换或 checkpoint 任一步失败时，事务整体回滚并持久化 `MUTATION_MATERIALIZATION_FAILED`。
  9. 调用前预算不足、预算开关关闭或账本不可用时 Provider 调用计数为 0；成功、异常、超时、retry 和重启恢复均不突破任务上限。
  10. 同一 decisionId 重放时不重复追加 planVersion 或动态节点。
  11. 所有最终分析数据均包含非伪造的 `sourceUrls`，且 sourceUrls 与 gapKey/结构化字段的证据绑定可追溯。
- 核心断言：

```text
reviewer.output contains diagnoses and does not drive runtime directly
policy.normalizedAction == CREATE_SUPPLEMENT_BRANCH
mutation.type == APPEND_NODES
projectedNextAction == SUPPLEMENT_EVIDENCE
executedNextNode startsWith collect_revision_evidence_v
dynamicNodeCount > 0
targetCoverageGate == PASSED
rewriteInvocationCount == 0  // 目标覆盖 gate 通过前
```

- 回归要求：定向测试通过后执行 `mvn -pl backend test`，不得用忽略失败、跳过测试或修改无关契约的方式通过。
- 完成标准：定向契约测试、事务回滚测试、Fake Provider 全链路测试和完整 backend 回归全部通过，真实 Provider/Tavily 调用增量为 0。

### Day 6：一次受预算限制的真实 E2E

- 当前状态：`PENDING`
- 核心目标：验证真实环境中“Reviewer 诊断、权威 mutation、实际执行节点、目标覆盖增量、停止条件”保持一致。
- 预期耗时：1 天。
- 前置依赖：Day 5 全部测试和完整 backend 回归通过；预算开关已启用并经过测试；环境凭证只做安全存在性校验。
- 执行限制：
  - 只创建并执行一个新任务。
  - 禁止自动 resume、retry 整个任务或人工重复 rerun。
  - 超过硬预算或固定 deadline 立即停止。
  - 失败后保留现场，不通过临时修改 Prompt、阈值或数据库数据掩盖问题。
- 必须记录：
  - Reviewer 初审诊断、归一化 candidate action、Policy normalizedAction、权威 mutation 和投影 `nextAction`。
  - 实际执行的下一节点及其与 mutation 的一致性。
  - 动态节点新增数量。
  - 补采前后 gapKey 集合、targetCoverageDelta、evidence 和 `sourceUrls` 数量。
  - Writer 的调用时机和次数。
  - 初审、终审分数、阻断诊断集合及诊断变化。
  - 每次模型调用的 estimated reservation、actual/estimated usage、retry attempt 与任务剩余 token。
  - 最终状态和确定性停止原因。
- 允许的成功终态：

```text
补采成功 -> 指定 gapKey 关闭 -> 目标覆盖 gate 通过 -> 终审通过
```

或：

```text
指定 gapKey 未关闭 -> 禁止盲目改写 -> WAITING_INTERVENTION
```

- 完成标准：实际路径与权威 mutation 及其 `nextAction` 投影一致，mutation 无半提交，且预留与 actual 对账后的总成本没有突破冻结预算。

### Day 7：面试材料与项目冻结

- 当前状态：`PENDING`
- 核心目标：把实现和失败复盘转化为可展示的工程成果。
- 预期耗时：1 天。
- 前置依赖：Day 5 全部通过；Day 6 无论通过或诚实停止，都必须形成记录。Day 1-6 任一运行时硬门未满足时，不得用文档或演示材料宣称闭环完成。
- 交付内容：
  1. README：问题、系统边界、Agent 职责、运行链路、快速启动和已知限制。
  2. 架构图：Reviewer 决策、Orchestrator、动态节点、预算门禁和人工接管关系。
  3. ADR：为什么 LLM 负责诊断、确定性代码负责控制；为什么采用有限自治而不是无限自修复。
  4. Case Study：Task 7 Step 4 修复前后的路径、证据、分数、token 和终态对比。
  5. 三条可重复演示：正常收口、缺证据补采、失败后人工介入。
  6. 3-5 分钟演示脚本和面试追问题清单。
- 完成标准：材料只投影已经由测试和一次受限 E2E 证明的事实；新环境能够按 README 启动，Fake Provider 演示稳定，项目限制与失败模式不被隐藏。

## 6. 总体验收门槛

### 6.1 功能验收

- [ ] Reviewer 只输出诊断事实；Policy 通过后的 mutation 是唯一权威运行时命令，`nextAction` 仅由 mutation 确定性投影。
- [ ] 初审、终审和动态复审使用同一控制入口；静态 DAG 不再独立解释 Reviewer 原始字段。
- [ ] 缺来源时下一节点为动态 Collector；已有来源但结构化缺口时下一节点为白名单 Extractor；均不是 Writer。
- [ ] `CREATE_SUPPLEMENT_BRANCH`、`CREATE_RERUN_BRANCH`、`CREATE_REWRITE_BRANCH` 和 `MARK_WAITING_INTERVENTION` 均有真实、排他的执行语义。
- [ ] 符合策略的 `APPEND_NODES` 能真实创建并执行动态节点，成功时 `dynamicNodeCount > 0`。
- [ ] 计划、节点、任务版本和 checkpoint 原子提交；故障注入时不产生半成品 active plan 或孤儿节点。
- [ ] 指定目标覆盖 gate 通过前 `rewriteInvocationCount=0`。
- [ ] `targetCoverageDelta=0` 时停止；新增重复、无关或错误竞品证据不能制造改善。
- [ ] 诊断无改善或同一动作指纹重复时停止，不允许只用分数波动继续循环。
- [ ] 达到循环或预算上限时进入 `WAITING_INTERVENTION`。
- [ ] 分析数据继续强制保留 `sourceUrls`。

### 6.2 工程验收

- [ ] 关键状态转换有详细中文注释。
- [ ] Reviewer 诊断归一、动态计划物化、预算门禁保持单一职责。
- [ ] Reviewer 归一化、ActionMatrix、Policy、Executor、mutation 物化和 DAG 消费之间不存在重复裁决或双写。
- [ ] 同一 decisionId 的 mutation 具有幂等保护；任务重启后可以从 checkpoint、active plan 和预算账本恢复。
- [ ] Provider 和外部采集调用继续具备 try-catch、重试上限和审计记录。
- [ ] Fake Provider 覆盖通过、完整 backend 回归通过。
- [ ] 真实 E2E 最多执行一次，并有硬 token 预算。
- [ ] 计划、进度、失败原因和恢复位置均持久化可追溯。

### 6.3 面试验收

- [ ] 能在 3-5 分钟内演示一条确定性路径。
- [ ] 能解释为什么多 Agent 不等于有效协作。
- [ ] 能用 Task 7 Step 4 说明“决策正确但执行错误”的根因。
- [ ] 能展示修复前后动态节点、证据增量和 token 成本对比。
- [ ] 能明确说明系统是受控半自动工作流，而不是无限自治 Agent。

## 7. 统一进度记录模板

每天开始和结束时，在本文件末尾追加一次记录，禁止覆盖历史记录：

```markdown
### YYYY-MM-DD Day N 进度记录

当前阶段：[正在进行的阶段]
- [x] 信息采集：已完成
- [ ] 数据分析：执行中
- [ ] 报告撰写：待执行
- [ ] 质检复核：待执行

计划进度：N/7 天
当前步骤：[步骤名称]
步骤状态：成功 / 失败 / 执行中 / 待执行
已完成占比：XX%
剩余步骤：[步骤列表]
实际耗时：[时间]
依赖状态：[满足 / 阻塞及原因]
真实 Provider/Tavily 调用增量：[数量]
token 预留 / actual / estimated 增量：[数量]
任务剩余 token：[数量]
gapKey 关闭数量 / targetCoverageDelta：[数量]
发现问题：[事实]
处理结果：[结果]
下一步唯一动作：[动作]
```

## 8. 面试表达基线

修复前事实：

```text
Reviewer 能发现证据缺口，但诊断没有控制运行时；
动态节点新增为 0；Writer 在没有新证据时改写；
质量分 37 -> 38；任务消耗 1,731,038 actual token；
最终依靠 WAITING_INTERVENTION 诚实停止。
```

本任务期望形成的修复后表达：

```text
Reviewer 只负责诊断，Policy 通过后的 mutation 是唯一运行时命令；
证据不足时 Writer 被目标覆盖 gate 硬阻断，动态补采可执行、可审计；
指定缺口没有关闭或阻断诊断没有改善时自动停止；
mutation 原子提交，循环次数和任务级 token 预算均由确定性代码限制；
系统定位为可追溯、可人工接管的半自动 Agent 工作流。
```

## 9. 当前状态

当前阶段：Day 3 目标覆盖 gate 与固定回流链已完成，并已通过完整 backend 回归；等待进入 Day 4 收益、循环与预算硬门禁。

- [x] 信息采集：Task 7 Step 4 基线问题已整理
- [x] 数据分析：双控制面、mutation 非原子、RERUN 缺失、目标覆盖和预算根因已明确
- [x] Day 2 代码实现：Reviewer 诊断归一、旧契约迁移、受控 mutation 与事务收口已完成
- [x] Day 3 代码实现：确定性 target coverage gate、固定动态回流链、RERUN 白名单回流已完成
- [x] 质检复核：Day 3 定向测试、完整 backend 回归、`git diff --check` 已完成

计划进度：`3/7 天（43%）`

下一步唯一动作：执行 Day 4，补齐收益、循环与预算硬门禁；继续禁止真实 Provider/Tavily 任务，直到 Fake Provider 全链路验收通过。

### 2026-07-21 Day 1 目标契约基线确认记录

当前阶段：Day 1 目标契约、生产现状差异与迁移门槛已确认；生产运行时迁移未开始
- [x] 信息采集：已核对 Reviewer、ActionMatrix、Policy、Executor、mutation、静态 DAG 和动态物化入口
- [x] 数据分析：已确认目标单一决策链、优先级、字段定义、目标所有者和目标运行时不变量
- [x] 报告撰写：Day 1 目标基线、当前生产差异和迁移门槛已写入本文档
- [x] 质检复核：新增跨层目标基线测试、生产入口特征测试和半提交特征测试，并完成相关回归

计划进度：1/7 天
当前步骤：Day 1 目标契约基线与生产差异确认
步骤状态：成功
已完成占比：14%
剩余步骤：Day 2-3 运行时闭环、Day 4 收益与预算门禁、Day 5 Fake Provider 验收、Day 6 受限真实 E2E、Day 7 材料冻结
实际耗时：本次 Codex 会话约 45 分钟。该数字表示在已有 Stage 2 实现、Task 7/9 复盘和既有测试基线上完成审计、增量测试与文档修订的墙钟时间，不代表从零人工设计、编码和评审所需工时；面试材料不得将其包装为完整功能开发耗时。
依赖状态：满足；现有 Reviewer、ActionMatrix、Policy、Executor 和测试基线可用
真实 Provider/Tavily 调用增量：0
token 预留 / actual / estimated 增量：0 / 0 / 0
任务剩余 token：Day 1 未启用真实任务预算账本，不适用
gapKey 关闭数量 / targetCoverageDelta：Day 1 仅确认目标定义，未执行补采，不适用
测试结果：
- 首次新增契约测试：5 tests / 1 failure / 0 errors；PASS fixture 错误使用空 `sourceUrls`，命中现有缺来源确认门禁
- 修正 fixture 并增加“无来源 PASS 必须停点”断言后：5 tests / 0 failures / 0 errors
- 最终相关回归：60 tests / 0 failures / 0 errors / 0 skipped
- 回归范围：目标基线测试、ActionMatrix、Legacy Adapter、Policy、Executor、DynamicPlanAppender、DynamicTaskGraphService，以及 DagExecutor 初审静态 Writer/终审真实动态回流两条生产路径
发现问题：
- LLM ActionMatrix 尚无 RERUN 规则
- Legacy Adapter/Policy 可归一 `CREATE_RERUN_BRANCH`，但默认确认后 Executor 只产生 `MARK_WAITING_INTERVENTION`，没有 RERUN 节点
- 初审仍由静态 `review_failed` 解释，只有终审进入 `DynamicPlanAppender`
- 动态计划保存早于节点物化，mutation 尚无原子提交边界
- 动态补采链尚无 target coverage gate
处理结果：上述问题均未通过 Day 1 局部补丁掩盖；目标行为由基线测试表达，当前错误行为由迁移前特征测试复现，已作为 Day 2-4 的明确迁移入口
恢复位置：
- 统一入口：`DynamicPlanAppenderTest.shouldCharacterizeInitialReviewBypassingRuntimeDecisionPipelineBeforeDayTwoMigration`
- 静态旧控制面：`DagExecutorTest.shouldAllowRewriteWhenInitialReviewRequiresHumanInterventionButHasNoBlockingDiagnosis`
- RERUN：`ReviewerRuntimeClosureDay1ContractTest.shouldExposeRerunAsDayTwoGapInsteadOfPretendingItAlreadyProducesAppendNodes`
- 原子提交：`DynamicTaskGraphServiceTest.shouldCharacterizePlanPersistenceBeforeEmptyMutationMaterializationGuard`
- decisionId 幂等：`DynamicTaskGraphServiceTest.shouldCharacterizeRepeatedDecisionIdCreatingDuplicateDerivedPlansBeforeIdempotencyGuard`
下一步唯一动作：Day 2 首先实现所有 Reviewer 阶段共用的整轮诊断归一入口，并以 Policy-approved mutation 替换静态 `review_failed` 的直接 Writer 路由

### 2026-07-21 Day 2 运行时闭环迁移开始记录

当前阶段：Day 2 目标范围与工程基线已核对，正在以契约测试驱动运行时迁移
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 代码实现：执行中
- [ ] 质检复核：待执行

计划进度：2/7 天
当前步骤：补充 Day 2 目标契约测试
步骤状态：执行中
已完成占比：20%
剩余步骤：整轮诊断归一、初审/终审统一入口、受控 RERUN、mutation 原子提交与幂等、定向及完整回归
预期耗时：约 4 小时（基于当前工程状态的本轮实施估算）
依赖状态：满足；Day 1 目标契约、生产差异和迁移前特征测试均已存在
真实 Provider/Tavily 调用增量：0
token 预留 / actual / estimated 增量：0 / 0 / 0
任务剩余 token：未启用真实任务预算账本，不适用
gapKey 关闭数量 / targetCoverageDelta：Day 2 不执行真实补采，暂不适用
发现问题：初审仍由静态 `review_failed` 直接裁决；整轮诊断没有单一归一入口；RERUN 默认停在人工确认；派生计划在节点物化前保存且相同 decisionId 可重复提交
处理结果：待实现并由 Day 2 通过性测试验证
下一步唯一动作：先建立整轮 Reviewer 诊断归一契约，再迁移所有 Reviewer 阶段到同一运行时入口

### 2026-07-21 Day 2 运行时闭环迁移完成记录

当前阶段：Day 2 功能实现、旧契约迁移、定向测试和完整 backend 回归已完成；等待进入 Day 3 目标覆盖 gate
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 代码实现：已完成
- [x] 质检复核：已完成

计划进度：2/7 天
当前步骤：Day 2 运行时闭环迁移收口
步骤状态：成功
已完成占比：29%
剩余步骤：Day 3 目标覆盖 gate 与固定回流链、Day 4 收益/循环/预算硬门禁、Day 5 Fake Provider 全链路验收、Day 6 受限真实 E2E、Day 7 材料冻结
实际耗时：本次收尾验收约 6 分钟，其中完整 backend 回归耗时 338 秒；Day 2 完整研发耗时以连续会话记录为准，不在此重新估算或包装。
依赖状态：满足；Day 1 目标契约、迁移前特征测试、Day 2 代码实现和旧契约迁移均已存在
真实 Provider/Tavily 调用增量：0
token 预留 / actual / estimated 增量：0 / 0 / 0
任务剩余 token：未启用真实任务预算账本，不适用
gapKey 关闭数量 / targetCoverageDelta：Day 2 未执行真实补采，未产生运行时覆盖增量；补采诊断字段已新增 competitor、targetField、requiredSourceType、gapKey，目标覆盖 gate 保留为 Day 3
测试结果：
- 决策层与受控验收：65 tests / 0 failures / 0 errors
- 四组工作流集成：6 tests / 0 failures / 0 errors
- H2 mutation 事务回滚：1 test / 0 failures / 0 errors
- 完整 backend 回归：`mvn -pl backend test`，1423 tests / 0 failures / 0 errors / 10 skipped
- 工作区空白校验：`git diff --check` 退出码 0；仅存在 LF 将被替换为 CRLF 的换行提示
已落地能力：
- Reviewer 初审、终审、动态复审统一进入整轮 Java 诊断归一入口
- 静态 `review_failed` 不再拥有 Reviewer 原始字段的独立裁决权
- Policy 批准后的 mutation 是唯一运行时命令，`nextAction` 由最终 mutation 确定性投影
- `SUPPLEMENT_EVIDENCE`、`REWRITE`、白名单 `RERUN_NODE` 均具备实际节点语义
- `RERUN_NODE` 仅允许从 `extract_schema` 或动态 Extractor 开始，不重跑 Collector 或整图
- `CompensationGraphAssembler` 的增量变更属于 Day 2 受控范围：用于把 `CREATE_RERUN_BRANCH` 物化为单个动态 Extractor 起点，并显式把 coverage gate 与后续固定回流链留到 Day 3
- mutation 提交具备事务边界、decisionId 幂等和空物化保护；checkpoint 失败会回滚派生计划、动态节点和任务版本
- mutation 物化失败通过独立事务收口到 `WAITING_INTERVENTION`
审查关注项确认：
- Day 2 不能宣称完整闭环已经生效；面试表达统一为“控制面已统一，执行面还差 target coverage gate 和固定回流链一段”
- `CompensationGraphAssembler` 的 +25 行不是附带修复，而是白名单 `RERUN_NODE` 实际节点语义的物化入口
- H2 mutation 事务回滚测试使用 `@DataJpaTest`、`@Import(DynamicPlanMutationCommitter.class)` 和 `@Autowired` Spring Bean，并新增 AOP 代理断言；`DynamicPlanAppender` 的轻量构造器仅用于纯单元测试，不作为事务语义证明
发现问题：Day 3 的 target coverage gate 和固定 Collector -> Extractor -> Analyzer -> Writer -> Citation -> Reviewer 回流链尚未实施；本次未混入 Day 2 范围
处理结果：Day 2 可按全量回归证据完成验收；后续不得使用 Day 2 记录宣称 Day 3 gate、Day 4 预算硬门禁或 Day 5 Fake Provider 全链路已完成
下一步唯一动作：进入 Day 3，先实现 target coverage gate，再固化补采成功后的受控回流链；继续保持真实 Provider/Tavily 调用增量为 0

### 2026-07-22 Day 3 目标覆盖 gate 与固定回流链完成记录

当前阶段：Day 3 目标覆盖 gate、固定动态回流链、RERUN 白名单回流和回归验证已完成
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 代码实现：已完成
- [x] 质检复核：已完成

计划进度：3/7 天
当前步骤：Day 3 目标覆盖 gate 与固定动态回流链收口
步骤状态：成功
已完成占比：43%
剩余步骤：Day 4 收益/循环/预算硬门禁、Day 5 Fake Provider 全链路验收、Day 6 受限真实 E2E、Day 7 材料冻结
实际耗时：本次 Day 3 收尾验证约 6 分钟；其中完整 backend 回归耗时 287 秒。Day 3 完整研发耗时以连续会话记录为准，不在此重新包装。
依赖状态：满足；Day 1 目标契约与 Day 2 运行时统一控制面已存在
真实 Provider/Tavily 调用增量：0
token 预留 / actual / estimated 增量：0 / 0 / 0
任务剩余 token：未启用真实任务预算账本，不适用
gapKey 关闭数量 / targetCoverageDelta：成功路径按目标 gapKey 关闭并输出 `targetCoverageDelta=1`；无关证据路径输出 `targetCoverageDelta=0` 并停在 `WAITING_INTERVENTION`
测试结果：
- 目标覆盖 gate 单测：2 tests / 0 failures / 0 errors
- 动态图装配测试：4 tests / 0 failures / 0 errors
- DynamicPlanAppender 回归：8 tests / 0 failures / 0 errors
- DagExecutor 回归：35 tests / 0 failures / 0 errors
- Spring 集成链路：OrchestrationRuntimeFeedbackSmokeTest 2 tests、Phase4WorkflowIntegrationTest 1 test，均 0 failures / 0 errors
- Day 3 相关集合：52 tests / 0 failures / 0 errors
- 完整 backend 回归：`mvn -pl backend test`，1428 tests / 0 failures / 0 errors / 10 skipped
- 工作区空白校验：`git diff --check` 退出码 0；仅存在 LF 将被替换为 CRLF 的换行提示
已落地能力：
- 新增确定性 `TargetCoverageGateEvaluator`，只消费 gate 配置和上游 Extractor 输出，不调用外部 Provider、Tavily 或 LLM
- `SUPPLEMENT_EVIDENCE` 动态链固定为 `Collector -> Extractor -> target coverage gate -> Analyzer -> Writer -> Citation -> Reviewer`
- `RERUN_NODE` 动态链固定从白名单 Extractor 开始，再进入同一 target coverage gate 与后续固定回流链；不重跑 Collector 或整图
- target coverage gate 通过前，Analyzer/Writer/Citation/Reviewer 不会执行；无关证据或 `targetCoverageDelta=0` 时确定性进入 `WAITING_INTERVENTION`
- 动态 rewrite-only 分支补齐 Citation，Reviewer 依赖 Citation 结果继续复核
- `target_coverage_gate_v*` 虽复用 `REVIEWER` 节点类型承载，但不进入 Reviewer 决策周期，避免被 DynamicPlanAppender 当成新的复核回流
发现问题：真实收益增量、重复动作指纹、循环上限和任务级预算硬门禁仍属于 Day 4；本次未宣称已完成预算或 Fake Provider 全链路验收
处理结果：Day 3 可按定向测试、两条 Spring 集成链路和完整 backend 回归完成验收；继续保持真实 Provider/Tavily 调用增量为 0
下一步唯一动作：进入 Day 4，实现 targetCoverageDelta/diagnosisDelta 收益判定、动作指纹去重、循环上限和 token 预算硬门禁
