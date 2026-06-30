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

## 0. 前置事实（已读代码 + 数据库确认）

1. **断点**：`executeSupplementByFallbackOrder`（883 行）循环 `[PLANNED, BROWSER, HTTP]`，
   899 行 `if (existing+supplemented >= targetPoolSize) break`。BROWSER（903 行）先跑发"官方网站"凑满 → break →
   HTTP（925 行，唯一带 `fieldEvidenceQueries` 的 `buildSearchSourceRequest`）不执行。
2. **targetPoolSize 口径只看验证数**：`resolveSupplementTargetPoolSize`（约 1000 行）=
   `currentCandidateCount + max(1, minVerifiedCount - verifiedCount)`。**只数验证候选，不看字段覆盖。**
   官方壳页易凑够 → 提前 break。
3. **字段覆盖信息在 break 那层可得**：`config.getDimensionEvidencePlan()` 在 executeSupplement 可访问，
   `fieldEvidenceFinalStatus=EVIDENCE_PATH_COVERAGE_NOT_MET` 表明未达标——可作为"不许 break"的判据。
4. **field query 逐条执行入口已就绪**：`TavilyFastLaneProvider.searchScope:127` —— 只要
   `request.getFieldEvidenceQueries()` 非空就走 `searchFieldEvidenceQueries` 逐条发，无需改 provider。
5. **不是 09/10 的问题**：池里零第三方候选，根因在"发 query 的 stage 没跑"，不在选源、不在主从。

---

## 1. 设计决策：两条互补改动，A 必做、B 视情况

### A（必做）：字段覆盖未达标时，break 不得提前触发
在 899 行 break 条件增加前提——**仅当字段覆盖已达标（或本任务无字段计划）时**才允许"候选池满即停"；
若 `DimensionEvidencePlan` 存在且字段覆盖未达标（有 pending field query / `COVERAGE_NOT_MET`），
**必须继续执行到 HTTP stage**，让 field query 发出。

伪逻辑：
```
boolean fieldCoveragePending = hasPendingFieldEvidenceQueries(config)
        || dimensionEvidencePlan 标记 COVERAGE_NOT_MET;
if (!fieldCoveragePending && existing+supplemented >= targetPoolSize) break;
// fieldCoveragePending 时不因候选数 break，确保 HTTP stage 的 field query 得到执行
```
保留四层防无限搜索上限（maxRounds=2 等）兜底，避免"不 break"导致多跑——它仍受轮次/次数硬上限约束。

### B（视情况）：把 field query 执行前置到 BROWSER 之前
若 A 改完后 field query 虽执行、但 BROWSER 已先污染候选池/抢占 targetCount 名额导致第三方仍进不来，
则把 field query 执行（HTTP/PLANNED 内的 field 分支）提到 BROWSER 朴素补源之前。
**B 在 A 的端到端验证后按数据决定，不提前做。**

---

## 2. Task 列表

### Task 0：确认 break 层能拿到字段覆盖状态
- [ ] **Step 1**：确认 `executeSupplementByFallbackOrder` 内可访问 `config.getDimensionEvidencePlan()`
      及 `hasPendingFieldEvidenceQueries(config)`（CollectorAgent 已有同名判断，确认 coordinator 侧可复用或等价实现）。
- [ ] **Step 2**：确认 `fieldEvidenceFinalStatus / COVERAGE_NOT_MET` 在该阶段是否已知，
      或用"还有 pending field query"作为等价判据。

### Task 1：改 break 条件（A）
- [ ] **Step 1**：`executeSupplementByFallbackOrder` 899 行 break 增加"字段覆盖未达标则不 break"前提。
- [ ] **Step 2**：确保无字段计划的旧任务行为不变（fieldCoveragePending=false 时维持原 break）。
- [ ] **Step 3**：确认四层上限仍兜底（不 break 不等于无限循环）。

### Task 2：单测
- [ ] **Step 1**：`SearchExecutionCoordinatorTest`（类名以实际为准）增用例：
      - 有 DimensionEvidencePlan 且字段未达标 + 候选数已达 targetPoolSize → **不 break，HTTP stage 执行**。
      - 无字段计划 + 候选数达标 → 维持原 break。
      - 字段已达标 + 候选数达标 → break（不过度搜索）。
- [ ] Run: `cd backend && mvn -Dtest=SearchExecutionCoordinatorTest test` → PASS。

### Task 3：端到端验证（核心：丰富度）
- [ ] **Step 1**：重跑 task69 同类任务。
- [ ] **Step 2**：查 output_data 的 `tavilyQuery`：应出现 07 的多条 query（含第三方视角），不再只有"官方网站"。
- [ ] **Step 3**：查候选池域名：**出现 open.douyin.com/open.bilibili.com 以外的第三方域名**
      （目标对齐 PoC：volcengine/huasheng/diansan 等），`distinct_url` 显著 > 2。
- [ ] **Step 4**：查 `evidence_source`：落库出现 > 2000 字第三方长文。
- [ ] **Step 5**：若第三方 query 已执行但候选仍进不来 → 启动 B（field query 前置），并复查是否撞 09 选源问题。

### Task 4：与 09 的衔接判断
- [ ] **Step 1**：若 08 让第三方候选进了池，但落库仍丢 → 09（选源认 prefetch/丰富候选）补上。
- [ ] **Step 2**：据丰富度结果，重新评估 10（主从反转）是否还必要。

---

## 3. 不做什么
- 不改 07 query 生成（已正确生成 26 条含第三方）。
- 不改 TavilyFastLaneProvider 逐条执行逻辑（已就绪，只是没被调到）。
- 不动四层防无限搜索上限；A 改动靠"字段达标"收敛，不靠 break 数量。
- 不在 A 验证前做 B（field query 前置）。
- 不与 09/10 混做：08 解决"第三方进得来"，09 解决"进来的不丢"，10 是长期方向。

## 4. 完成判定
- task69 重跑：tavilyQuery 出现多条 07 生成 query；候选池出现第三方域名；evidence_source 落库第三方长文。
- 无字段计划的旧任务行为不变；过度搜索被四层上限兜住。
- 丰富度接近 PoC（多域名、多条 >2000 字正文）即达成——这是"保证丰富采集"的验收线。
