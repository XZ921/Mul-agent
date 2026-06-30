# Task66-09 选源认 prefetch 正文：让 BOOTSTRAPPED 候选可入选 实施计划

> 2026-06-30。本计划替代已作废的 08a，基于真根因诊断
> `docs/Tavily/problem/2026-06-30-task69-prefetch-evidence-dropped-at-selection-diagnosis.md`。
> **执行顺序里的第 2 步（编号 09）**，前置是 08（先让第三方候选进池），本步保证"进来的丰富候选不被选源丢弃"。
> 执行方式：`master` 直接改、用户自行提交、过程不创建 commit、逐 Task 勾 checkbox。

**目标：** 让带 Tavily prefetch 正文的 `BOOTSTRAPPED` 候选能通过 `CollectionTargetSelector` 的入选判定，
从而被打包给已存在的 `TavilyPrefetchedExecutor` 取回正文落库——而不是被当"未验证候选"丢弃，只留根域壳页。

**范围铁律（2026-06-30 复核修正）：** 只改 `CollectionTargetSelector` 一个类，但要改**三件事**——
**入选判定（resolveEligibility）+ 排序优先级（resolveSelectionTier）+ 回填审计文案（applySelectionResult）**。
只改入选会被排序抵消、被审计文案误写，导致"代码改了、2049 字正文仍没进库"。
不碰 family 主从、不碰短路逻辑、不新增 gate、不改下游消费链（已存在且可用）、不引入 registry 依赖。

---

## 0. 前置事实（已读代码 + 数据库确认）

1. **真根因**：2049 字真文档候选 `discoveryMethod=TAVILY_PHASE1_BOOTSTRAP`、`fastLaneUsable=true`、
   `prefetchedRawContentLength=2049`、`verified=null`、`selectionStage=BOOTSTRAPPED`。
   在 `CollectionTargetSelector.resolveEligibility` 中：
   - `hasUsableCollectedPage`（230-236 行）只看 `collectedPage.content/snippet`，prefetch 正文不在此 → 判"无正文"。
   - `isExplicitCandidate`（223-228 行）白名单无 `TAVILY_PHASE1_BOOTSTRAP` → 非 explicit。
   - `verified=null` → 不进 verified 入选分支。
   - 结果落到"未验证候选不能进入正式采集"被丢弃。
2. **下游消费链已存在且可用（关键，决定本计划只需改选源）**：
   - `CollectionTaskPackageBuilder.resolvePrimaryTool`（104-107 行）：候选若
     `fastLaneUsable=true && hasPrefetchedContent=true && prefetchedContentRef 非空` → 主工具 `TAVILY_PREFETCHED`。
   - `TavilyPrefetchedExecutor` 从 `TavilyPrefetchedContentRegistry` 按 ref 取回完整正文。
   - **即：只要该候选进入 selectedTargets，正文就能被取回落库。缺的只是选源放行。**
3. **不是被短路**：task69 的 recovery/repair/字段再采全部触发（见诊断文档 §1），无需动那些路径。
4. **排序会抵消入选（复核新增，必须一起改）**：`resolveSelectionTier`（289 行）`verified=true → tier 0`、
   未验证 → tier 1。`targetCount=1` 时根域 verified 壳页占 tier 0 先被选，prefetch 真文（tier 1）被挤掉。
   **只改入选不改排序，task69 仍会失败。**
5. **审计文案会被覆盖（复核新增，必须一起改）**：`applySelectionResult`（329-359 行）对非 verified 候选
   统一写"显式候选已恢复公开壳信息 / 已取得可用公开正文"，对 Tavily prefetch 候选不准确，破坏可审计性。
6. **阈值已有现成语义，优先复用而非新造**：`TavilyPrefetchedContentGate` 已用
   `TavilySearchProperties.minRawContentChars=500`（yaml:438）判过 prefetch 可用性，
   **`fastLaneUsable=true` 本身就是该 gate 判定的结果**。selector 可直接信任 `fastLaneUsable`，
   不必自造阈值；若仍要二次长度防线，明确标注为 selector 二次防线、参考 500，不与 gate 配置语义混淆。
7. **registry 是一次性 remove，无 contains/peek**（`TavilyPrefetchedContentRegistry:48`，注释"仅单次任务运行期"）。
   **选源阶段绝不能调 remove 校验（会提前消费正文）**。本计划不引入 registry 依赖，
   仅在 Task 0 记录"同进程运行期引用"边界，不做跨 resume/进程的 ref 有效性校验。

---

## 1. 设计决策：入选 + 排序 + 审计，三件一起改（同一个类）

### 1.1 入选（resolveEligibility）
在 verified 分支（165 行）之前、**且在 DISCARDED / 中介页 / 工具页拦截之后**（不绕过安全护栏），
增加 prefetch 入选分支：

```
若 fastLaneUsable == true 且 hasPrefetchedContent == true 且 prefetchedContentRef 非空
→ SelectionEligibility(true, "Tavily prefetch 正文可用，作为正式采集目标入选")
```
判定条件**严格对齐下游 `resolvePrimaryTool` 的 TAVILY_PREFETCHED 触发条件**，保证判得可入选的候选下游一定能取回正文。
信任 `fastLaneUsable`（已是 gate 用 minRawContentChars=500 判过的结果）；如加二次长度防线，标注为 selector 防线。

