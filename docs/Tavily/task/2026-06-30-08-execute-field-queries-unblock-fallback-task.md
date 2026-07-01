# Task66-08（执行序）让字段 query 真正执行：解除 fallback 提前 break 实施计划

> 2026-06-30。基于根因诊断
> `docs/Tavily/problem/2026-06-30-task69-field-query-skipped-by-fallback-order-diagnosis.md`。
> **执行顺序里的第 1 步（编号 08）**，"保证丰富采集"的源头修复，先于 09 选源、10 主从。
> 执行方式：`master` 直接改、用户自行提交、过程不创建 commit、逐 Task 勾 checkbox。

**目标：** 让 07 生成的字段 query（尤其 4 条不限域名第三方 query）在 task69 这类任务里**真正发给 Tavily 执行**，
从而把 volcengine/huasheng 等第三方长文召回进候选池——而不是被 BROWSER stage 凑满候选池后 break 跳过。

**范围铁律：** 只动搜索补源执行链的"break 条件"与（可选）"stage 顺序"。
不碰 query 生成（07 已对）、不碰下游消费（已可用）、不碰 09 选源/10 主从（各自独立）、不动四层防无限搜索上限。

---

## 0. 前置事实（已读代码 + 数据库确认；2026-06-30 晚二次复核修订）

1. **补源整体已被放行（修正：不是"整个补源没放行"）**：`shouldSupplement`（1383 行）中
   `if (hasPendingFieldEvidenceQueries(config)) return true`（1398 行）——只要有待执行字段 query，
   补源会进入 provider。**所以 08 的断点不在"补源放没放行"，而在补源进入后、fallback 循环内部的 break。**
2. **真断点（内层 break）**：`executeSupplementByFallbackOrder`（898 行）循环 `[PLANNED, BROWSER, HTTP]`，
   899 行 `if (existing+supplemented >= targetPoolSize) break`。BROWSER（903 行）先跑发"官方网站"凑满 → break →
   HTTP（925 行，唯一带 `fieldEvidenceQueries` 的 `buildSearchSourceRequest`）**在 stage 循环里被提前 break 跳过**。
   即：补源 stage 整体放行了，但内层 BROWSER 先把池子凑满，轮不到 HTTP stage 发 field query。
3. **judge 用 pending query，不用 COVERAGE_NOT_MET（修正）**：break 前提判据应为
   `hasPendingFieldEvidenceQueries(config)`（基于 `DimensionEvidencePlan.hasPendingFieldEvidenceQueries()`，
   搜索循环内稳定可得），**不要用 `fieldEvidenceFinalStatus=COVERAGE_NOT_MET`**——后者是 Collector 输出摘要、
   不是搜索循环的稳定输入。必要时补 `hasUnmetRequiredFieldPath()` 作为补充判据。
4. **入口级短路（08 原未覆盖，本次补入）**：`shouldSupplement`（1389 行）中
   `shouldSkipSupplementForDirectDiscovery(...)` 排在 pending field query 检查（1398 行）**之前**。
   某些 direct discovery 场景（有 competitorUrls + verified 达标）会在还没看 pending query 时就 `return false`，
   导致补源整体不进入——这是比内层 break 更早的入口级短路，08 必须一并处理。
5. **field query 逐条执行入口已就绪**：`TavilyFastLaneProvider.searchScope:127` —— 只要
   `request.getFieldEvidenceQueries()` 非空就走 `searchFieldEvidenceQueries` 逐条发，无需改 provider。
6. **不是 09/10 的问题**：池里零第三方候选，根因在"发 query 的 stage 没跑到"，不在选源、不在主从。
7. **与 09 的优先级关系（诚实标注）**：task69 已有 TAVILY_PHASE1_BOOTSTRAP 的 2049 字正文进池、被
   `CollectionTargetSelector` 丢弃——**若目标仅是"修 task69 落库"，09 比 08 更直接**。
   08 解决的是更上游的"第三方候选完全没进池 / field query 根本没执行"的**丰富度**问题。
   两者目标不同：08 = 让更多第三方进得来；09 = 让已进来的不被丢。建议仍先 08（丰富度源头）再 09。

---

## 1. 设计决策：两处短路都要解，A 必做、B 视情况

