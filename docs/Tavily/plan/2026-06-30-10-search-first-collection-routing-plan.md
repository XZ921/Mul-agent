# Task66-10（执行序）采集路由主从反转：搜索为主、官方直采为辅 改动计划

> **⚠️ 状态（2026-06-30 晚）：本计划是执行顺序第 3 步（编号 10），定位为"长期方向"，最后评估是否仍需做。**
> 两次根因核查推翻了它作为 task69 直接根因的定性：
> - `2026-06-30-task69-field-query-skipped-by-fallback-order-diagnosis.md`：第三方 query 被 fallback 提前 break 跳过（→ 08 修）。
> - `2026-06-30-task69-prefetch-evidence-dropped-at-selection-diagnosis.md`：prefetch 正文在选源被丢弃（→ 09 修）。
> task69 的搜索、补采、repair、字段再采**全部正常触发**，Tavily 已抓到 2049 字真文档——问题不在"主从关系"。
> **因此：先做 08（让第三方 query 真执行）+ 09（选源不丢丰富候选）；本计划"主从反转"作为长期方向保留，**
> **待 08/09 跑出丰富度数据后再判断是否仍需推进。下方 0-2 节"搜索被短路/被旁路"的定性已部分失效，以本声明为准。**

> 2026-06-30。本计划**只写方案，不改代码**。等用户确认后再执行。
> 编号续 07，但定位不同：07 及之前是"在辅助搜索支路内部做 query/域名/可用性"，
> 主从反转是**反转采集主链路的主从关系**——这是 07 收口想达成、却因为没动 family 主从而没达成的最后一环。

---

## 0. 为什么要开 08（与"07 封板"的关系）

memory 记录 07 是 Tavily 线的收口、之后不再开 08 修采集。本计划是**例外**，理由有三，且有数据支撑：

1. **它不是无边界深挖，是根因修复。** 07 把 query 生成、includeDomains、可用性评分都做对了
   （任务 69 nodeConfig 实证：118 条 query、16 条第三方 query），但这些**从未被主链路执行**。
   根因不在搜索支路内部，在它外面那层"官方直采为主、搜索为辅、主源满足即短路"的 family 路由。
2. **它被验证数据证明会见效。** 2026-06-30 用任务 69 真实生成的第三方 query 直打 Tavily：
   - `抖音开放平台 评测 对比 教程 解读 行业分析`（不限域名）→ 5 条全有正文：
     developer.open-douyin.com(9401字)、volcengine 技术文(30551字)、行业分析(6723字)…
   - 而 family 直采拼 `/docs /help` 顶层壳路径 → 只落 484/433 字壳页。
   - 结论：连官方资料，搜索都比拼路径直采采得好；"只能搜到壳"是系统短路，不是数据缺失。
3. **它修正了一个设计哲学冲突，而非新增功能。** 见第 1 节。

