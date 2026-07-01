# Task66-11 字段证据查询执行预算 + 可观测能力 改动计划

> 2026-07-01。本计划**只写方案，写完待用户确认后再执行**（先写失败单测再改生产代码）。
> 编号续 10。定位：**执行序 08→09 之后、10（主从反转）之前的必做前置能力**，不是症状修复。

---

## 0. 一句话结论

task78 复跑暴露的"53 条 field query 串行跑 10 分钟到不了 SELECT_TARGETS"，
**不是 09 的 bug**（09 单测全绿、逻辑正确），是 08+09 把第三方 query 全部激活后，
把执行层三个既存缺陷顶爆了：**query 数量无全局上限 + 串行执行 + 循环内无时间/配额预算与可观测**。

修法**刻意不用魔数 `cap=N` 截断**——那个数按今天 official-first 量级拍，plan 10 反转成 search-first 后量级变大、
魔数立即失效，问题在 10 复发（= 用户点名要避免的"根因能力被反复后移"）。
本计划把它修在**执行预算 + 可观测**这一能力层：循环知道自己有多少预算、超了自我熔断、跳过了什么可见。
该能力**不依赖 query 数量**，无论 10 之后灌多少量都自限，因此 10 不复发。
且这正是 memory `project-closure-direction-and-state` 里"运行时可见性镜子"这个**最高 ROI 新建议**的落地。

---

## 1. 根因三层（已读码定位，2026-07-01）

| 层 | 病灶 | 位置 |
|---|---|---|
| ① query 数量无全局上限 | `allPlannedQueries()` 把每个 required 字段的 plannedQueries 全量 flat-map，无 cap、无跨字段去重。09 Task6 放开第三方 optional 路径后 39→53 继续膨胀。`FieldEvidenceQuery.priority` 字段存在但没被用来排序/截断 | `DimensionEvidencePlan.java:43-52`；`FieldEvidenceQuery.java:31` |
| ② 串行执行 + 每条最长超时 | `searchFieldEvidenceQueries` 逐条 `client.search(profile)` 阻塞调用，无并行、无批量、无循环内熔断。单条 Tavily 超时默认 45s | `TavilyFastLaneProvider.java:150-168`；`TavilySearchClient.java:128` |
| ③ 有"预算"概念却只放行不约束 | `ensureMinimumTimeoutForFieldEvidenceQueries` 按 `12s + 6s×query数` **放大** phase 超时（53 条→330s），把节点放行去跑长循环；但循环内部**不读这个预算、不自我熔断**。全局超时只在 phase 之间检查（`isTimedOut` at :409），循环内不检查 | `SearchPolicyResolver.java:170-178`；`SearchExecutionCoordinator.java:409`、`:910-911`、`:967-969` |

补充：候选池早退闸门（`:910-911`）在 pending field query 时被 `&& !pendingFieldEvidenceQueries` **主动关掉**（08 为不饿死 field query 故意加），
唯一 field-aware break（`:967-969`）发生在整个串行 `search()` **跑完之后**，挡不住这个长循环。

---

## 2. 设计原则（决定这是根因修复而非症状修复）

1. **约束依据是"预算"（时间 + 配额），不是"数量魔数"。** 预算随节点时间预算派生，架构反转后自动适配，不失效。
2. **截断要保价值，且不污染"完整计划读取口"：** 用 `FieldEvidenceQuery.priority` 排序后，在预算内优先执行高价值 query；
   被截断/跳过的不是随意丢，而是保留为独立的 skipped 集合。**关键：不在 `allPlannedQueries()` 里做截断**——
   它是"完整计划读取口"，截断会让 planned/skipped 的审计来源一起丢失。截断动作放 coordinator 层新方法（见 §3-A1）。
3. **一切自限动作必须可观测：** 每条 query 发没发、耗时多少、哪些因预算被 skip、循环为何提前结束，都要落成结构化信号（step message / audit / 输出字段），进 nodes 快照可见。这是"运行时可见性镜子"。
4. **不动并发模型（C 观望）：** 本轮不引入并行 `client.search`，避免牵动 Tavily 速率限制与线程模型。A+B 先把"自限 + 可见"做扎实，用 A+B 跑出的耗时数据再评估 C 是否必要。
5. **边界（防无底洞）：** 不重建 planner/scorer/gate，不碰 plan 10 的路由主从，不追求"每字段必命中"。只加"执行预算闸门 + 可观测"。