### A（必做）：break + 入口短路，都增加"还有 pending field query 则不短路"前提
- **A1 内层 break（899 行）**：增加 `!hasPendingFieldEvidenceQueries(config)` 前提——
  仅当无 pending field query（或无字段计划）时才允许"候选池满即 break"；有 pending 则继续到 HTTP stage。
- **A2 入口短路（1389 行）**：`shouldSkipSupplementForDirectDiscovery` 命中时，
  若 `hasPendingFieldEvidenceQueries(config)` 为真，**不得跳过补源**（把 pending 检查提到 direct-discovery 短路之前，
  或在该短路内加 pending 例外）。否则补源在入口就被 direct discovery 挡掉，A1 的内层修复也没机会生效。
- 判据统一用 `hasPendingFieldEvidenceQueries(config)`（必要时加 `hasUnmetRequiredFieldPath()`），
  **不用 COVERAGE_NOT_MET**。
- 四层防无限搜索上限（maxRounds=2 等）仍兜底：pending query 本身有限且随执行减少，不会无限。

### B（必做，task70 实证后从"视情况"升级）：field query 不应排在 Playwright 壳页验证/BROWSER 之后被超时饿死
**task70 实证（2026-06-30 重跑）：A 改对了但单独无效。** task70 死在更早的超时熔断：
- `fallbackDecision=SKIP_SUPPLEMENT_AND_FALLBACK_PLANNED`、`supplementMethod=TIMEOUT_FALLBACK`
- `searchTimeoutMillis=18300`(18.3秒)，但补源前的 PLANNED+VERIFY_TOP_CANDIDATES 阶段用 Playwright 渲染
  `open.douyin.com/about·/app·/download·/help` 等官方壳路径（每个 `page-timeout-millis=15000`），18.3 秒被耗光。
- `isTimedOut` 在 `shouldSupplement` 后、`executeSupplementByFallbackOrder` 前为真 → 整个补源被 `markStepSkipped`，
  field query **0 执行**（`TAVILY_FIELD_EVIDENCE_QUERY` 出现 0 次），候选池零第三方，落库仍壳页。

因此 B 必做：让 field query 在"被 Playwright 壳页验证耗尽预算之前"执行。两条子改动：
- **B1 顺序前置**：把 field query 执行提到 BROWSER/Playwright 壳页验证之前（field query 是 07 主力取证手段，
  不该排在官方壳页直采兜底之后）。
- **B2 超时预算**：`searchTimeoutMillis=18300` 太短，容纳不下多条 field query。调到能跑完 field query 的值
  （或：超时判定对 field query 阶段豁免/单独计时，不让壳页渲染吃掉 field query 的预算）。

### C（必做，新断点）：超时熔断不得在 field query 未执行时跳过补源
`SearchExecutionCoordinator.java:355-365`：`shouldSupplement` 返回 true 后，
`if (isTimedOut(...)) { circuitBroken; markStepSkipped; }` 直接跳过 `executeSupplementByFallbackOrder`。
**这是 A 的 break/入口短路之外、更早的第三个短路点。** 改动：超时熔断时，若仍有 pending field query，
不得整体跳过补源——至少保证 field query 阶段执行（可配合 B2 给 field query 独立预算）。
否则 A 改的 break 永远走不到。

---

## 2. Task 列表

> **进度（2026-06-30）：A 档（Task 0-3）已完成，单测 8 个全绿。但 task70 重跑证明 A 单独无效——
> 死在更早的超时熔断（见 Task 4 结果）。下一步是 Task 5（C：超时熔断）+ Task 6（B：field query 前置）。**

### Task 0：确认判据可得（用 pending query，不用 COVERAGE_NOT_MET）✅ 完成
- [x] **Step 1**：`hasPendingFieldEvidenceQueries(config)`（1409 行）可在两处调用。
- [x] **Step 2**：用 pending query 判据，未用 COVERAGE_NOT_MET。

### Task 1：改内层 break（A1）✅ 完成
- [x] 899-903 行 break 增加 `!pendingFieldEvidenceQueries` 前提，已落地。

### Task 2：改入口短路（A2）✅ 完成
- [x] `shouldSupplement` 1399-1404 行：`hasPendingFieldEvidenceQueries → return true` 已提到
      `shouldSkipSupplementForDirectDiscovery` 之前。

### Task 3：单测（HYBRID 场景）✅ 完成
- [x] `SearchExecutionCoordinatorFieldEvidenceTest` 新增 2 个 HYBRID 用例，`mvn test` 8 个全绿。

