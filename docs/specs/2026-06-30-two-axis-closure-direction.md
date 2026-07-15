+# 收口方向：架构 AI 化 + 业务阻断点（2026-06-30 立，2026-07-07 重写）

> 本文是 2026-06-30 复盘的结论沉淀，2026-07-07 结合真实工程状态重写。
> 用于**终止无边界深挖、明确从当前到交卷的完整收口路线**。
> 它优先于 `2026-06-11-business-landscape-and-optimization-roadmap-design.md` 的"9 链路全收口"叙事——6.11 总蓝图自此降级为**参考地图**（查阅用），不再作为待办驱动。

---

## 0. 为什么写这份文档

经过一个多月，系统已经能跑、且具备协作骨架（五角色 + Orchestrator 反馈回流 + sourceUrls 可追溯）。
真正的问题从来不是"还差很多功能"，而是缺一条画在地上的终点线，导致在最下游的 E2E 测试上反复调优、信噪比极低。

**这一轮重写要解决的认知问题：** 停止把"E2E 跑不通"当成需要无限深挖的信号。E2E 是最下游节点，任何上游微小波动都会在这里放大；在这里优化边际收益递减。正确的做法是**收口降级**：
1. 用一个**正常竞品**端到端跑出一份带 sourceUrls 的成功报告（允许诚实降级：缩范围、松准入、降放行门槛）；
2. 让"AI 驱动"名副其实（Orchestrator 决策由 LLM 而非 if-else 驱动）——这是简历核心卖点；
3. 可演示、可讲出 before/after 对比。

**达成即交卷，不追求"9 链路全绿"、不进 4.x、不做对话/RAG/细优化。** 作为第一个 agent 项目，做到 7-8 成的诚实完成度已经足够写进简历。

---

## 1. 当前工程真实状态（2026-07-07 核对代码）

> 本节是重写的核心增量：把"文档意图"替换成"代码实证"，后续所有决策都基于这里。

### 已具备（机制侧，不要重做）
- **五角色分工 + 静态 DAG 正向流转**：Collector / Extractor / Analyzer / Writer / Reviewer + Citation。
- **Orchestrator 反馈回流**：Extractor / Analyzer / Citation 缺证（有 sourceUrls）会触发 `SUPPLEMENT_EVIDENCE` 自动补采，动态插 Collector 节点并在同一次运行重新调度。
- **可追溯红线**：每个决策带 `sourceUrls / evidenceState`，走 `DecisionPolicyService` 确定性校验，生成 `DecisionTrace`。
- **claim 生命周期已修（2026-07-07）**：`SearchExecutionCoordinator` 的 field-evidence query gate 会把本轮真正 claim 成功的 fingerprint 透传进 `SearchExecutionTrace.fieldEvidenceClaimedFingerprints`；`CollectorAgent` 在节点 FAILED 时**只释放本次尝试**占用的 fingerprint（成功路径继续保留做跨节点去重）。这补上了阶段1最后一块地基裂缝——**节点失败自动 retry 不再被上一轮自己写入的 claim 自我饿死**。封板验证命令：`mvn -pl backend "-Dtest=CollectorAgentFieldEvidenceLoopTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest" test`，结果：14 个相关测试通过（0 failures / 0 errors）。

### 现状缺口（代码实证，这是要动的地方）

**A. 编排大脑是纯 if-else，零 LLM。**
`OrchestrationDecisionService.decide()`（`backend/.../orchestration/OrchestrationDecisionService.java:23-80`）顶层是一条按 `triggerNodeName` 字符串分支的 if-else 链，每个分支再做三趟循环，硬编码匹配 `ERROR` / `EVIDENCE_GAP` / `ANALYSIS_GAP` / `CITATION_GAP` 加 `sourceUrls.isEmpty()`。类注释明写"不调用 LLM，保证演示版本稳定可复现"。`confidence` 是硬编码字面量（0.95 / 0.35 / 0.20）。
- 输入契约 `OrchestrationContext`、输出契约 `OrchestrationDecision` **已存在且完整**；`OrchestrationDecision.reason` 字段已存在（当前只填静态字符串），但**没有传进 `DecisionTrace`**。
- 护栏 `DecisionPolicyService.evaluate()` 是独立的确定性校验，与 `decide()` 分离。
- **结论：换大脑的接缝很干净——只替换 `decide()` 内部逻辑，契约和护栏不动，不需要 4.x 重写。**