---

## 3. 改动方案（A + B 组合，C 观望）

### 档 A：query 执行计划分层（planned / executable / skipped）（治本控数量）
**目标**：把"无上限全量透传"改成"完整 planned 保留 + 按预算派生配额挑出 executable + 明确 skipped 三份结果"，
配额由预算算出、不是硬编码魔数，且不破坏审计来源。

- A1. **不改 `DimensionEvidencePlan.allPlannedQueries()`**（它是完整计划读取口，保留全量 planned 作审计来源）。
  在 coordinator 层新增 `resolveExecutableFieldEvidenceQueries(config)`，返回一个轻量结果对象，含三份：
  - `planned`：`allPlannedQueries()` 全量原样（审计基线）。
  - `executable`：按 `FieldEvidenceQuery.priority` 稳定排序（priority 小=优先，null 垫底）后，按配额取前 N 条。
  - `skipped`：executable 之外的余量，带 skip 原因 `SKIPPED_OVER_BUDGET`（供 O2/O3 落审计）。
  - `buildSearchSourceRequest` 改用 `executable` 透传给 provider（替换现在 `resolveFieldEvidenceQueries` 的全量透传）。
- A1-预算口径（**必须明确，否则截不断**）：配额 = `节点搜索预算 / 单条预估耗时`。
  - 分子用**放大前**的预算：`SearchPolicyResolver.resolveSearchTimeoutMillis(configured, executionPlan)`
    （= 节点显式配置，或 `expectedNodeDuration × 0.6`），或剩余 phase 时间，或独立的 field-query budget。
  - **绝对不能用** `ensureMinimumTimeoutForFieldEvidenceQueries()` 放大后的值（53 条→330s）反推——
    330s / 6s = 53，等于没截断（这正是当前 bug 的自我循环）。
  - 分母用单条预估耗时常数（可复用/独立于 `FIELD_QUERY_PER_QUERY_TIMEOUT_MILLIS`，但**分子分母不能同源于被放大的预算**）。
- A2. 跨字段去重（可选，若实测重复率高）：`allPlannedQueries` 目前只在单字段内按 fingerprint 去重，跨字段不去重，
  可能有等价 query 重复占预算。在 `resolveExecutableFieldEvidenceQueries` 内按 fingerprint 全局去重，保留 priority 最高的一条（planned 仍留全量）。

### 档 B：循环内执行预算闸门 + 熔断（兜底防极端）
**目标**：即使 A 之后 query 数仍多，循环也能读预算、超预算自我熔断，不把节点吊死。

- B1. `TavilyFastLaneProvider.searchFieldEvidenceQueries` 循环内接入"剩余预算"检查：
  - 传入执行 deadline（由 coordinator 从 §3-A1 放大前预算派生并下传）。
  - **每条执行前**检查 remaining budget：若不足以容纳"一条最坏耗时"，**直接跳过、不启动下一条长请求**（标 `SKIPPED_BUDGET_EXHAUSTED`）。
    仅在每条前检查 deadline 不够——因为单条 Tavily 最坏耗时 = `timeoutSeconds(45s) × (maxRetries(2)+1) ≈ 135s`
    （`TavilySearchProperties.timeoutSeconds=45`、`maxRetries=2`），启动一条就可能直接击穿整个剩余预算。
  - 保持现有 fail-open（单条失败不停循环）语义不变。
- B1-a. **给 Tavily 请求加 per-query timeout override**：当剩余预算 < 单条默认最坏耗时（135s）但仍 > 0 时，
  用 `min(默认 timeout, 剩余预算)` 作为本条请求的超时上限下传给 `TavilySearchClient`（需支持 per-call timeout 覆盖），
  避免"还有 20s 预算却启动一个可能跑 135s 的请求"。若无法覆盖 timeout，则退化为"预算不足直接跳过"。