### Task 4：端到端验证（task70 已重跑）❌ 暴露新断点
- [x] **Step 1**：task70 已重跑（2026-06-30 20:11，改后代码 19:57 编译，确认用新代码）。
- [x] **结果**：候选池仍零第三方、落库 2 壳页、`TAVILY_FIELD_EVIDENCE_QUERY` 0 执行——与 task69 相同。
- [x] **根因**：`fallbackDecision=SKIP_SUPPLEMENT_AND_FALLBACK_PLANNED`、`supplementMethod=TIMEOUT_FALLBACK`。
      A 改对了（`shouldSupplement` 因 pending 返回 true），但补源在 `executeSupplementByFallbackOrder` 调用前
      被 `isTimedOut`（searchTimeoutMillis=18300）熔断跳过。Playwright 渲染官方壳页耗光了 18.3 秒预算。
- ⇒ **结论：A 必要但不充分，必须加 C（超时熔断豁免）+ B（field query 前置）。**

### Task 5（C，必做）：超时熔断时不跳过 pending field query ✅ 完成
- [x] **Step 1**：`SearchExecutionCoordinator.java:356-357` 已改为 `if (isTimedOut(...) && !pendingFieldEvidenceQueries)`——
      超时且无 pending field query 才熔断；有 pending 则继续补源。
- [x] **Step 2**：当前实现 = 超时判定对 field query 豁免（pending 时不熔断）。独立预算（B2）未做，见 Task 6。
- [x] **Step 3**：四层上限仍兜底。
- [x] **效果验证**：task71 证明 C 生效——field query 终于发出，第三方域名（csdn/51cto）首次进池。

### Task 6（B，必做）：field query 前置 + 超时预算 ❌ 未做（task71 暴露其必要性）
- [ ] **B1**：把 field query 执行提到 BROWSER/Playwright 壳页验证之前（顺序前置）。
      **现状：fallback 默认仍是 `[PLANNED, BROWSER, HTTP]`，未前置。**
- [ ] **B2**：`searchTimeoutMillis` 现状仍是 18300，未调大、未给 field query 独立计时。
- [ ] 单测覆盖：壳页验证慢的情况下 field query 仍在预算内执行。
- 说明：task71 的 field query 是靠 C（超时豁免）发出的，不是 B。但 B 未做导致 field query 仍排在
  Playwright 壳页之后、总耗时被拉长（task71 16-24 分钟有一部分是此因，主因是 Task 9 的 sitemap 放大）。

### Task 7：端到端复验（核心：丰富度）🔵 task71 部分验证
- [x] **Step 1**：task71 已重跑（A1+A2+C 已落地，B/Task9 未做的状态）。
- [x] **Step 2**：✅ field query 已发出、第三方进池（达成"进得来"目标）。
- [x] **Step 3**：✅ 候选池出现 csdn/51cto 等第三方域名。
- [ ] **Step 4**：❌ 落库仍为壳/垃圾页（apps.microsoft.com、csdn/api 17~820字），且采集 16-24 分钟——
      根因 = Task 9 缺失（sitemap 放大无关域名）。需 Task 9 + B 修复后重新复验。

### Task 8：与 09/10 的衔接判断
- [ ] **Step 1**：08 让第三方进池但落库仍丢 → 09 补上。
- [ ] **Step 2**：据丰富度结果，重新评估 10（主从反转）是否还必要——
      注：task70 证明官方直采既污染候选又吃光超时预算，10 的必要性已上升。

### Task 9（必做，task71 实证后新增）：sitemap 来源补归属校验，挡住无关域名放大
**task71 实证（2026-06-30，做了 C/B 之后重跑）：field query 终于发出、第三方域名（csdn/51cto）进池了——
方向对了。但暴露灾难性放大链：**
- 采集耗时 **16~24 分钟**（task70 是 3-5 分钟）；落库混入 `apps.microsoft.com`、csdn.net/api(壳)、51cto.com/api(224字)。
- 候选池：`SITEMAP_DISCOVERY` ×324、`apps.microsoft.com` ×329——一个**与抖音无关的域名**被当 official 展开根，
  sitemap 炸出 324 个候选，逐个 Playwright 渲染 → 时间爆炸 + 垃圾落库。