**B. 采集闸门四道串联，Tavily 长文在下游被筛掉。**
一个 Tavily 候选要过四道门：`TavilyPrefetchedContentGate`（长度/质量分）→ `CandidateVerifier`（联网核验，可丢弃）→ `CandidateOwnershipPolicy`（域名归属）→ `CollectionTargetSelector`（终端准入/丢弃）。
- 最高杠杆卡点：`CollectionTargetSelector.resolveEligibility`（`.../search/CollectionTargetSelector.java:278-280`）——非 prefetch-usable、非 verified、attempted-but-failed 的 Tavily 候选被当"未验证候选"终端丢弃。
- 归属误杀：`CandidateOwnershipPolicy.hasCompetitorOwnershipSignal`（`.../CandidateOwnershipPolicy.java:252-255`）——search-discovered 候选域名不符时，即使正文提到品牌也返回 false。这正是记忆里 task89 "OFFICIAL 退化成 FAILED"的真因来源。
- **最低风险松绑旋钮**：`application.yml` 的 `tavily-search.min-raw-content-chars`、`min-tavily-score`（config-only，不改代码逻辑）。

---

## 方面一：架构 —— 实现真正的"AI 驱动的协作"

**核心命题：协作机制已经有了，但驱动机制的"大脑"是 if-else，不是 AI。**（已由上节 A 项代码证实）

### 轴一 / 运行中决策（必做，简历核心，性价比最高）
- 改造：把 `OrchestrationDecisionService.decide()` 的 if-else 查表换成**一次 LLM 推理**——输入"任务目标 + 当前缺口 + 已有证据 + 可选动作"，输出"决策 + 理由"，**理由写进 `DecisionTrace`**（现在缺这一步，需补 trace 字段透传）。
- 护栏不动：`DecisionPolicyService` 与 sourceUrls 红线保留，作为 LLM 决策之上的确定性安全网。
- 价值：让"AI 驱动"名副其实，且是工业级标准叙事（**LLM 决策 + 确定性护栏**）。
- 契约现成：`OrchestrationContext`（输入）/ `OrchestrationDecision`（输出，含 `reason`）/ `DecisionPolicyService`（护栏）全部已存在，只换中间的"大脑"，**不需要 4.x 架构重写**。

### 轴二 / 开局规划（明确不做，留作未来方向）
- 现状：`CollaborationPlanService` 永远生成固定六角色串行，不看任务。
- 为何不做：动态拓扑会与静态 `dependsOn` 建图、可追溯红线打架，比轴一重得多；且 task66 模板错配可用更轻的模板分型解决，不必上轴二。
- 定位：**简历有轴一就够亮**。轴二写进"下一步演进方向"，体现架构视野即可，本轮不实现。

---

## 方面二：业务 —— 影响成功率的阻断点

**核心命题：就算大脑变聪明，链路上仍有几个"卡点"会让最终报告失败。这些是局部口径问题，不是架构问题。**

**纪律（防止重蹈无底洞）：只修"会让那个正常竞品跑不出报告（BLOCKER）"的阻断点，不修"能让报告更完美"的优化点。**

已知阻断点（按当前状态标注）：

1. **采集丰富度（阻断点0，最上游）** — 见上节 B 项。地基：搜到的东西只有官方壳页，后面再强也是 garbage in garbage out。**这是阶段1要松绑的主战场。**
2. **claim 生命周期自我饿死** — ✅ **2026-07-07 已修**。曾是隐藏 BLOCKER：节点失败 retry 被上一轮 claim 饿死到 executed=0 → 永久 WAITING_INTERVENTION。
3. **任务模板错配** — 开放平台等主题被强套标准版模板要 pricing/weaknesses，官方天然没有 → BLOCKER。对策：**阶段1直接用正常竞品规避**（不碰 task66），不靠改架构。
4. **质量评分口径混乱** — `score>=80`、Reviewer 维度评分、`passed/requiresHumanIntervention` 门禁、固定字段覆盖四套口径并存。⚠️ **降级红线**：可以放松"准入/放行策略"，但**绝不允许调低 `score>=80` 阈值来假装成功**。报告标"质量65分、3字段缺官方数据"算跑通，比"假装90分"强，也更好讲。
5. **策略层默认值** — `DecisionPolicyService` 缺证时默认压人工还是放行补采，直接决定链路是自动闭环还是处处卡人工。阶段1可诚实调成"放行补采"。
6. **证据准入** — ✅ 已在 07 落地（全字段允许第三方源、官方优先非排他）。仅作记录。

