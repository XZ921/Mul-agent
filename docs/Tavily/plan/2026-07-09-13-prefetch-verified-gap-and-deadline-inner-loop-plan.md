# Stage1 双竞品 E2E 收口：两个最终根因与修复方向

- 日期：2026-07-09
- 来源现场：`tmp/stage1-degraded-live-e2e-drainfix-20260709-172938`（taskId=103, reportId=94）
- 对照现场：`tmp/stage1-degraded-live-e2e-rerun-20260709-155144`（taskId=102, reportId=93）
- 状态：根因已用现场数据定位，修复方案待实现

---

## 背景：collector drain 修复已生效，但暴露出下一层的两个真问题

上一刀（CollectorAgent 增加 30s deadline drain grace window）已验证有效：

| 指标 | 上轮 102 | 本轮 103 | 判定 |
|------|----------|----------|------|
| Notion DOCS(01_02) | 选中0/采0 | 选中4/采2 | drain 接住 |
| extract_schema | 1 竞品 | 2 竞品/successCount=2 | Notion 没再掉 |
| evidenceCount | 5 | 11 | 净证据翻倍 |
| 终态 | FAILED | STOPPED | 失败前移 |
| qualityScore | 34(改写后) | 14(初审毛坯) | 见下方说明 |

失败点从「证据饿死」前移到两个新层：证据验证章缺失（分数杀手）+ deadline 内层循环不受控（耗时）。

> 关于 34→14 的分数「下降」：不可直接横向比。34 是 102 走完 rewrite 链路的改写后成品分；14 是 103 初审毛坯分（rewrite/citation/final 三节点被 SKIPPED，从未改写）。是精装比毛坯，非系统退步。

---

## 问题一（分数杀手）：prefetch 证据 verified=None，绕过验证盖章直接进报告

### 现象（现场实证）

最终 11 条证据中，9 条 `verified=None`（占 82%），全部来自越界节点 02_02(Airtable DOCS) 与 02_03(Airtable REVIEW)：

```
verified=True:  2   （Notion 01_02，drain 救回，走完验证）
verified=None:  9   （Airtable 02_02×4 + 02_03×5，discoveryMethod=TAVILY_PHASE1_BOOTSTRAP）
verified=False: 0
```

这 9 条的关键字段：
- `selectionStage = SELECTED`（不是 VERIFIED）
- `selectionReason = "Tavily prefetch 正文可用"`
- `qualitySignals` 含 `TAVILY_PREFETCHED_CONTENT_CONSUMED` / `TAVILY_RAW_CONTENT_READY` → 是真消费了预取正文的证据，非空壳
- `sourceAuthenticityScore=0.9`、`sourceTier=OFFICIAL`、`repairRequired=false`、`issues=[]` → Gate 认为内容质量不差
- 但 `contentUsabilityScore=0.65` → 未到「免验证」的充分程度

reviewer 后果：`initialReview` 判 4 条 BLOCKER 级 `EVIDENCE_TRACEABILITY` / `ACTIONABILITY` 问题 → `requiresHumanIntervention=true` + `autoRewriteAllowed=false` → rewrite/citation/final 三节点 SKIPPED → 终态 STOPPED，qualityScore 冻结在初审 14 分。

### 根因（代码定位）

存在两条对「Tavily prefetch 可用」都会放行的路径，但门槛不一致：

**A. verifier 盖章路径** `CandidateVerifier.shouldSkipNetworkVerification`（`CandidateVerifier.java:259-278`），命中才走 `buildTavilyFastLaneVerificationTarget` 设 `verified=true` + `selectionStage=VERIFIED`。要求 5 个条件：
```
providerKey == "tavily"
fastLaneUsable == true
skipNetworkVerification == true          ← 关键闸
sourceUrls 非空
pageType ∈ {ARTICLE, OFFICIAL_DOC, PDF}
```

**B. selector 直通路径** `CollectionTargetSelector.isUsablePrefetchedCandidate`（`CollectionTargetSelector.java:334-339`），命中即 `selectionStage=SELECTED` 放行，但**不碰 verified 字段**。只要 3 个条件：
```
fastLaneUsable == true
hasPrefetchedContent == true
prefetchedContentRef 非空
```

B 的放行门槛是 A 的**真子集**，漏掉了 `skipNetworkVerification==true`（以及 pageType 集合校验）。

Gate（`TavilyPrefetchedContentGate.java:129-138`）对这 9 条判定 `skipNetworkVerification=false`（因内容可用性/长度未达免验证门槛，`resolveSkipNetworkVerification` 要求 `rawContentLength>=2000` 且 pageType∈SKIP_VERIFY 集）。含义是「内容可用，但没充分到可免验证，需再验一道」。

**接缝断**：Gate 说「要验证」，但 selector 无视 `skipNetworkVerification` 信号，把「需验证」的候选当「免验证 prefetch」直接选中，`verified` 停留在候选出生时的 None → 溜进报告 → 触发可追溯性 BLOCKER。

> 认知修正记录：追查过程中一度怀疑「pageType=None 是真根因」和「rawContentLength<2000 是确切触发条件」。前者已排除——报告 JSON 里 pageType=None 是持久化未落该字段，内存里 fastLaneUsable=true 反推 pageType 必有有效分类（见 `resolveFastLaneUsable` 要求 pageType∈FAST_LANE_PAGE_TYPES）。后者无法从现场钉死——`prefetchedRawContentLength` 报告未持久化、日志级别不够，只能确认「Gate 最终没给 skipNetworkVerification=true」这一结果（由 verified=None 反推），具体卡在 5 条件的哪一道属次要细节，不影响修复方向。