**根因（三漏洞叠加，均在 `CandidateOwnershipPolicy`）：**
1. 归属校验 `hasCompetitorOwnershipSignal`（查域名/正文含竞品别名）本身是对的，apps.microsoft.com 本应被拦。
2. 但 `shouldRequireOwnershipValidation = OFFICIAL && isSearchDiscovered`，而 `isSearchDiscovered` 名单
   （BROWSER/SEARCH/SEARCH_ROOT_TEMPLATE…）**不含 `SITEMAP_DISCOVERY`** → sitemap 候选免归属校验。
3. `isTrustedSearchRoot` 对非 verified 候选在 `!isSearchDiscovered` 时直接 `return true` → 无关域名当展开根。
4. sitemap 上限存在但偏大：`maxUrlsPerSitemap=80 × maxSitemapsPerDomain=3 = 单域 240`，多域累加成 324。

**闸门（两处最小改动，只给现有校验补 SITEMAP_DISCOVERY 盲区，非新功能）：**
- [ ] **A（兜底）**：把 `SITEMAP_DISCOVERY` 纳入归属校验——`isSearchDiscovered` 加入该方法，
      或 sitemap 候选入池时强制走 `hasCompetitorOwnershipSignal`，无竞品别名（如 apps.microsoft.com）直接丢。
- [ ] **B（治本）**：`isTrustedSearchExpansionRoot/isTrustedSearchRoot` 对非 verified 候选一律要求
      `hasCompetitorOwnershipSignal`，不再因 `!isSearchDiscovered` 放行——无关域名当不了展开根，从源头不触发 324 展开。
- [ ] **C（防御，可选）**：评估 `maxSitemapsPerDomain/maxUrlsPerSitemap` 是否需收紧，避免相关域名也展开过多。

**D（必做，保证闸门通用、非定制）：别名从 competitorUrls 主域推导，替代中文硬编码**
当前 `buildAliases` 硬编码 `哔哩哔哩→bilibili/b站`、`抖音→douyin`（注释自承"先覆盖当前 live 样本"）。
若不改，A/B 闸门换竞品会**误杀真域名**：如竞品"飞书"，其官方域 `feishu.com` 不含中文"飞书" → 被归属校验当无关域名挡掉。
- [ ] **D1**：`hasCompetitorOwnershipSignal` / `isTrustedSearchRoot` 签名增加 competitorUrls（或其主域名）入参。
      数据可达：`isTrustedSearchExpansionRoot(config, candidate)`（1630 行）已持有 config，
      `config.getCompetitorUrls()`（77 行字段）可直接取；只需把主域名一路传到 `CandidateOwnershipPolicy`。
- [ ] **D2**：`buildAliases` 把 competitorUrls 各 URL 的主域名（如 douyin、feishu）自动并入别名集合，
      不再依赖中文硬编码。用户填了官网 URL = 最可靠的"自己人"归属信号，换任何竞品都通用。
- [ ] **D3**：保留 competitorName 别名作为补充；`aliases.isEmpty()` 的 fallback 不要无条件 return true
      （否则等于不校验），改为"无任何归属信号时按需收紧"。
- [ ] **单测（含通用性约束）**：
      - apps.microsoft.com（不含任何别名/主域）的 SITEMAP_DISCOVERY 候选 → 被拦/不当展开根。
      - open.douyin.com（competitorUrls 主域）→ 仍可正常展开。
      - **换竞品验证非定制**：competitorUrls=[feishu.com]、competitorName=飞书 →
        feishu.com 候选不被误杀（证明不靠中文硬编码）。
- [ ] **端到端复验**：重跑 task71 同类 → 采集时间回落分钟级、候选池无 apps.microsoft.com、
      落库第三方域名为真正相关页（含竞品别名或主域）。

---

## 3. 不做什么
- 不改 07 query 生成（已正确生成 26 条含第三方）。
- 不改 TavilyFastLaneProvider 逐条执行逻辑（已就绪，只是没被调到）。
- 不动四层防无限搜索上限；A/C 改动靠"字段达标/pending 减少"收敛，不靠 break 数量。
- B 已从"视情况"升级为必做（task70 实证 A 单独无效）。
- 不与 09/10 混做：08 解决"第三方进得来"，09 解决"进来的不丢"，10 是长期方向。

