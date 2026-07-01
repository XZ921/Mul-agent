# Task66-09 选源认 prefetch 正文：让 BOOTSTRAPPED 候选可入选 实施计划

> 2026-06-30。本计划替代已作废的 08a，基于真根因诊断
> `docs/Tavily/problem/2026-06-30-task69-prefetch-evidence-dropped-at-selection-diagnosis.md`。
> **执行顺序里的第 2 步（编号 09）**，前置是 08（先让第三方候选进池），本步保证"进来的丰富候选不被选源丢弃"。
> 执行方式：`master` 直接改、用户自行提交、过程不创建 commit、逐 Task 勾 checkbox。

**目标：** 让带 Tavily prefetch 正文的 `BOOTSTRAPPED` 候选能通过 `CollectionTargetSelector` 的入选判定，
从而被打包给已存在的 `TavilyPrefetchedExecutor` 取回正文落库——而不是被当"未验证候选"丢弃，只留根域壳页。

**范围铁律（2026-07-01 修订：两阶段、两处类改动）：**
task75 实证后 09 从"只改选源"升级为**两处代码改动**（详见 §5）：
- **阶段一（断点 B，先做）**：改 `FieldEvidenceQueryPlanner`——激活非 required 的第三方 evidence 路径 query 生成
  （现 `plan:52` 无条件跳过 `!isRequired()`，`PUBLIC_REVIEW_OR_NEWS` 一条 query 都不生成）。
- **阶段二（断点 A，再做）**：改 `CollectionTargetSelector` **三件事**——
  入选判定（resolveEligibility）+ 排序优先级（resolveSelectionTier）+ 回填审计文案（applySelectionResult）。
  只改入选会被排序抵消、被审计文案误写，导致"代码改了、prefetch 正文仍没进库"。

> **注意：下方 §0-§4 是 2026-06-30 初稿，当时判断"只改 selector 一个类"。该结论已被 §5（task75 实证）修正为上面的两阶段。
> 若正文与本铁律冲突，以本铁律 + §5 + §7 执行顺序为准。**

不碰 family 主从、不碰短路逻辑、不新增 gate、不改下游消费链（已存在且可用）、不引入 registry 依赖、不动四层防无限搜索上限。

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

### 1.2 排序（resolveSelectionTier）★关键，强约束修订（2026-07-01）
让"可用 prefetch 真文候选"tier **严格优于根域 verified 壳页**，而不是仅仅"同档再拼 totalScore"。
**原因**：若只设同 tier=0 再按 totalScore 二级排序，根域 verified 壳页 totalScore 更高时，
targetCount=1 下 prefetch 真文仍会被壳页挤掉——"不低于"不够，必须"严格优先"。
- **实现建议**：给"prefetch-usable 真文候选"一个**独立更前 tier**（如 tier=0，把原 verified 壳页降到 tier=1 之后，
  或新增 tier=-1 专给 prefetch 真文），确保排序阶段 prefetch 真文一定排在 verified 根域壳页之前。
- **护栏**：仅对"根域壳页"让位，不影响 verified 的**真内容**候选正常竞争（避免误伤官网内容丰富竞品）。
- **强约束单测（不可省，见 Task 4）**：故意构造 `prefetch 真文 totalScore < verified 壳页 totalScore`，
  targetCount=1，仍断言**最终选中 prefetch 真文**。只有这个用例能证明"严格优先"生效、没被 totalScore 抵消。

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
- [ ] **Step 1**：让"可用 prefetch 真文候选"tier **严格优于根域 verified 壳页**（独立更前 tier，
      不是同 tier 再拼 totalScore——否则壳页分高时仍会挤掉 prefetch）。
- [ ] **Step 2**：只对"根域壳页"让位，不影响 verified 真内容候选正常竞争（不误伤官网丰富竞品）。
- [ ] **Step 3**：确保 targetCount=1、根域 verified 壳页与 prefetch 真文并存、**且壳页 totalScore 更高**时，
      prefetch 真文仍不被挤出名额。

### Task 3：回填审计文案（applySelectionResult）
- [ ] **Step 1**：为 prefetch 候选增专属分支，selectionReason/selectionSummary 保留"Tavily prefetch 正文可用"，
      不被"显式公开壳/公开正文"覆盖。

### Task 4：单测（强约束）
- [ ] **Step 1**：`CollectionTargetSelectorTest` 增用例：
      - prefetch 候选(fastLaneUsable=true, hasPrefetchedContent=true) → selectable=true。
      - **targetCount=1 时，verified 根域壳页 + BOOTSTRAPPED prefetch 真文并存、且壳页 totalScore 更高
        → 最终仍选中 prefetch 真文**（排序强约束——只有壳页分更高这个用例能证明"严格优先"没被 totalScore 抵消）。
      - 选中后 selectionReason = "Tavily prefetch 正文可用"（审计约束）。
      - 无 prefetch 的未验证候选 → 仍不放行（不破坏原护栏）。
      - verified 真内容候选（非壳页）→ 不被 prefetch 误伤，正常竞争。
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

