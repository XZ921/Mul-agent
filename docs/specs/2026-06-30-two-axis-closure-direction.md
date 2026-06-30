# 收口方向：架构 AI 化 + 业务阻断点（2026-06-30）

> 本文是 2026-06-30 复盘讨论的结论沉淀，用于**终止无边界深挖、明确收口路线**。
> 它优先于 `2026-06-11-business-landscape-and-optimization-roadmap-design.md` 的"9 链路全收口"叙事。
> 6.11 总蓝图自此降级为**参考地图**（查阅用），不再作为待办驱动。

---

## 0. 为什么写这份文档

经过一个月，系统已经能跑、且具备协作骨架（五角色 + Orchestrator 反馈回流 + sourceUrls 可追溯）。
真正的问题不是"还差很多功能"，而是缺一条画在地上的终点线。本文把后续工作收敛成**两个方面**，
每个方面都设硬边界，做完即停。

项目终点（修正后）：
1. 用一个正常竞品**端到端跑出一份带 sourceUrls 的成功报告**；
2. 让"AI 驱动"名副其实（Orchestrator 决策由 LLM 而非 if-else 驱动）；
3. 可演示、可讲出 before/after 对比。达成即交卷，不追求"9 链路全绿"。

---

## 方面一：架构 —— 实现真正的"AI 驱动的协作"

**核心命题：协作机制已经有了，但驱动机制的"大脑"是 if-else，不是 AI。**

机制侧（已具备，不要重做）：
- 五角色分工 + 静态 DAG 正向流转；
- Orchestrator 反馈回流：Extractor / Analyzer / Citation 缺证（有 sourceUrls）会触发
  `SUPPLEMENT_EVIDENCE` 自动补采，动态插 Collector 节点并在同一次运行重新调度；
- 每个决策走 `DecisionPolicyService` 校验、带 `sourceUrls / evidenceState`、生成 `DecisionTrace`。

缺口侧（要做的两根杠杆）：

### 轴一 / 运行中决策（必做，性价比最高）
- 现状：`OrchestrationDecisionService.decide` 是 `if(节点名 && 有没有 sourceUrls)` 的查表，
  不思考、不权衡、不理解任务。
- 改造：把这段 if-else 换成**一次 LLM 推理**——输入"任务目标 + 当前缺口 + 已有证据 + 可选动作"，
  输出"决策 + 理由"，理由写进 `DecisionTrace`。
- 护栏不动：`DecisionPolicyService` 与 sourceUrls 红线保留，作为 LLM 决策之上的确定性安全网。
- 价值：让"AI 驱动"名副其实，且是工业级标准叙事（**LLM 决策 + 确定性护栏**）。
- 契约现成：`OrchestrationContext`（输入）/ `OrchestrationDecision`（输出）/ `DecisionPolicyService`（护栏）
  全部已存在，只换中间的"大脑"，**不需要 4.x 架构重写**。

### 轴二 / 开局规划（可选，有余力再做）
- 现状：`CollaborationPlanService` 永远生成固定六角色串行，不看任务。
- 改造：让 Orchestrator 在任务开局根据竞品特点决策——是否并行采多家、是否跳过某角色、
  选哪个模板/Schema（这一步顺带治 task66 的模板错配根因）。
- 风险：动态拓扑会与静态 `dependsOn` 建图、可追溯红线打架，比轴一重。
- 定位：锦上添花。简历有轴一就够亮，不一定非要轴二才能交卷。

---

## 方面二：业务 —— 影响成功率的阻断点

**核心命题：就算大脑变聪明，链路上仍有几个"卡点"会让最终报告失败。这些是局部口径问题，不是架构问题。**

已知阻断点（来源于自有诊断文档，有据可查）：

1. **任务模板错配（成功率头号杀手）**：开放平台等主题被强套标准版模板要 pricing/weaknesses，
   官方天然没有 → BLOCKER。注意：这在"开局之前"，**LLM 大脑救不了它**，需靠模板分型或轴二动态选模板解决。
   诊断见 `docs/Tavily/problem/2026-06-29-task66-database-evidence-mixed-cause-diagnosis.md`。