## 4. 完成判定
- field query 真执行（`TAVILY_FIELD_EVIDENCE_QUERY` > 0）、候选池出现第三方域名——task71 已达成此项。
- **采集时间回落到分钟级**（task71 的 16-24 分钟是 sitemap 放大无关域名所致，Task 9 修复后应消除）。
- 候选池无 `apps.microsoft.com` 这类与竞品无关的域名；落库第三方页为真正相关内容（含竞品别名）。
- 无字段计划的旧任务行为不变；过度搜索被四层上限 + 归属校验兜住。
- 丰富度接近 PoC（多域名、多条 >2000 字**相关**长文）即达成——这是"保证丰富采集"的验收线。

> **执行进度小结（2026-06-30）：A（Task0-3）✅；C/B（Task5-6）已做、task71 验证 field query 已发出第三方进池 ✅；
> 但暴露 sitemap 放大无关域名 → Task 9（归属校验闸门）必做。下一步 = Task 9 的 A+B 两处改动 + 复验。**

---

## 5. 封板归档（2026-07-01，task75 端到端复验后确认）

**结论：08 达成"保证丰富采集"的源头修复目标，正式封板。** 代码实测状态（以代码为准，上方部分 checkbox 未同步勾选）：

| 项 | 代码位置 | 实际状态 | task75 佐证 |
|---|---|---|---|
| A1 内层 break 加 pending 前提 | `SearchExecutionCoordinator` 补源循环 | ✅ 已落地 | field query 39 条能发 |
| A2 入口短路加 pending 例外 | `shouldSupplement` | ✅ 已落地 | 补源进入 provider |
| C 超时熔断豁免 pending | `SearchExecutionCoordinator.java:361` `isTimedOut(...) && !pendingFieldEvidenceQueries` | ✅ 已落地 | 未被超时熔断跳过 |
| B2 field query 独立超时预算 | `SearchPolicyResolver.ensureMinimumTimeoutForFieldEvidenceQueries`（12s + 每条 6s） | ✅ 已落地 | 39 条给到约 246s 预算，采集跑完 |
| Task9 A SITEMAP 纳入归属校验 | `CandidateOwnershipPolicy.isSearchDiscovered:227` 含 `SITEMAP_DISCOVERY` | ✅ 已落地 | `SITEMAP_DISCOVERY`=0 |
| Task9 B 非 verified 一律要归属 | `isTrustedSearchRoot` + `hasCompetitorDomainOwnershipSignal` | ✅ 已落地 | `apps.microsoft`=0 |
| Task9 D 别名从 competitorUrls 主域推导 | `buildAliases(name, competitorUrls)` | ✅ 已落地 | 换竞品单测通过 |
| 壳页恢复补归属闸门（08 原未列，7-01 追加） | `PublicEvidenceRecoveryService` + `hasCompetitorDomainOwnershipSignalForCandidate` | ✅ 已落地 | 官方深链进、无关域名不进 |
| Tavily 超时（08 外的运行时阻断） | `application.yml timeout-seconds 20→45` + `TavilySearchProperties` | ✅ 已落地 | Tavily 0 失败（此前 7/7 全挂） |
| **B1 fallback 顺序前置** | `SearchPolicyResolver:52` 仍 `[PLANNED, BROWSER, HTTP]` | ❌ **未做，但降级为非阻断** | B2 独立预算已让 field query 跑完，B1 不再卡执行 |

**task75 复验数据（抖音开放平台 vs B站开放平台，2026-07-01 12:28）：**
- Tavily `TAVILY_BOOTSTRAP_ENRICH` SUCCESS（3 秒），`tavily request failed`=0；
- `fieldEvidenceQueryCount=39`（生成对）、field query 真执行；
- `apps.microsoft`=0、`SITEMAP_DISCOVERY`=0（闸门生效）；
- 采集耗时 542s（≈9 分钟），较 task73/74 的 30.5 分钟降到约 1/3；
- 官方采集变为真实文档深链（`open.douyin.com/platform/resource/docs/...`），不再是壳页。

**08 未解决、正式移交 09 的问题（不属 08 范围）：**
- `fieldEvidencePaths` 全是官方路径，`PUBLIC_REVIEW_OR_NEWS`（第三方）`required=false` 未激活 → field query 全绕官方域名、第三方源 0 命中；
- 7 字段 `sufficientFields=0`、全 `EVIDENCE_PATH_COVERAGE_NOT_MET`，任务停在 `analyze_competitors` 人工介入；
- 这是"进来的丰富候选 / 第三方路径未被采纳"问题，属 09 范围。B1 顺序前置也并入 09 一起评估（见 09 计划补充）。