### 1.2 排序（resolveSelectionTier）
让"可用 prefetch 真文候选"获得**不低于 verified 壳页**的 tier，避免 targetCount=1 时被根域壳页挤掉。
建议：prefetch-usable 候选 tier 设为 0（与 verified 同档）或更前，再由 totalScore 二级排序。
**这一步是修复能否生效的关键，不可省。**

### 1.3 回填审计（applySelectionResult）
为 prefetch 候选增加专属文案分支，保留 `selectionReason/selectionSummary = "Tavily prefetch 正文可用"`，
不被"显式公开壳/公开正文"覆盖，保证 output_data 审计准确。

**为什么不动 `isExplicitCandidate` / `hasUsableCollectedPage`**：
那两个方法服务多处语义，改它们风险外溢。新增独立 prefetch 分支，语义最窄、最安全、可单测隔离。

---

## 2. Task 列表

### Task 0：记录 registry 运行期引用边界（不加依赖、不调 remove）
- [ ] **Step 1**：确认 `TavilyPrefetchedContentRegistry` 仅一次性 `remove`、无 contains/peek（已确认）。
- [ ] **Step 2**：文档记录边界——本修复仅在**同进程运行期**有效（选源→打包→执行时 ref 仍在）；
      resume/跨进程/仅从持久化快照恢复的场景，prefetch ref 可能已失效，属已知边界，本计划不处理。
- [ ] **Step 3**：**明确不在选源阶段调用 registry.remove/任何消费式方法**（会提前消费正文）。

### Task 1：入选判定（resolveEligibility）
- [ ] **Step 1**：在 DISCARDED/中介页/工具页拦截**之后**、verified 分支**之前**，增加 prefetch 入选分支
      （条件 = `fastLaneUsable && hasPrefetchedContent && prefetchedContentRef 非空`，对齐下游 resolvePrimaryTool）。
- [ ] **Step 2**：信任 `fastLaneUsable`（gate 已用 minRawContentChars=500 判过）；如加二次长度防线，
      标注为 selector 防线、参考 500，不与 gate 配置混淆。

### Task 2：排序优先级（resolveSelectionTier）★关键，不可省
- [ ] **Step 1**：让"可用 prefetch 真文候选"tier 不低于 verified（建议同为 0），由 totalScore 二级排序。
- [ ] **Step 2**：确保 targetCount=1、根域 verified 壳页与 prefetch 真文并存时，prefetch 真文不被挤出名额。

### Task 3：回填审计文案（applySelectionResult）
- [ ] **Step 1**：为 prefetch 候选增专属分支，selectionReason/selectionSummary 保留"Tavily prefetch 正文可用"，
      不被"显式公开壳/公开正文"覆盖。

### Task 4：单测（强约束）
- [ ] **Step 1**：`CollectionTargetSelectorTest` 增用例：
      - prefetch 候选(fastLaneUsable=true, hasPrefetchedContent=true) → selectable=true。
      - **targetCount=1 时，verified 根域壳页 + BOOTSTRAPPED prefetch 真文并存 → 最终选中 prefetch 真文**（排序约束）。
      - 选中后 selectionReason = "Tavily prefetch 正文可用"（审计约束）。
      - 无 prefetch 的未验证候选 → 仍不放行（不破坏原护栏）。
- [ ] Run: `cd backend && mvn -Dtest=CollectionTargetSelectorTest test` → PASS。

### Task 5：端到端验证
- [ ] **Step 1**：重跑 task69 同类任务。
- [ ] **Step 2**：查 `evidence_source`：`open.douyin.com/platform/.../docs/develop/...`（2049 字）**进入落库**，
      `distinct_url > 2`、出现 > 2000 字正文。
- [ ] **Step 3**：查 output_data：该候选 `selectionStage` 由 BOOTSTRAPPED 变为 SELECTED，
      且 selectionReason = "Tavily prefetch 正文可用"。
- [ ] **Step 4**：确认 `quality_score` 较 task69 基线(21) 提升；ACTIONABILITY BLOCKER 是否因有真文而缓解。

---

## 3. 不做什么
- 不改 family 主从 / direct-path（10 的长期方向，非本根因）。
- 不改 shouldSkipSupplementForDirectDiscovery / 短路逻辑（已作废的 08a，task69 未命中）。
- 不动 `isExplicitCandidate` / `hasUsableCollectedPage` 既有语义（新增独立分支，风险最小）。
- 不改下游 TavilyPrefetchedExecutor / 消费链（已可用）。
- 不引入 registry 依赖、不在选源阶段调 registry.remove（会提前消费正文）。
- 不处理 resume/跨进程 prefetch ref 失效（已知边界，Task 0 记录）。
- 不新增 gate/audit/recovery，不动四层防无限搜索上限。

## 4. 完成判定
- task69 那条 2049 字 prefetch 真文进入 evidence_source 落库，selectionStage=SELECTED、reason 为"Tavily prefetch 正文可用"。
- 三件事都到位：入选放行 + 排序不被壳页挤掉（targetCount=1 强约束单测）+ 审计文案准确。
- 官网内容丰富竞品回归不受影响（新增分支条件严格对齐下游触发条件，不误放行）。
- 据此结果再判断 10（主从反转）这个长期方向是否仍需推进、何时推进。