## 4. 完成判定（原 09，task69 基线）
- task69 那条 2049 字 prefetch 真文进入 evidence_source 落库，selectionStage=SELECTED、reason 为"Tavily prefetch 正文可用"。
- 三件事都到位：入选放行 + 排序不被壳页挤掉（targetCount=1 强约束单测）+ 审计文案准确。
- 官网内容丰富竞品回归不受影响（新增分支条件严格对齐下游触发条件，不误放行）。
- 据此结果再判断 10（主从反转）这个长期方向是否仍需推进、何时推进。

---

## 5. 补充：task75 实证暴露的更上游问题（2026-07-01，08 封板后）

> 08 已封板（Tavily 超时修好、field query 真执行、壳页/sitemap 闸门生效、耗时腰斩）。
> 用抖音开放平台 vs B站开放平台重跑 task75，**证明原 09（选源认 prefetch）方向对、仍要做**，
> 但同时暴露一个比"选源丢弃"更靠上游的断点：**field query 全绕官方域名，第三方路径根本没被激活。**

### 5.1 task75 关键实证（数据来源：node output_data / searchExecutionTrace）
- `searchMode=HYBRID`、`fieldEvidenceQueryCount=39`（生成没问题）、`fieldEvidenceLoopRounds=2`（补采跑满上限）。
- 但 `fieldEvidencePaths` **全是官方路径**：`OFFICIAL_PUBLIC_PROFILE / DOCS_API_GUIDE / OFFICIAL_PRICING_PAGE /
  DOCS_BILLING_OR_LIMITS / TERMS_OR_SERVICE_AGREEMENT`——**没有一条 `PUBLIC_REVIEW_OR_NEWS`（第三方评测/新闻）**。
- `dimensionEvidencePlan` 里每个字段**定义了** `PUBLIC_REVIEW_OR_NEWS` 路径，但 `required=false`，
  实际执行时**没被纳入 `fieldEvidencePaths`、没生成对应 query**。
- 结果：`BROWSER_SUPPLEMENT_SEARCH` 走 `HTTP_FALLBACK`、`browserExecutedQueries=0`、"未命中第三方源"；
  `evidenceRepairPlan.promotedUrls` 全是 `open-douyin.com/about|help|app` 官方壳。
- 7 字段 `sufficientFields=0`，全 `EVIDENCE_PATH_COVERAGE_NOT_MET`，任务停在 `analyze_competitors` 人工介入。

### 5.2 两个断点，09 要一起解
1. **断点 A（原 09，仍要做）**：Tavily prefetch 正文（BOOTSTRAPPED）在 `CollectionTargetSelector` 被当未验证丢弃。
   task75 里 `TAVILY_BOOTSTRAP_ENRICH` 新增了 2 条候选，但 `selectedCandidateCount=1`——prefetch 候选大概率仍被挤。
   → 原 Task 0-4 不变，照做。
2. **断点 B（task75 新增，更上游）**：即使选源放行了 prefetch，**只要 field query 全是官方域名、第三方路径不激活，
   Tavily 就永远搜不到第三方评测/新闻长文**，selector 也就无 prefetch 第三方正文可放行。
   → 必须让 `PUBLIC_REVIEW_OR_NEWS` 这类第三方路径真正生成 query 并执行。

**顺序：先解 B（让第三方 query 发得出去、搜得到东西），再靠 A（让搜到的东西不被选源丢）。**
两者缺一：只做 A，池里没第三方正文可选；只做 B，第三方正文进来了又被选源丢。

### 5.3 断点 B 的代码根因（已读码定位，2026-07-01）
- **真断点**：`FieldEvidenceQueryPlanner.plan:52`
  ```java
  for (CoverageEvidencePath path : paths) {
      if (path == null || !path.isRequired()) {   // ← 非 required 路径直接 continue，不生成任何 query
          continue;
      }
      ...
  }
  ```
  `PUBLIC_REVIEW_OR_NEWS` 是 `required=false` → 被 51-54 行跳过 → 第三方 query 一条都不生成。
- **关键：第三方 query 生成逻辑本身存在且正确**。同文件 `buildQuery` 注释（77 行）明说
  "第三方视角会返回空域名约束，允许全网材料进入"——`FieldQueryComposition.compose` 已支持生成不限域名的第三方 query，
  **只是被第 52 行的 required 门槛拦在门外，永远走不到**。零件对、接缝断。
- **改动点最小**：让非 required 的第三方路径也参与 query 生成（可控数量），而不是无条件 `continue`。