2. **质量评分口径混乱**：`score>=80 无 ERROR` 展示口径、Reviewer 维度评分、
   `passed / requiresHumanIntervention` 门禁、固定字段覆盖规则，四套口径并存，
   易"调低阈值掩盖评分失真"。应统一 `score / qualityGate / deliveryStatus / evidenceState` 语义边界。
3. **策略层默认值**：`DecisionPolicyService` 缺证时默认压人工还是放行补采，
   直接决定链路是自动闭环还是处处卡人工。
4. **证据准入**：已在 07 落地（全字段允许第三方源、官方优先非排他）。**此项已解决**，仅作记录。

### 阻断点 0（最上游）：采集丰富度——搜到的东西就是地基

> 比模板/评分更靠前。搜索采集到的素材若只有官方壳页，后面提取/分析/写作再强也是 garbage in garbage out。
> task69 实证：07 已能生成丰富 query、Tavily 也能搜到 6000~30000 字第三方长文，但运行期只落 2 个官方壳页。
> 经四轮根因核查（记录见两份诊断），定位到两个独立断点，按**执行顺序**修复：

| 序 | 计划 | 解决什么 | 诊断依据 |
|---|---|---|---|
| **08** | `task/2026-06-30-08-execute-field-queries-unblock-fallback-task.md` | 让 07 的第三方 query **真执行**（现被 fallback 顺序提前 break 跳过）→ 第三方长文**进得来** | `problem/2026-06-30-task69-field-query-skipped-by-fallback-order-diagnosis.md` |
| **09** | `task/2026-06-30-09-selection-accept-prefetch-evidence-task.md` | 让进来的 prefetch 丰富正文**不被选源丢弃**（现 BOOTSTRAPPED 候选被当未验证丢） | `problem/2026-06-30-task69-prefetch-evidence-dropped-at-selection-diagnosis.md` |
| **10** | `plan/2026-06-30-10-search-first-collection-routing-plan.md` | 主从反转（搜索为主、官方为辅）——**长期方向**，待 08/09 数据后评估是否仍需 | 同上两份 |

执行顺序铁律：**先 08（进得来）→ 再 09（不丢）→ 10 视数据**。验收线 = task69 重跑落库丰富度接近 PoC（多域名、多条 >2000 字正文）。

**方面二的纪律（防止重蹈无底洞）：**
> 只修"会让报告直接失败（BLOCKER）"的阻断点，不修"能让报告更完美"的优化点。
> 判断标准只有一条：这个问题会不会让那个正常竞品跑不出报告？会就修，不会就记下来留着，别碰。

---

## 两个方面的关系与执行顺序

**业务地基先于架构楼。** 地基不平（模板会错配、评分会误杀），楼盖得再漂亮也是危房：
LLM 大脑产出的报告一样过不了，而且失败原因会和"LLM 决策好不好"纠缠在一起，无法调试——
这正是过去一个月吃过的亏。

```
阶段0  收尾 07 封板          → 跑 07 测试套件，绿了提交，采集线封板不开 08，清掉半成品干扰
阶段1  端到端基线(正常竞品)   → 踩平方面二地基，顺手拿到 before 基线
         ├ 跑通 → 地基 OK，直接进阶段 2
         └ 卡住 → 当场暴露的就是方面二真实阻断点，先修它（只修 BLOCKER）
阶段2  LLM 大脑(方面一轴一)   → 盖楼，用同一竞品再跑，拿 after 对比基线 = 简历硬证据
阶段3  动态开局(方面一轴二)   → 可选加盖
```

**关键纪律：**
- 阶段 1 用**正常竞品**，绝不用 task66（其失败是模板错配这个已知干扰项，会污染判断）。
- 不开 4.x：触发条件（≥3 链路指向 runtime 不足）未满足，且两根杠杆都不需要它。
- 6.11 总蓝图的 5 个 ⬜ 待诊断链路，让它们保持 ⬜；只有阶段 1 真的卡在某链路时才翻开查。
- 达成项目终点（端到端成功报告 + 轴一 LLM 化）即交卷，停止深挖。