- B2. `SearchExecutionCoordinator` 在进入 supplement 前把 field-query 执行 deadline 算好、下传给 provider（新增 request 字段或参数）。
  预算口径与 §3-A1 一致（放大前），与 `ensureMinimumTimeoutForFieldEvidenceQueries` 的放大值区分开。
- B3. 循环熔断后，coordinator 正常推进到 `SELECT_TARGETS`（关键：保证到得了选源，让 09 的 prefetch 选源分支真正被执行到）。

### 可观测（贯穿 A/B，这是根因能力的核心，不是附加）
- O1. `searchExecutionPlan` 的 `BROWSER_SUPPLEMENT_SEARCH` step message 增加：计划 N 条 / 执行 M 条 / 跳过 K 条（原因）/ 累计耗时。
- O2. collect 节点输出 + `SearchExecutionTrace` 新增结构化字段：现有 trace 已有 `fieldEvidenceQueryCount/Fields/Paths`（`SearchExecutionTrace.java:77-79`），
  在其后追加 `fieldEvidenceQueryPlannedCount` / `ExecutedCount` / `SkippedCount` / `SkipReasons` / `fieldEvidenceExecMillis`。
- O3. **per-query 审计需接口改造，明确落点**：`SearchSourceProvider.search()` 目前只返回 `List<SourceCandidate>`（`SearchSourceProvider.java:37/47`），
  无处挂 per-query 明细；`SearchExecutionTrace` 也只有聚合计数。方案二选一（倾向前者，改动更小）：
  - **(推荐) 扩展现有 `TavilyFastLaneAudit`**（`search/tavily/TavilyFastLaneAudit.java`，已有 `queriesSent`/`tavilyRequestIds`/`totalResults`）：
    新增一个轻量 `List<FieldEvidenceQueryExecutionAudit>`（每条：query 指纹、发没发、耗时、命中数、失败/skip 原因），随 audit 上抛。
  - 或新建独立 `FieldEvidenceQueryExecutionAudit` DTO，挂到 `SearchExecutionTrace`（需打通 provider→coordinator 的 audit 回传通道）。
  - task78 快照里 `searchAudit=null` 说明这条回传链路本就没接通，本项一并补上。

### 档 C：受限并行执行（本轮观望，不实施）
**为何观望**：并行 `client.search` 收益大（53 条并发 4-6 路可把耗时压到 1/4~1/6），但牵动 Tavily 速率限制、
线程池、错误聚合、以及和 B 的预算闸门如何协同。**先用 A+B 跑出真实耗时数据**，再判断：
- 若 A（控数量）+ B（熔断）后耗时已回落到可接受（比如单节点 field query 阶段 < 60~90s），C 优先级下调、可不做。
- 若数量已难再压、但单条 45s 仍是瓶颈，再开 C，且必须复用 B 的预算口径（并行不等于无预算）。
- C 的实施前置：确认 Tavily 并发速率限制、给 `TavilySearchClient` 加受限并发（如 Semaphore/固定线程池 4-6）、错误 fail-open 聚合。

---

## 4. 测试策略（先写失败单测，红→绿）

- T1（档 A 分层）：构造 40+ 条含 priority 的 planned queries + 一个小预算，断言 `resolveExecutableFieldEvidenceQueries` 返回的
  `executable` 按 priority 排序且截断到配额内、`planned` 仍为全量、`skipped` = 余量且带 `SKIPPED_OVER_BUDGET`。**先 FAIL 后 PASS。**
- T1-b（预算口径防呆）：断言配额基于**放大前**预算计算——给一个会被 `ensureMinimumTimeoutForFieldEvidenceQueries` 放大的场景，
  断言 executable 数量**不等于**全量（防止用 330s 反推导致截不断的回归）。
