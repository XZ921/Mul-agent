# Task69 丰富采集根因诊断：field query 被 fallback 顺序提前 break 跳过

> 2026-06-30。本文是"为什么 task69 采集不丰富、只有官方壳页"的**最上游根因诊断**，
> 基于数据库候选池 + nodeConfig + 搜索执行链代码 + fallback 配置四方实证。
> 它在 `2026-06-30-task69-prefetch-evidence-dropped-at-selection-diagnosis.md`（选源丢弃，末端）**之上**，
> 是更靠前、更直接决定"采集丰富度"的根因。

---

## 0. 结论先行

> 07 生成的 26 条字段 query（含 4 条不限域名 `OPEN_WEB` 第三方 query，本可召回 volcengine/huasheng 等长文）
> 挂在搜索 fallback 的 **HTTP stage**。但 HYBRID 默认 fallback 顺序是 `PLANNED → BROWSER → HTTP`，
> **BROWSER stage 先执行、用朴素 query「抖音 官方网站」凑满候选池后触发 `targetPoolSize` break，
> HTTP stage 从未执行**。于是 26 条 query 一条没发给 Tavily，候选池全是官方域、零第三方，采集不可能丰富。

一句话：**你要的丰富素材，输送它们的那条管道（HTTP stage 的 field query）在执行前就被前一个 stage 短路了。**

---

## 1. 实证（四方一致）

**1.1 候选池域名分布（数据库 output_data，4 个采集节点合计）**
- open.bilibili.com ×72、open.douyin.com ×44、developer.open-douyin.com ×28
- **第三方域名（volcengine/huasheng/diansan…）= 0**

**1.2 实际发给 Tavily 的 query（output_data）**
- `tavilyQuery` 实际只有 `"抖音 官方网站"`（执行 2 次）
- nodeConfig.plannedQueries 有 26 条（22 条锁 `includeDomains:["open.douyin.com"]`，4 条 `OPEN_WEB` 不限域名）
- **26 条精心生成的 query，没有一条出现在实际 tavilyQuery 里**

**1.3 PoC 对照（同一第三方 query 直打 Tavily 能搜到）**
- volcengine 技术文 30551 字、huasheng 行业分析 6723 字、developer 9401 字…
- 证明"搜不到第三方"不是数据缺失，是 query 没发出去

**1.4 字段覆盖最终状态**
- `fieldEvidenceFinalStatus = EVIDENCE_PATH_COVERAGE_NOT_MET`（明明没达标）
- 但候选池因官方壳候选数量够了，仍触发了 break

---

## 2. 根因代码定位

执行链：`SearchExecutionCoordinator.executeSupplementByFallbackOrder`（883 行起）

```
for (String stage : resolveSearchFallbackOrder(config)) {   // HYBRID → [PLANNED, BROWSER, HTTP]
    if (existing + supplemented >= targetPoolSize) break;    // 899 行：池满即停
    if ("BROWSER".equals(stage) ...) { browserSearchRuntimeService.search(config); ... }  // 903 行：先跑，发"官方网站"
    if ("HTTP".equals(stage) ...) {                          // 925 行：带 26 条 field query
        searchSourceProvider.search(buildSearchSourceRequest(config, ...));  // 1034/1040 行 fieldEvidenceQueries
    }
}
```

- **fallback 顺序**：`SearchPolicyResolver.resolveFallbackOrder` 默认 HYBRID = `[PLANNED, BROWSER, HTTP]`，BROWSER 在 HTTP 前。
- **field query 的承载点**：只有 HTTP stage 的 `buildSearchSourceRequest`（1040 行）带 `fieldEvidenceQueries`；
  `TavilyFastLaneProvider.searchScope`（127 行）也确认：只有 `request.getFieldEvidenceQueries()` 非空才走
  `searchFieldEvidenceQueries` 逐条执行，否则退化成只发 1 条 primary profile。
- **短路点**：BROWSER 凑满候选池 → 899 行 break → HTTP stage 不执行 → field query 不消费。

**核心矛盾**：`fieldEvidenceFinalStatus=COVERAGE_NOT_MET`（字段没达标）与"候选数够了就 break"并存——
break 只看候选**数量**，不看字段**覆盖是否达标**，于是"数量够但全是官方壳、字段没覆盖"时仍提前停。

---

## 3. 四轮根因修正全记录（方法论留痕）

| 轮次 | 判断 | 被什么推翻 | 层级 |
|---|---|---|---|
| 一 | 搜索没跑/Tavily 被旁路 | PoC 能搜到；output 有 Tavily metadata | — |
| 二 | 官方直采短路补采（改 shouldSkipSupplement/主从反转 08/08a） | recovery/repair/字段再采全触发 | — |
| 三 | 选源丢弃 prefetch 正文（09） | 真，但只是末端：池里本就没第三方可选 | 末端 |
| 四（本文，成立） | **field query 挂在 HTTP stage，被 BROWSER 先 break 跳过** | 候选池零第三方 + tavilyQuery 仅"官方网站" + fallback 顺序代码 | **最上游** |

教训（呼应 `2026-06-29-task66-planning-methodology-risks.md` §6.3）：
"配置有 26 条 query" ≠ "运行时携带"（携带了）≠ "主执行链消费"（**没消费，被 stage 顺序短路**）。
三层里断在第三层，且断点比前几轮判断的都更靠上游。

---

## 4. 修复方向（详见配套实施计划）

要害是让 field query 真正执行，两条互补思路：
- **A. break 不应在字段覆盖未达标时触发**：`targetPoolSize` break 增加"字段覆盖已达标"前提，
  `COVERAGE_NOT_MET` 时不许提前停，必须继续到 HTTP stage。
- **B. field query 不应排在 BROWSER 兜底之后**：field query 是 07 的主力取证手段，
  应在 BROWSER 朴素补源之前执行（调整 fallback 顺序或把 field query 执行前置）。

与既有计划关系：
- 本根因**优先级高于** 09（选源）和 08（主从）——它们解决"进来的不丢/官方为辅"，
  但只有本修复能让第三方长文**进得来**。丰富度的源头在此。
- 09 仍要做（即使丰富了，选源仍会按老逻辑丢 prefetch）；08 作为长期方向最后评估。

---

## 5. 数据库/代码参考
- 库 `ecommerce_agent`（127.0.0.1:5432，容器 postgres）；task_node.output_data(taskId=69)。
- `SearchExecutionCoordinator.java:883-948`（fallback 循环）、`SearchPolicyResolver.resolveFallbackOrder`、
  `TavilyFastLaneProvider.java:124-169`（field query 逐条执行入口）、`buildSearchSourceRequest:1034-1049`。