---

## 从现在到交卷：四阶段完整规划

> 你现在的位置：骨架完成 + claim bug 已修 → **地基已平，站在"可以盖楼"的起点**。

```
阶段1  降级到"每次跑通"     松采集闸门 + 选友好竞品 + 诚实降门槛
阶段2  编排 LLM 化(卖点)    decide() 换 LLM 推理 + reason 进 DecisionTrace + before/after
阶段3  前端演示页           任务→采集→分析→报告(带 sourceUrls) + DecisionTrace 可视化
阶段4  收卷 + 简历叙事       未来工作明确标注，写项目条目
```

### 阶段1 — 降级到"每次跑通"（只降门槛，不改大脑）
目标：选一个 Tavily 能搜到料的**正常竞品**，让 E2E 每次都能产出一份带 sourceUrls 的报告。三刀按序：
1. **选友好竞品**（demo 锚点，先定死）：有丰富第三方长文、非强反爬、模板不错配。
2. **松采集准入**：优先动 config 旋钮（`min-raw-content-chars`/`min-tavily-score`），必要时松 `CollectionTargetSelector` 终端 reject 与 `CandidateOwnershipPolicy` 对 search 候选的域名硬判。判据：不放它进来报告就失败吗？
3. **诚实降放行门槛**：阶段1封版线初始定为 "报告能产出 + sourceUrls 可追溯 + qualityScore≥60"；60-79 分允许作为“降级报告 / 需人工复核”交付，80 分以上才算优秀报告。若唯一一次友好基线 E2E 卡在 55-59，且无 BLOCKER、无核心证据 CRITICAL、来源和预算红线都满足，允许一次性把封版线校准到该次实测整数分（最低不低于 55）并记录理由；不再二次下调。无来源、BLOCKER 或核心证据 CRITICAL 仍不放行；不打开 Gate 1 / Gate 2 的多轮自动补采，避免循环和预算失控。
- ⚠️ 红线：降的是**准入和放行策略**，不是**评分诚实度**。

### 阶段2 — 编排 LLM 化（简历核心）
- `decide()` 换成一次 LLM 推理，理由写进 `DecisionTrace`（补 trace 字段透传）。护栏一行不动。
- 用阶段1同一竞品再跑，拿 **before(if-else) / after(LLM)** 对比 = 简历硬证据。

### 阶段3 — 前端演示页
- 一个干净页面演出链路：任务输入 → 采集进度 → 分析 → 最终报告（可点击 sourceUrls）。重点把 **DecisionTrace 和 before/after 可视化**。

### 阶段4 — 收卷 + 简历
- 明确标"未来工作"：对话协同、RAG、方面一轴二（动态开局）、4.x 动态编排——**都不做**，写进简历"下一步演进"体现视野。
- 写项目条目：多智能体协作骨架 + claim 生命周期治理 + LLM 决策大脑 + 全链路 sourceUrls 可追溯。

---

## 明确不做（防止再钻牛角尖）

| 不做 | 理由 |
|---|---|
| 4.x 动态编排 | 触发条件（≥3 链路指向 runtime 不足）未满足，两根杠杆都不需要它 |
| 方面一轴二 动态开局 | 与静态 DAG + 可追溯红线打架，比轴一重；"简历有轴一就够亮" |
| 对话协同 / RAG | 不影响主叙事，已决定放 |
| 质量评分口径统一 / 细优化 | 非 BLOCKER，记下不碰 |
| 调低 score 阈值假装成功 | 降级红线，会在面试穿帮 |

---

## 执行时机说明

- 用户当前不想连续跑 E2E 测试。因此**松闸门/降门槛/LLM 大脑的代码改动可以先做、攒着**，真正的 E2E 基线验证集中留到后面一次性跑，避免反复跑测试。
- 阶段顺序（地基先于楼）不变，但**执行时机**可灵活：先把阶段1、阶段2 的代码改好，最后集中验证。
- 阶段1 用**正常竞品**，绝不用 task66（模板错配已知干扰项，会污染判断）。
- 抖音 / 哔哩哔哩开放平台样例降级为复杂失败案例和压力测试，不作为阶段1 / 阶段2 before-after 基线。
- 达成项目终点（端到端成功报告 + 轴一 LLM 化 + 前端演示）即交卷，停止深挖。