### 5.4 Task 6（断点 B，必做）：激活第三方 evidence 路径的 query 生成
- [ ] **Step 1**：改 `FieldEvidenceQueryPlanner.plan`——非 required 路径不再无条件 `continue`。
      建议策略（择一，倾向前者）：
      - **6a（推荐）**：required 路径全量生成；非 required 路径（如 `PUBLIC_REVIEW_OR_NEWS`）每字段限量生成
        （如每路径取前 N=1~2 条 variant），保证第三方 query 一定进入待执行集合，又不让总量膨胀失控。
      - **6b（备选）**：给 `CoverageEvidencePath` 增 `bestEffort` 语义，required=false 但 bestEffort=true 的路径参与生成。
- [ ] **Step 2**：确认生成的第三方 query `includeDomains` 为空（不限域名），对齐 `buildQuery` 既有第三方视角逻辑。
- [ ] **Step 3**：数量护栏——第三方 query 计入既有上限，靠"字段达标/pending 减少"收敛，不动四层防无限搜索上限。
- [ ] **单测**：`FieldEvidenceQueryPlannerTest` 增用例——含 `PUBLIC_REVIEW_OR_NEWS`（required=false）的字段
      → 生成的 query 里出现该 pathKey、且 includeDomains 为空（第三方全网）。

### 5.5 Task 7（可选，视 Task 6 数据）：fallback 顺序 / 第三方优先（并入 08 遗留 B1）
- [ ] **Step 1**：Task 6 做完重跑，看第三方 query 是否真被执行（`browserExecutedQueries>0` 或 Tavily 命中第三方域名）。
- [ ] **Step 2**：若第三方 query 仍排在官方壳页验证之后被拖慢/饿死，再评估 08 遗留的 **B1（fallback 顺序前置）**——
      把 field query 执行提到 BROWSER/Playwright 壳页验证之前（`SearchPolicyResolver.resolveFallbackOrder` 默认序）。
      **注意：B2 独立超时预算已让 field query 能跑完，B1 现为"提速/优先级"优化，非阻断，故降级为可选。**
- [ ] **Step 3**：这一步与用户提出的"Tavily-first"方向一致——若数据显示 Playwright 已退居兜底、Tavily 全文够用，
      则 10（主从反转）可能仅剩"默认 fallback 顺序调整"这一小改动，据此再评估 10。

### 5.6 端到端复验（断点 A+B 都做完后）
- [ ] 重跑 task75 同类（抖音开放平台 vs B站开放平台）。
- [ ] `fieldEvidencePaths` 出现 `PUBLIC_REVIEW_OR_NEWS`；Tavily/搜索命中第三方域名（非 open.douyin.com/open.bilibili.com）。
- [ ] 第三方长文（>2000 字）prefetch 候选进入 `selectedTargets`、落库 evidence_source。
- [ ] 字段覆盖 `sufficientFields>0`、不再全部停在 `analyze_competitors` 人工介入。
- [ ] 官网内容丰富竞品回归不受影响（第三方 query 限量、不喧宾夺主）。

## 7. 执行顺序小结（2026-07-01 修订，TDD 顺序）

> **当前状态：09 尚未进入执行——两个断点代码都还没落地。**
> 已读码确认：`FieldEvidenceQueryPlanner.plan:52` 仍跳过非 required 路径（断点 B）；
> `CollectionTargetSelector.resolveSelectionTier:288` 仍 verified=0/其他=1、无 prefetch tier，
> 且无 prefetch 入选分支、无 prefetch 审计文案（断点 A）。**现在直接跑 E2E 只会复现旧失败。**

1. **[已做] 修计划文档**：范围铁律改为"两阶段两处改动"、排序改为"严格优先"强约束。
2. **阶段一 · 断点 B（先写测试再实现）**：
   - 先写 `FieldEvidenceQueryPlannerTest`：`PUBLIC_REVIEW_OR_NEWS`(required=false) 也生成 query、includeDomains 为空。
   - 再实现 Task 6：**限量**放行第三方/开放网络路径（每路径 N=1~2 条），**不是无差别放开所有 non-required**。
3. **阶段二 · 断点 A（先写测试再实现）**：
   - 先写 `CollectionTargetSelectorTest`：prefetch 可入选 + targetCount=1 且壳页分更高时仍打败壳页 +
     审计 reason 正确 + 无 prefetch 未验证候选仍被拒 + verified 真内容不被误伤。
   - 再实现 Task 1-3：入选 + 排序（严格优先）+ 审计文案。
4. **端到端复验（§5.6 / Task 5）**：两断点都通、单测全绿后，再重跑 task75 同类。
5. **Task 7（B1/10 评估）最后**：视 E2E 数据决定是否调 fallback 顺序、10 主从反转是否还需要。