边界声明（防止 08 又变无底洞）：
- **08 只反转主从关系 + 解除壳页短路，不新增任何 gate/audit/recovery/repair 层。**
- 不重建 CoverageContract、EvidenceQualityGate、07 的 planner/scorer。
- 不追求"公网每字段必命中"；公开确无证据时仍输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`。

---

## 1. 问题定性：两套设计哲学在打架

系统里并存两套互相矛盾的采集设计，运行时"设计二"赢，把"设计一"架空：

| | 设计一（字段驱动，06/07 建立，PoC 验证过） | 设计二（家族直采，早期简化，实际在跑） |
|---|---|---|
| 入口 | 字段（summary/coreFeatures…） | 竞品域名 |
| 主角 | 搜索发现的候选池（官网是候选之一） | 官网（PRIMARY_VERTICAL） |
| 官网角色 | **参考/高权重 seed** | **起点+终点+决定者** |
| 取正文 | 按互补 query 搜索 → 可用性筛选 | direct-path 拼 `/docs /help` 直抓 |
| 闭环 | 覆盖闭环判断够不够 | 抓到 1 个候选即 primarySatisfied 短路 |

用户判断（成立）：**官网应该是"给 Tavily 更好参考"，不是"决定"，就像 PoC 那样**——
PoC 里官网只是搜索结果之一，正文可用才被采，从不是流程起点。
设计二违反了"字段驱动多页证据采集 + 覆盖闭环"这个主旨。

---

## 2. 病灶代码与配置位置（已定位）

1. `application.yml` `source-discovery.search.families.official`
   - `role: PRIMARY_VERTICAL`
   - `primary-tools: [WEB_SCRAPER, JINA_READER]`、`auxiliary-tools: [PUBLIC_SEARCH]`
   - `direct-path-templates: [/, /pricing, /docs, /documentation, /help]` ← 壳路径来源
2. `application.yml` `source-discovery.search`
   - `primary-candidate-threshold: 1` ← 抓到 1 个就满足
   - `run-auxiliary-when-primary-satisfied: false` ← 主源满足就不跑搜索
3. `RoutingSearchSourceProvider.java:135-137`
   - `primarySatisfied = primaryCandidateCount >= threshold`，**只数候选个数，不看正文质量**
   - 壳页(484字)也被计入，于是短路辅助搜索
4. `RoutingSearchSourceProvider.java:113-118`
   - `primarySatisfied && !runAuxiliaryWhenPrimarySatisfied && role!=PRIMARY_VERTICAL` → skip
5. `SearchPolicyResolver.java:176-192`
   - provider role 由 family 的 `PRIMARY_VERTICAL` 绑定列表决定
6. **`SearchExecutionCoordinator.java:1419` `shouldSkipSupplementForDirectDiscovery(...)`
   ← task69 场景的更直接、更早发生的短路点（2026-06-30 复核新增）**
   - 语义：有显式 `competitorUrls` + `verifiedCount >= minVerifiedCount` + direct discovery 能为
     该 sourceType 生成候选 → **跳过 public search supplement**。
   - 病灶同样在第 1428 行 `verifiedCount < minVerifiedCount`：**只数 verified 个数，不看正文质量**。
     壳页只要"能打开 + 是该 sourceType"即计入 verifiedCount，达标即短路。
   - task69 正是"显式 open.douyin.com + direct-path 模板"，精确命中这里。
   - **关键结论：只改第 3 项 `RoutingSearchSourceProvider` 挡不住这条 direct discovery 短路；
     A 档必须同时改这里，否则 provider router 改了、direct discovery 仍提前结束补源。**

---

## 3. 改动方案（两档力度，建议先 A 后 B）

### 档 A：解除壳页短路（轻，治标，先验证哲学成立）

**目标：** 官网壳页不再被判为"满足"，逼搜索补源跑起来。不动主从关系本身。

> **2026-06-30 复核修正：A 档有两个短路点，必须同时改，否则只堵一个、另一个仍放行。**
> 二者都犯同一个错——**只数候选/验证个数，不看正文质量**。

- **A1（主病灶，task69 命中点）** `SearchExecutionCoordinator.java:1419`
  `shouldSkipSupplementForDirectDiscovery(...)`：把第 1428 行的
  `verifiedCount < minVerifiedCount` 升级为"**可用 verified 数 < minVerifiedCount**"——
  即先用 07 的 `ContentUsabilityScorer`（或正文长度/壳页评分）过滤掉壳/登录页，
  只统计"真的有可用正文"的 verified 候选。壳页不算可用 → 不跳过 supplement → 搜索补源跑起来。
- **A2** `RoutingSearchSourceProvider.java:135-137`：同理，`primarySatisfied` 判定从
  "候选个数 ≥ threshold"改为"**有可用正文的候选数 ≥ threshold**"，同样复用 `ContentUsabilityScorer`。
  这一层挡的是 provider 路由级短路，与 A1 互补：A1 堵 direct discovery 短路，A2 堵 provider router 短路。
- **A3** `application.yml`：`run-auxiliary-when-primary-satisfied` 在"主源全是壳/薄页"场景下放开
  （实现上等价于 A1/A2：壳页不满足，自然继续跑辅助）。
- 风险：低。只让"该跑的搜索跑起来"，不改官网内容丰富竞品的行为（官网有料时 verified 可用、仍短路、仍快）。
- 预期：task69 官方壳页 → 两个短路点都判定"不可用/不满足" → 触发 Tavily 第三方搜索 → 采到 6000~30000 字长文。
- **统一抽取建议**：A1/A2 的"verified/候选是否可用"判断应抽到一个公共方法（如
  `ContentUsabilityScorer` 的一个 `isUsable(candidate/page)` 入口），避免两处各写一套壳页判定口径漂移。

### 档 B：主从反转（重，治本，落地"官网是参考不是决定"）

**目标：** 把"搜索发现"提为字段采集主入口，官网 direct-path 降为搜索候选池里的高权重 seed。

- **B1** `application.yml` `official` family：
  - `primary-tools` 与 `auxiliary-tools` 对调或重排，使 `PUBLIC_SEARCH` 成为主取证手段，
    `WEB_SCRAPER` 直采降为对"搜索已发现的官网 URL"做正文补抓，而非用 direct-path 拼路径抢跑。
  - `direct-path-templates` 不再作为主采集入口；保留为"已知高价值 seed URL"喂给搜索/排序加权。
- **B2** 采集节点 toolchain 装配：确认 CollectorAgent 按新 primary-tools 顺序执行
  （搜索先行、直采补充），而非当前的"先 direct-path 直采、搜索仅在不足时辅助"。
- **B3** 官网可信度仍保留为**排序权重**（符合证据准入原则"官方是权重非门槛"），
  但不再拥有"短路整个搜索"的特权。
- 风险：中。改的是 family 语义与采集节点执行顺序，影响所有 family（不只 official）。
  需回归 06/07 既有测试，确认官网内容丰富的竞品不被拖慢、不丢官方证据。

### 证据准入原则落地校验（A/B 都必须满足）

- 第三方候选 `sourceUrls` 完整可追溯；落库与报告标注来源层级（官方/第三方）及可信度权重。
- 官方优先非排他：官方仍加权，但不排斥第三方，不短路搜索。

---

## 4. 验收标准

档 A 验收：
- 重跑任务 69（或同类开放平台任务），采集层 `distinct_url > 2`、出现 open.douyin.com / open.bilibili.com
  以外的第三方域名，且至少 2 条候选正文长度 > 2000 字。
- 日志出现 Tavily/PUBLIC_SEARCH 实际命中并入选，不再是 120 次 Playwright + 0 次搜索。
- **专项确认两个短路点都已解除**：`shouldSkipSupplementForDirectDiscovery` 在壳页场景返回 false
  （日志/断点确认 supplement 未被跳过），且 `RoutingSearchSourceProvider` 未因壳页提前 `primarySatisfied`。
  二者缺一，搜索仍可能被另一处短路挡住。

档 B 验收：
- 同一任务，采集主入口日志体现"先搜索发现、后直采补抓"。
- 回归一个**官网内容丰富**的正常竞品，确认官方证据不丢失、采集未明显变慢。
- `quality_score` 较任务 69 基线(21)显著提升；ACTIONABILITY BLOCKER 不再因壳页推测触发。

---

## 5. 执行前风险评估（2026-06-30 复核，代码已确认）

执行 08 前排查了两个风险，结论如下。

### 5.1 会不会无限搜索 —— 不会，四层独立硬上限

- **覆盖闭环再入轮次**：`CollectorAgent.java:1690-1693`，`maxCollectionRounds` 默认 **2**
  （`DimensionEvidencePlanFactory:57` 硬编码），`currentRound < maxRounds` 才再采。
- **每任务搜索次数**：`maxSearchesPerTask` 默认 **10**（`CollectorPlanTemplateFactory:112`）。
- **候选/结果数**：`maxSearchResults=targetCount`、验证候选 `resolveVerificationCandidateLimit`、
  补源 `.limit(needed)`（`SearchExecutionCoordinator:334/409`），每步都有 `.limit()`。
- **决策级**：`maxSearchQueriesPerDecision` 默认 5（`DecisionPolicyRuleSet:50`）。

**结论：08 不新增任何上限，只是让壳页不短路、把本该跑的 query 在既有预算内跑满。**
query 总量有限（字段数 × 路径数，任务 69 实测 118 条即上界），叠加 2 轮 + 10 次/任务 + 各级 limit，必然收敛。
**执行注意**：08 让壳页不算 satisfied，会让 `shouldRunFieldEvidenceRecollection` 更易判定"需再采"，
但 `maxRounds=2` 兜底，顶多多跑 1 轮。重跑验证时确认轮次在第 2 轮后正常停。

### 5.2 两个竞品搜索量会不会偏差过大 —— 预算不争抢，但数据丰富度差异客观存在

- **结构性保证**：每个竞品是**独立采集节点**（task69 的 `collect_sources_01_xx`=抖音、`02_xx`=B站），
  各自独立 `CollectorNodeConfig` + `DimensionEvidencePlan`。预算按节点算、不共享，
  **不存在"一个竞品吃掉另一个的搜索额度"**。
- **真实隐患（来源不同）**：偏差不来自额度争抢，来自**短路触发不对称**——
  若 A 竞品官网是壳页（触发完整搜索、采得多）、B 竞品官网恰好内容丰富（满足短路、搜索没跑、采得少），
  会出现"内容多的反而采得少"。08 档 A 把判定从"个数"改为"可用正文"后，两边都以可用性为准，
  **人为偏差会缩小**，但由公开资料丰富度不同造成的客观差异消不掉。
- **当前系统没有竞品间证据量均衡机制**：每个竞品只对自己的覆盖闭环负责，不和另一个比。
  要防的是偏差大到一边几乎无证据导致对比报告一边倒——这属于数据特性，不是本计划要解决的 bug。
- **执行注意（观测，非拦截）**：重跑后记录两竞品各自 `distinct_evidence` 与执行轮次，
  若某竞品远低于另一个，判断是"数据确实少"还是"仍被短路挡住"。看数据库即可，不需写进代码。

---

## 6. 与整体路线的关系

- 本计划属于 `docs/specs/2026-06-30-two-axis-closure-direction.md` **方面二（业务阻断点）**里
  **最上游、最根本**的一个——它是壳页/重复证据/ACTIONABILITY BLOCKER 一连串问题的共同祖宗。
- 完成后回到方面二纪律：只修 BLOCKER。若档 A 已让正常竞品端到端跑通，档 B 优先级可下调。
- 仍不开 4.x；本计划不涉及 Orchestrator/LLM 大脑（那是方面一）。