- T2（档 A 跨字段去重，若做）：两字段生成 fingerprint 相同的 query，断言 executable 只留一条且保留高 priority、planned 仍全量。
- T3（档 B 循环熔断）：mock `client.search` 每条耗时超预算，断言循环在 deadline 后停、剩余标 `SKIPPED_BUDGET_EXHAUSTED`、已执行结果保留。
- T3-b（档 B1-a 不启动击穿请求）：设剩余预算 < 单条最坏耗时（135s），断言要么用 per-query timeout override 收紧本条，
  要么直接跳过、**不启动**下一条长请求。
- T4（档 B 到得了选源）：断言熔断后 coordinator 仍推进到 `SELECT_TARGETS`（用现有 `SearchExecutionCoordinatorTest` 扩展）。
- T5（可观测）：断言 step message / trace 新字段 / `TavilyFastLaneAudit` 的 per-query 明细包含 planned/executed/skipped 计数与原因。
- 回归：`FieldEvidenceQueryPlannerTest`、`CollectionTargetSelectorTest`、`SearchExecutionCoordinatorTest`、`SourceCandidateRankerTest`、`TavilyFastLaneProvider`/`TavilyFastLaneAudit` 相关全绿。

---

## 5. 端到端验收（复用 task77/78 场景重跑）

- V1. 重跑抖音 vs B站（09 验收同款），确认 4 个 collect 节点**全部**推进到 `SELECT_TARGETS` 与 `COLLECT_PAGES`，不再卡死。
- V2. 确认 field query 阶段耗时大幅下降（记录实测值，为 C 是否要做提供数据）。
- V3. **顺带完成 09 遗留验收**：确认 `selectionReason=Tavily prefetch 正文可用` 至少出现一次（09 的 prefetch 选源正样本，此前因节点卡死从未跑到）。
- V4. 确认新增可观测字段在 nodes 快照可见（planned/executed/skipped）。

---

## 6. 与整体路线的关系（回应"根因能力被反复后移"）

- **为什么不先做 plan 10**：plan 10 把路由反转成 search-first，会让**更多** query 走这条串行循环，
  在没有执行预算的引擎上盖 search-first = 必然在 10 复发。执行序 08→09→**11**→10 是对的：先把执行引擎能自限，再反转主从。
- **为什么这是根因而非症状**：同一 bug，魔数 cap 修法会在 10 失效（症状）；预算+可观测修法不依赖 query 数量（根因）。
  决定复不复发的是**修法层次**，不是修的时机。
- **兑现最高 ROI 项**：memory `project-closure-direction-and-state` 第 2 项"运行时可见性镜子"——
  让"每条 query 发没发、每 stage 执没执行、候选在哪步被丢"可见。本计划的可观测部分即其首块落地。
- 仍不开 4.x、不碰 Orchestrator/LLM 大脑（那是方面一，排在地基之后）。

---

## 7. 执行顺序

1. 写 T1（档 A）失败单测 → 实现 A → 绿。
2. 写 T3/T4（档 B）失败单测 → 实现 B → 绿。
3. 写 T5（可观测）→ 实现 O1-O3 → 绿。
4. 全量回归。
5. 端到端复跑（§5），记录耗时数据。
6. 依 V2/V5 数据评估档 C 是否推进，写入本文档 §3-C 的结论。

---

## 8. 相邻但不属于本计划的小补丁（单列，勿混入）

- **`PublicEvidenceRecoveryService.resolvePublicPaths`（`PublicEvidenceRecoveryService.java:321`）壳页兜底归属闸门问题仍在。**
  该方法按上下文拼 `/about /help /download /app`、`/docs /help /guide /api`、`/pricing /plans /billing` 等**顶层壳路径**，
  是"壳页兜底"的来源之一。它和 plan 11 的执行吞吐**不是同一根因**（plan 11 是"query 太多串行跑不完"，这里是"补源拼壳路径"）。
- **处理方式**：不塞进本计划（避免 plan 11 又变无底洞），作为**紧邻的独立小补丁**处理——
  给壳路径兜底加"竞品域名归属信号"闸门（与 08 的 B 闸门 `hasCompetitorDomainOwnershipSignalForCandidate` 同源思路），
  或降低壳路径优先级、让 field query 真文优先。单独开 problem/patch 文档记录，不占用 plan 11 的 §3-§7。