### 修复方向

修 `CollectionTargetSelector.isUsablePrefetchedCandidate`，让其**尊重 skipNetworkVerification 信号**，区分两种放行语义：
- `skipNetworkVerification==true` → 真免验证，selector 放行并盖 `verified=true` + `selectionStage=VERIFIED`（与 verifier fast-lane 对齐，逻辑集中一处）
- `skipNetworkVerification==false`（本例 9 条）→ 不当已完成 prefetch，放行去 `CandidateVerifier` 走完整网络验证，验过再盖 verified=true

红线约束（见记忆 `evidence-source-admission-principle`）：验证章只能盖在真正验过（或 Gate 明确授权免验）的证据上，不得为提分批量盖假章。

---

## 问题二（耗时/语义）：hard deadline 只在 collector 外层，内层循环不消费它

### 现象（现场实证）

节点起跑均在 17:31:13 并行，但两个 Airtable 节点严重越过 hard deadline：

| 节点 | 类型预算 | 实际时长 | 收口状态 |
|------|----------|----------|----------|
| collect_sources_02_02 (Airtable DOCS) | 150s | **586s** | SUCCESS（应为 SUCCESS_DEGRADED） |
| collect_sources_02_03 (Airtable REVIEW) | 120s | **335s** | SUCCESS（应为 SUCCESS_DEGRADED） |

02_02 从 17:31:13 起跑到 17:40:59 结束（共 586s），期间在 Playwright 里对 Airtable 文档页**串行**反复渲染（每页失败→换下一个→重试），150s 硬 deadline 未能中断。

### 根因（代码定位）

hard deadline 的检查点是分段的、每段各自计时的：
- `executeSearchWithinHardDeadline`（`CollectorAgent.java:1807`）：search 超时 → drain 给 30s
- `executeCollectionCoordinatorWithinHardDeadline`（`CollectorAgent.java:1980`）：collection 超时 → drain 再给 30s

`drainCollectionReportAfterHardDeadline`（`CollectorAgent.java:2026`）用 `future.get(graceMillis)`。更精确地说：`CollectionExecutionCoordinator` 内部的多目标 queue / join / Playwright retry **不消费**外层的共享 deadline token，而外层的 `future.cancel(true)` 对不可中断的采集调用（Playwright 渲染、阻塞式 HTTP）约束不足——中断信号无法真正打断正在进行的采集。两者叠加，导致超预算的采集结果仍被正常收口返回，30s 闸门形同虚设。

这与记忆 `task86-sitemap-blocking-http-same-class-as-83` 同构：闸门在外层，真正烧时间的循环在内层，外层 deadline token 管不到内层每一圈。

两个后果：
1. **耗时失控**：02_02 堆到 586s，长尾拖住 DAG。
2. **语义不一致**：因内层循环「自己跑完」（每轮都在 grace 内返回、从未真正触发外层 timeout→cancel），`future.get()` 正常返回被当 SUCCESS，未打 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED` 降级标记。越界了却没被砍、也没被标降级。

### 修复方向

1. **主刀**：把同一个 hard deadline token（epochMillis）传进 `SearchExecutionCoordinator` / `CollectionExecutionCoordinator` / `PlaywrightPageCollector` 的**多目标循环体内部**，每个 target / 每次 Playwright 调用**前**检查剩余时间（含 grace），到点 break。堵住「内层逐段续命」的真根因——仅传 token 不够，循环必须真正消费它。
2. **搭刀**：因 deadline break 出来的部分结果统一打 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`，解决 SUCCESS 语义不一致。

---

## 两问题的耦合关系（必须一起评估）

问题一的修复会让 9 条 `skipNetworkVerification=false` 的证据改走完整网络验证 → 需重新抓页；而它们正处于问题二的越界节点 02_02/02_03。若只修问题一不修问题二：抓页验证可能又撞 deadline → 验证拿不到 → evidenceCount 可能从 11 回落至 2（退回证据饿死）。

反之只修问题二（砍越界）不修问题一：耗时受控了，但 9 条仍 verified=None，reviewer 照样判 BLOCKER，仍是 14 分——**砍越界本身不提分**。

结论：两刀耦合，需一起做、一起评估，否则会在「守时但饿死」与「越界但未验证」之间摆动（记忆 `search-first-tavily-amplifier-root-cause` 的「预算数学对不齐」换位复现）。

---

## 第三层（本轮不处理，仅登记）

证据数量过线后，失败前移到质量门：writer/reviewer 认为来源家族单一（大量 readthedocs/support 文档站，缺测评/新闻类）、字段支撑与报告结构不足。这是采集层修好后暴露的下一层真问题，属「来源家族多样性」范畴，建议本轮先不碰，待上述两刀闸死采集时序与验证章后单独处理，避免一刀切多个层。

---

## 验收方式

两刀实现（含回归测试）后，用与 102/103 相同的 Notion/Airtable 双竞品 payload 原样复跑，核对：
- 9 条 prefetch 证据是否从 verified=None → true（或经真实验证后盖章）
- evidenceCount 是否保住（不回落至 2）
- 02_02 时长是否压回 ~180s 内（150s 预算 + 30s grace）
- 02_02/02_03 越界收口是否正确标 SUCCESS_DEGRADED + HARD_DEADLINE_REACHED
- qualityScore 是否较 14 明显抬升
