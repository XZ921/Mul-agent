# Task69 真根因诊断：Tavily prefetch 正文在选源阶段被丢弃

> 2026-06-30。本文是 task69（抖音+B站开放平台，能力介绍模板）失败的**最终根因诊断**，
> 基于数据库 + 采集节点 output_data + 选源代码三方实证，并记录三轮误判的修正过程，
> 供后续避免重复"修错地方"。

---

## 0. 结论先行

task69 失败的真根因**不是**"搜索被旁路"，也**不是**"官方直采短路补采"，而是：

> **Tavily Fast Lane 已经抓到了 2049 字的真文档（深层 docs 页），存在候选的 `prefetchedRawContentLength` 里，
> 但最终选源 `CollectionTargetSelector.resolveEligibility` 只认 `collectedPage.content` 和
> `verified/explicit` 候选，看不到 prefetch 正文，于是把这个 BOOTSTRAPPED 候选当"未验证候选"丢弃，
> 只选中了 verified 的根域壳页（484/433 字）。**

即：**采到了好内容，却在"采到 → 落库"之间的选源环节被丢。问题在出口，不在入口。**

---

## 1. 实证链（数据库 + output_data）

task69 采集节点 `collect_sources_01_01`(抖音) 的候选明细：

| URL | discoveryMethod | 正文 | verified | fastLaneUsable | selectionStage | 结局 |
|---|---|---|---|---|---|---|
| open.douyin.com | DIRECT_LOCATOR | null | true | false | **SELECTED** | 落库（壳页 484 字） |
| open.douyin.com/platform/.../docs/develop/… | **TAVILY_PHASE1_BOOTSTRAP** | **2049** | null | **true** | **BOOTSTRAPPED** | **被丢弃** |
| open.douyin.com/about | PUBLIC_EVIDENCE_RECOVERY | null | true | null | VERIFIED | 未落库 |
| open.douyin.com/app · /download · /help | PUBLIC_EVIDENCE_RECOVERY | null | true | null | VERIFIED | 未落库 |

补采链路状态（证明补采/repair 全都正常触发，不是被短路）：
- `publicEvidenceRecoveryTriggered=true`、`publicEvidenceRecoveryStatus=RECOVERED_PUBLIC_PAGE`、`recoveryVerifiedCount=4`
- `repairRequired=true`、`evidenceRepairPlan.repairState=REPAIR_EVIDENCE_PROMOTED`
- `fieldEvidenceRecollectionTriggered=true`、`fieldEvidenceLoopRounds=2`、`fieldEvidenceQueryCount=26`
- `fieldEvidenceFinalStatus=EVIDENCE_PATH_COVERAGE_NOT_MET`（最终覆盖没达标）

最终 `evidence_source` 落库：仅 2 个 distinct URL、2 份正文、全是 484/433 字壳页。

那个 2049 字候选的精确快照：
`discoveryMethod=TAVILY_PHASE1_BOOTSTRAP`、`verified=null`、`fastLaneUsable=true`、
`prefetchedRawContentLength=2049`、`selectionStage=BOOTSTRAPPED`。

---

## 2. 根因代码定位

`CollectionTargetSelector.resolveEligibility`（`backend/.../search/CollectionTargetSelector.java:123-217`）
逐分支处理该 BOOTSTRAPPED 候选：

1. `hasUsableCollectedPage`（230-236 行）取的是 `target.getCollectedPage().getContent()/getSnippet()`——
   **BOOTSTRAPPED 候选的 2049 字正文在 `prefetchedRawContentLength`，不在 collectedPage** → 判为"无可用正文"。
2. `isExplicitCandidate`（223-228 行）白名单是 `DIRECT_LOCATOR/FAMILY_TEMPLATE/FAMILY_SUBDOMAIN_TEMPLATE/HEURISTIC`
   或 providerKey=`planned`——**`TAVILY_PHASE1_BOOTSTRAP` 不在其中** → 非 explicit。
3. `verified=null` → 不进 165 行 verified 入选分支。
4. 于是穿过所有"可入选"分支，最终落到 214 行 `未验证候选不能进入正式采集目标`（或 188 行降级分支也未把它升为 SELECTED）。

**核心缺陷：选源的"可用正文"判定与"可入选候选类型"白名单，都不认识 Tavily prefetch 正文与 BOOTSTRAPPED 来源。**

---

## 3. 三轮误判修正记录（防止重复踩坑）

| 轮次 | 当时判断 | 被什么推翻 |
|---|---|---|
| 一 | "只能搜到壳页，Tavily 没被调用/被旁路" | Tavily PoC 测试：同 query 能搜到 6000~30000 字长文；且 output_data 显示 Tavily metadata 存在 |
| 二 | "官方直采为主短路了搜索补采（改 shouldSkipSupplementForDirectDiscovery / 主从反转）" | output_data：`recoveryTriggered=true`、`repairState=REPAIR_EVIDENCE_PROMOTED`、字段再采跑满 2 轮——补采根本没被短路 |
| 三（成立） | "采到的 2049 字 prefetch 正文在选源阶段被丢弃" | 候选明细 + `resolveEligibility` 代码：BOOTSTRAPPED+prefetch 正文无入选路径 |

**方法论教训**（呼应 `2026-06-29-task66-planning-methodology-risks.md`）：
- 不要凭"症状（只剩壳页）"反推根因（"搜索没跑"），必须查运行时状态字段（recoveryTriggered/repairState/selectionStage）。
- "配置/能力存在"≠"运行时真消费"≠"产物被采纳"。task69 是第三层断裂：采到了、但没被采纳。

---

## 4. 对既有计划的处置

- **08a（解除壳页短路）**：已作废删除——它修的短路在 task69 没发生。
- **08（主从反转，搜索为主）**：方向本身没错（长期应让搜索为主），但**不是 task69 直接根因**，
  降级为"后续上游方向"，不作为当前修复项。
- **本诊断指向的修复**：见配套实施计划（CollectionTargetSelector 认 prefetch 正文 + BOOTSTRAPPED 入选路径），
  改动集中在一个类，不碰 family 主从、不碰短路、不新增 gate。

---

## 5. 数据库参考
- 库：`ecommerce_agent`（dev，127.0.0.1:5432，容器 `postgres` = pgvector/pgvector:pg16）
- 关键查询：`task_node.output_data`（taskId=69, node=collect_sources_01_01）含全部候选 selectionStage；
  `evidence_source`（task_id=69）为最终落库结果。
