# Task66 计划方法论风险复盘

> 2026-06-29。本文沉淀 task66 连续三轮批评中反复出现的三个系统性问题，目的不是重复列举症状，而是为后续 Tavily / 搜索采集计划提供方法论约束，避免计划继续只修下游、不碰上游。

---

## 0. 结论先行

task66 当前暴露出来的问题，不只是某几个实现 bug，而是计划方法本身存在偏差：

1. `01-05` 主要修的是下游容错、审计和恢复能力，没有真正修上游搜索质量。
2. Tavily 主搜索链路在执行形态上，仍然是“每个 scope 一条 primary query，必要时再加一条 expansion query”，没有形成多条语义互补 query 的真实执行能力。
3. 计划编写方式偏“症状修复”，导致根因级能力被反复识别、反复推迟，系统复杂度持续增加，但核心搜索能力几乎停滞。

这三点如果不被明确记录，后续计划很容易继续在 `gate / audit / recovery / repair` 外围能力上加层，而不敢触碰 `query generation / query planning / targeting strategy` 这些真正决定成功率的核心逻辑。

---

## 1. 问题一：01-05 修的是下游容错，不是上游搜索质量

### 1.1 问题描述

从 `00 roadmap` 到 `01-05` 各阶段实际交付，可以看出本轮修复主线是：

- 字段契约对齐
- 证据质量门禁
- Tavily 结构化块与 fast-lane 审计
- 公开证据补采
- repair 状态与回归测试

这些工作都有价值，而且确实能让系统在搜索失败、正文低质、字段错位时“更体面地失败”。但它们共同的特点是：都发生在“内容已经命中之后”。

也就是说，计划重点在于：

- 命中后如何识别垃圾内容
- 命中后如何结构化
- 命中失败后如何补采
- 补采后如何审计
- 最终如何避免 Reviewer 误杀

而不是：

- 为什么最初会搜到验证码页、壳页、中介页
- 为什么 Tavily 没有优先命中更有价值的细页或文档页
- 为什么 query 没能表达真实字段目标

### 1.2 工程证据

- [2026-06-28-00-task66-execution-roadmap.md](E:\java_study\Mul-agnet\docs\Tavily\plan\2026-06-28-00-task66-execution-roadmap.md:7)
  - `01-05` 的阶段定义全部是覆盖契约、质量门禁、结构化、补采、repair/regression。
- [2026-06-28-03-task66-tavily-structured-fastlane-plan.md](E:\java_study\Mul-agnet\docs\Tavily\plan\2026-06-28-03-task66-tavily-structured-fastlane-plan.md:22)
  - `FieldEvidenceQueryPlanner` 被明确后移，不作为 `03` 交付物。
- [2026-06-28-task66-self-check-report.md](E:\java_study\Mul-agnet\docs\Tavily\problem\2026-06-28-task66-self-check-report.md:98)
  - 自检已经明确指出“query intent 与字段目标不匹配”“Tavily 命中细页但未稳定入选”，说明上游搜索质量问题已被识别。
- [TavilySearchProfileResolver.java](E:\java_study\Mul-agnet\backend\src\main\java\cn\bugstack\competitoragent\search\tavily\TavilySearchProfileResolver.java:136)
  - Tavily 主查询模板仍是固定 family 级关键词拼接，没有被改造成字段级 query planning。

### 1.3 风险判断

如果只修下游容错，不修上游搜索质量，系统会越来越擅长：

- 解释失败
- 过滤失败结果
- 在失败后降级
- 在失败后补采

但不会明显更擅长：

- 一开始就命中正确页面
- 在更少轮次里拿到更高价值证据
- 让后续 gate / repair 的触发频率自然下降

这会让系统越来越“稳”，但不一定越来越“强”。

---

## 2. 问题二：Tavily 主链路仍是 1-2 条模板 query，没有形成互补 query 机制

### 2.1 问题描述

task66 相关讨论反复指出的第二个核心问题是：系统虽然在配置层、模板层、审计层已经支持 `searchQueries`、`queryIntents` 等概念，但 Tavily 主执行链路并没有真正把这些能力转成“多条互补 query 的检索策略”。

当前 Tavily 主链路更接近下面这个形态：

- 每个 `scope` 先生成 1 条 primary query
- 当 primary profile 不足时，最多再追加 1 条 expansion query
- primary 与 expansion 都是固定模板拼接
- expansion 主要是放宽关键词或放宽域名约束，不是换检索视角

这与问题文档和 JSON 测试中展示的“3 条互补 query 各自从不同角度覆盖同一 topic”的策略不是一回事。

### 2.2 工程证据

- [TavilyFastLaneProvider.java](E:\java_study\Mul-agnet\backend\src\main\java\cn\bugstack\competitoragent\source\TavilyFastLaneProvider.java:123)
  - 每个 `scope` 只执行一次 `primaryProfile` 搜索；满足条件时再执行一次 `expansionProfile` 搜索。
- [TavilyFastLaneProvider.java](E:\java_study\Mul-agnet\backend\src\main\java\cn\bugstack\competitoragent\source\TavilyFastLaneProvider.java:177)
  - 对普通模式下的 `request.searchQueries`，只取第一条做 primary override，而不是把多条 query 展开成多轮 Tavily 搜索。
- [TavilySearchProfileResolver.java](E:\java_study\Mul-agnet\backend\src\main\java\cn\bugstack\competitoragent\search\tavily\TavilySearchProfileResolver.java:141)
  - 例如 `OFFICIAL`、`DOCS`、`PRICING` 仍然是固定关键词拼接。
- [tavily-search-test-summary.md](E:\java_study\Mul-agnet\docs\Tavily\tavily-search-test-summary.md:117)
  - 测试样例已经展示了 3 条互补 query 的效果，并且统计了更高的 URL/域名多样性。

### 2.3 需要特别收紧的表述

这里要避免一个常见误判：

> “系统里存在多条 `searchQueries` 配置”  
> 不等于  
> “Tavily 主搜索链路已经具备多条互补 query 执行能力”

当前工程事实更准确的描述是：

- 配置层可以存多条 query
- 某些非 Tavily provider 也会执行多条 query
- 但 Tavily fast-lane 主链路并没有把这些 query 真正转化为多轮互补检索

### 2.4 风险判断

如果这个问题不修，系统会长期依赖 Tavily 自己的排序、召回和域名倾向来决定结果质量，而不是显式地通过多视角 query 去控制：

- 结果覆盖角度
- 来源多样性
- 官方资料与第三方资料的平衡
- 同一 topic 的交叉验证能力

最终结果就是：外层做了很多质量治理，但入口输入仍然单薄。

---

## 3. 问题三：计划方法偏症状修复，根因能力被反复后移

### 3.1 问题描述

前三轮批评里最值得沉淀为长期注意事项的，不是某个具体实现缺口，而是计划编写方式本身的问题：

- 计划容易围绕“上次暴露出来的症状”写
- 容易优先做实现风险低、边界清晰、对现有主链冲击小的外围加固
- 容易把真正影响主行为的核心逻辑，推迟到“下一阶段”

这会形成一种非常危险的惯性：

1. 根因在 spec 里已经被识别
2. 但因为实现难、影响大、需要改主链，所以暂时不做
3. 下一阶段又出现新的症状，于是继续优先修症状
4. 根因继续后移
5. 系统外围复杂度不断增加，主能力却没有同步前进

`FieldEvidenceQueryPlanner` 就是这个问题最典型的样本。

### 3.2 工程证据

- [2026-06-28-task66-coverage-contract-and-test-strategy-design.md](E:\java_study\Mul-agnet\docs\Tavily\specs\2026-06-28-task66-coverage-contract-and-test-strategy-design.md:826)
  - spec 已经明确把 `FieldEvidenceQueryPlanner` 定义为从 source-first 切到 field-first 的关键层。
- [2026-06-28-03-task66-tavily-structured-fastlane-plan.md](E:\java_study\Mul-agnet\docs\Tavily\plan\2026-06-28-03-task66-tavily-structured-fastlane-plan.md:22)
  - `03` 明确后移。
- [2026-06-28-04-task66-search-collection-public-evidence-recovery-plan.md](E:\java_study\Mul-agnet\docs\Tavily\plan\2026-06-28-04-task66-search-collection-public-evidence-recovery-plan.md:7)
  - `04` 强调“补采服务必须能接收字段上下文”，但没有真正落地 query planner。
- [2026-06-28-05-task66-repair-regression-plan.md](E:\java_study\Mul-agnet\docs\Tavily\plan\2026-06-28-05-task66-repair-regression-plan.md:702)
  - 回归测试命令里包含 `FieldEvidenceQueryPlannerTest`，但阶段内容没有真正实现该能力。

这说明它在“问题识别层”和“验收想象层”已经存在，但在“执行交付层”被不断推迟。

---

## 4. 问题四：live 复验证明 04→05 仍在治理失败，而非提升成功率

### 4.1 问题描述

2026-06-29 bilibili 下载中心 live 复验（`live-bilibili-public-evidence-recovery-20260629-174119-rerun`）提供了一个完整样本，证明问题一、二、三不是理论推演，而是已经在真实链路中阻断了交付。

该次 live 的配置是 `reportTemplate=标准版`、`competitorUrls=["https://app.bilibili.com"]`、节点配置挂载 `coverage-standard_competitor_report-v1`，7 个 `requiredCoverageFields / blockingCoverageFields`（`summary / positioning / targetUsers / coreFeatures / strengths / pricing / weaknesses`）全部要求。这不是"能不能采到"的轻量验证，而是"完整竞品报告"的验收。

最终结果：

- 只落库 1 条证据：`T0068-COLLECT_SOURCES_01_01-001`，URL 是 `https://app.bilibili.com`（下载中心页），`sourceType=OFFICIAL`，`discoveryMethod=DIRECT_LOCATOR`，`selectionStage=SELECTED`
- `publicEvidenceRecoveryTriggered=false`，`publicEvidenceRecoveryStatus=RECOVERY_NOT_TRIGGERED`
- 抽取 `evidenceCoverage`：`summary=TRACEABLE`、`targetUsers=TRACEABLE`、`positioning=EMPTY`、`coreFeatures=EMPTY`、`strengths=EMPTY`、`pricing=EVIDENCE_NOT_COVERING`、`weaknesses=EVIDENCE_NOT_COVERING`
- Reviewer 输出 `unsupported_claim ×2`（市场定位节、核心功能节）+ `coverage_gap ×1`（定价/不足/优势等多节），级别分别为 `MAJOR` 和 `BLOCKER`
- 最终 `qualityPassed=false`、`qualityScore=20`、`deliveryStatus=BLOCKED`、需要人工处理

### 4.2 为什么 04 修好了但这次仍然失败

失败链上的五个直接原因，完整暴露了"04 安全性"与"05 充分性"之间的设计张力：

1. **任务契约要求完整标准报告。** 节点配置里 `requiredCoverageFields` 和 `blockingCoverageFields` 都包含全部 7 个字段。系统这次不是在做一个"验证官网公开页能不能采到"的轻量任务，而是在按"完整竞品报告"验收。

2. **实际采到的证据只有 1 条，而且内容非常窄。** `app.bilibili.com` 本质上是下载中心页，只能支撑：多端下载入口、安卓/iOS/TV/PC/车机用户讨论群。它几乎不提供市场定位、核心功能细节、商业定价、优势/短板。输入一开始就太薄了。

3. **搜索层 recovery 被短路了。** `SearchExecutionCoordinator.shouldTriggerPublicEvidenceRecovery`（[SearchExecutionCoordinator.java:1366-1368](backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java#L1366-L1368)）在 `hasVerifiedCandidate=true` 时直接返回 `false`，不再检查 `recoveryFieldName / recoveryEvidencePathKey / recoveryQueryIntents`。因为下载中心页自己已经拿到了"可用公开正文"并被验证通过，所以系统没有继续走"按字段去找 docs / pricing / policy / help"那条 recovery 分支。这对 04 阶段是好事（显式候选可安全入选修好了），但对"完整报告可交付"不是好事（没补到更深层页面）。

4. **抽取结果客观证明了字段覆盖不够。** `evidenceCoverage` 精确区分了 `TRACEABLE / EMPTY / EVIDENCE_NOT_COVERING` 三种状态。系统不是胡乱失败，而是很准确地告诉你：有些字段完全抽不出来，有些字段当前证据明确不覆盖。

5. **Writer 写了推测性句子，Reviewer 按规则拦截。** 报告里虽然已经写了"本节缺失"，但同时写了很多类似"推测哔哩哔哩可能具备视频播放、弹幕互动……""推测其可能采用免费+会员……""推测其优势可能在于 UGC 生态……"的句子。这些句子没有足够证据回指，Reviewer 按无幻觉原则打出 `unsupported_claim` 和 `coverage_gap`。

**一句话总结：不是采集链路没跑通，04 解决的是"能不能安全采到公开证据"（can collect safely），现在卡住的是"采到的这点证据够不够支撑标准报告"（is collected sufficient for full report）。** 04 的 recovery 定位是"主入口受限时的替代源补采"，不是"主入口可访问但太浅时的深度补采"。这两个场景需要不同的触发条件。

### 4.3 05 能改变什么、不能改变什么

05 计划引入三样新能力：

- **采集后 recovery 路径（`CollectorAgent`）**：采集完成后检查 `EvidenceQualityVerdict.isRepairRequired()`，如果质量门禁判定证据太弱（例如质量门禁识别出"根域入口页 + 内容窄"），就生成 `RepairPlan`。这条路径不经过 `shouldTriggerPublicEvidenceRecovery` 的短路逻辑——它不看"是否有 verified candidate"，而是看"候选的正文质量是否够"。下载中心页这种"验证通过但浅"的情况**有可能**被触发。

- **`EvidenceRepairPlan` 状态模型**：`REPAIR_QUERY_PROPOSED → REPAIR_CANDIDATE_VERIFIED → REPAIR_EVIDENCE_PROMOTED` 生命周期，repair 不再是黑箱，下游可以区分"只生成了 query"和"真的提升了证据"。

- **`FieldAnswerSynthesizer`**：把字段证据路径的覆盖状态合成为可审计结论，记录 `sourceUrls / reasoningSteps / recommendedNextAction (ACCEPT_CONCLUSION / REPAIR_WITH_TAVILY / REQUIRE_HUMAN_REVIEW / DOWNGRADE_FIELD)`。这样字段缺失至少是可诊断的，而不只是报告里一段模糊的空缺说明。

但 05 **不能**解决以下结构性缺口：

- **不会实现多轮 deepening 执行环。** `CollectorAgent` 生成的 repair plan 只写入 metadata，不自动回到采集阶段再执行 repair 候选。闭环 `collect → detect shallow → plan field-specific re-collection → execute → verify → re-evaluate → sufficient / abandon` 没有落地。Repair plan 留给后续轮次或人工处理。

- **不会改变搜索层 recovery 的门控。** `shouldTriggerPublicEvidenceRecovery` 的 `hasVerifiedCandidate → return false` 逻辑不变。如果未来某次执行里 CollectorAgent 还没来得及介入，搜索层仍然会走同样的短路。

- **不会新增 `FieldEvidenceQueryPlanner`。** 采集规划仍是 sourceType-first（去采 OFFICIAL / DOCS），不是 field-first（为 `pricing` 字段去采 `/pricing`、`/plans`、`/billing` 页）。这意味着即使 repair 触发了，它生成的候选仍然偏通用替代 URL（`/about`、`/help`、`/download`），不是字段特定的深层细页。

- **不会改动 Tavily 主执行链路。** Tavily 仍是每个 scope 一条 primary + 最多一条 expansion 模板拼接，不是多 query 互补检索。一次只能拿到 Tavily 自己排序的有限结果，不能从多个检索视角交叉覆盖同一个字段目标。

- **不会引入 `DimensionEvidencePlan`。** 没有字段级采集预算（最少尝试路径数、最少独立证据数、每条路径的 sourceTypes 和 queryIntents 展开），采集成功标准仍是"某个 sourceType 节点采到页面"，不是"字段证据计划达到最低路径覆盖"。

### 4.4 05 交付后仍然缺失的能力清单

这四块是 spec 里优先级标为"最高"的步骤 1-5，也是方法论风险文档问题三中记录的"被反复后移"的能力：

| 缺失能力 | 对应 spec 步骤 | 后移轨迹 | 当前状态 |
|----------|---------------|----------|----------|
| `FieldEvidenceQueryPlanner` | 步骤 4，优先级最高 | 03 → 04 → 05 → 无 | 类不存在 |
| `DimensionEvidencePlan` | 步骤 3，优先级最高 | 03 → 04 → 05 → 无 | 类不存在 |
| Tavily 多 query 互补执行 | — | 未进入任何阶段 | 主链路仍是 1+1 模板 |
| 覆盖闭环再入逻辑 | — | 未进入任何阶段 | repair plan 只存 metadata |

05 的回归测试命令里写了 `FieldEvidenceQueryPlannerTest`，但 05 的 Task 列表里没有实现 `FieldEvidenceQueryPlanner` 的步骤。这说明它在"验收想象层"已经被当作存在，但在"执行交付层"仍然缺失。

### 4.5 工程证据

- 搜索层 recovery 门控：`SearchExecutionCoordinator.shouldTriggerPublicEvidenceRecovery`（[SearchExecutionCoordinator.java:1366-1368](backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java#L1366-L1368)）—— `hasVerifiedCandidate → return false`
- 采集层 recovery 路径（05 新增）：`CollectorAgent` 中 `publicEvidenceRecoveryService.toRecoveryContext(...)` 调用，基于 `EvidenceQualityVerdict.isRepairRequired()` 触发，不经过搜索层门控
- live trace 完整记录：`docs/superpowers/ExtractionStructured/progress/live-bilibili-public-evidence-recovery-20260629-174119-rerun/`
- live 请求配置：`create-request.json` 中 `reportTemplate=标准版`，`competitorUrls=["https://app.bilibili.com"]`
- 05 未实现类：`EvidenceRepairPlan`、`EvidenceRepairState`、`FieldAnswerSynthesizer`、`FieldAnswerConclusion` 在 `src/main/java` 中不存在
- 05 计划存在：`docs/Tavily/plan/2026-06-28-05-task66-repair-regression-plan.md`

---

## 5. 注意事项：后续写计划时必须遵守的红线

以下条目不是建议，而是后续 Tavily / 搜索采集 / field-first 计划编写时必须显式自检的注意事项。目的就是避免再次出现“症状修完了，但根因没碰”的情况。

### 4.1 红线一：测试或 PoC 已证明“更优策略存在”时，计划不能只修失败症状

如果 `docs/Tavily/` 下的测试、PoC、JSON 样例已经证明：

- 多 query 更有效
- 更明确的自然语言 query 更有效
- 更窄的 include_domains 更有效
- 某类细页更容易命中目标字段

那么后续计划必须显式回答两个问题：

1. 本计划是否把这些已知更优策略纳入交付范围？
2. 如果不纳入，为什么不纳入？是依赖不足、风险过高，还是明确拆到下一份已命名、已排期、可验收的计划？

不能继续出现：

- 测试已经证明“可以更好”
- 计划却只修“不要更差”

### 4.2 红线二：一旦 spec 把某能力认定为根因级方案，后续计划不能无限后移

对根因级能力，必须满足下面至少一条：

- 当期计划直接实现
- 下一期计划明确承接，且写清楚验收标准、调用链入口、测试文件和依赖关系
- 如果决定暂缓，必须记录“暂缓原因 + 不实现它的代价 + 何时重新评审”

不能出现下面这种模式：

- `03` 推迟到 `04`
- `04` 没做
- `05` 没做
- 回归测试和 roadmap 却继续把它当成默认存在的能力

这种写法会制造虚假的完成感。

### 4.3 红线三：新增复杂度必须配对检查“核心能力是否真的前进”

每当计划新增：

- gate
- audit
- recovery
- repair state
- sanitizer
- fallback
- trace

都必须同步检查一个问题：

> 本阶段除了让系统更安全、更可解释之外，是否也让主成功路径更容易成功了？

如果答案是否定的，就必须在计划里明确写出：

- 这是“防失败能力”建设，不是“提成功率能力”建设
- 本阶段不会改善 query 质量 / 命中质量 / 来源多样性
- 哪个后续阶段负责补这块

否则计划标题很容易写成“搜索采集增强”，实际做的却只是“失败治理增强”。

### 4.4 红线四：计划必须区分“配置支持”和“执行支持”

后续所有涉及 query、intent、evidence path 的计划，必须明确区分三层：

1. 配置层是否支持
2. 运行时对象是否携带
3. 主执行链是否真的消费并执行

例如：

- 有 `searchQueries` 字段，不等于 Tavily 真会逐条执行
- 有 `queryIntents` 元数据，不等于 Collector 真按字段路径驱动 query
- 有 `evidencePathKey`，不等于 repair 真已经 field-first

计划文档里如果不把这三层拆开，后续很容易把“已经能存这个信息”误写成“已经具备这个能力”。

### 4.5 红线五：每份搜索相关计划都要回答“为什么会搜到这些东西”

凡是涉及搜索采集、补源、Tavily、direct discovery、target selection 的计划，在背景部分必须明确回答：

- 当前错误结果是怎么来的？
- 是 query 质量问题？
- 是 include_domains 约束问题？
- 是 source family 错配问题？
- 是 candidate ranking 问题？
- 是 selection 阶段没把细页选上？

如果计划背景只写“上次出现了验证码页/中介页/DB 溢出”，却没有追溯“为什么会先搜到这些页”，那它大概率还是在修症状。

---

## 6. 推荐后续行动

### 6.1 下一步：先执行 05，再启动 06

基于本文四个问题和 05 能力边界的分析，建议顺序为：

1. **先完成 05 计划**（`docs/Tavily/plan/2026-06-28-05-task66-repair-regression-plan.md`）。05 里的 `EvidenceRepairPlan` 状态模型、`CollectorAgent` 采集后 recovery 路径、`FieldAnswerSynthesizer` 是 06 会用到的上游基础设施。05 不完成，06 的 repair 闭环没有底座。

2. **再启动 06**。06 必须是一份专门的上游方案，名字直接体现它不是外围治理，而是核心能力升级，例如：

   - `task66-field-evidence-query-planner-plan`
   - `tavily-search-quality-upgrade-plan`
   - `field-first-query-execution-plan`

### 6.2 06 必须覆盖的四块能力

06 至少应满足以下交付标准：

**1. `FieldEvidenceQueryPlanner`（字段 → Tavily query 翻译层）**

输入是 `fieldName / evidencePathKey / queryIntent / sourceTypes / competitorName / locale`，不是单独的 `sourceType`。每个字段路径展开多条互补 query，而不是一条 primary + 一条 expansion 模板拼接。

以 bilibili 下载中心 live 为例，当 `pricing` 字段的 `DOCS_BILLING_OR_LIMITS` 路径被触发时，应同时生成文档计费、API 限制、协议条款三组独立 query，而不是在一条 query 里塞所有关键词。

验收标准：
- 同一个字段路径产出 ≥2 条语义互补的 Tavily query
- 每条 query 记录 `fieldName / evidencePathKey / queryIntent / sourceType / query / reason`
- 不会因为 `sourceType=OFFICIAL` 就不搜 docs/pricing 来源

**2. `DimensionEvidencePlan`（字段级采集规划与预算分配）**

采集成功标准从 “sourceType 节点采到页面” 切换为 “字段证据计划达到最低路径覆盖”。

每个 `REQUIRED` 字段至少包含：
- `evidencePaths[]`：每条路径的 `pathKey / sourceTypes / queryIntents / expectedSignals / required`
- `minimumAttemptedPaths`：最少必须尝试的路径数（未达到时判为 `EVIDENCE_PATH_COVERAGE_NOT_MET`）
- `minDistinctEvidenceCount`：最少独立证据数（避免多个 URL 指向同质入口内容）
- 每条路径最多验证 4 个候选，字段路径预算优先于 sourceType 预算

**3. Tavily 主链路多 query 真实执行**

当前 `TavilyFastLaneProvider` 每个 scope 只跑一条 primary + 最多一条 expansion。06 需要让主执行链路把 `FieldEvidenceQueryPlanner` 产出的多条 query 展开为多轮 Tavily 搜索，且结果按来源多样性交叉覆盖。

验收标准：
- 配置层有多条 query → 运行时对象携带 → 主执行链真实逐条执行（三层全部打通）
- 多条 query 的结果不是简单拼接，而是按域名/来源家族去重并保留多样性
- 执行审计记录每条 query 的命中数、被选中数和未选中原因

**4. 覆盖闭环的再入逻辑**

`collect → qualityGate → detect gap → planFieldCollection → re-collect → verify → re-evaluate → sufficient / abandon`

05 的 `CollectorAgent` repair plan 写入 metadata 是正确的第一步，但闭环缺”再入采集”步骤。06 需要让搜索层或编排层能读取 `REPAIR_QUERY_PROPOSED` 状态的 repair plan，并把 repair 候选重新送入验证和采集管道。

验收标准：
- 当 `FieldEvidenceCoverage` 显示某字段未达到 `minimumAttemptedPaths` 时，系统自动进入第二轮采集
- 第二轮采集使用 `FieldEvidenceQueryPlanner` 为该字段未完成路径生成的专用 query
- 最多 N 轮后终止（默认可设 2 轮），终止时必须输出 `attemptedPaths` 和失败原因
- 如果所有路径都试过仍无公开证据，输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`，不继续循环

### 6.3 验收时必须区分三层

后续所有涉及 query、intent、evidence path 的计划，必须明确区分：

1. 配置层是否支持
2. 运行时对象是否携带
3. 主执行链是否真的消费并执行

例如：有 `searchQueries` 字段 ≠ Tavily 真会逐条执行；有 `queryIntents` 元数据 ≠ Collector 真按字段路径驱动 query；有 `evidencePathKey` ≠ repair 真已经 field-first。

### 6.4 不做什么

- 不在 06 中重建 `CoverageContract`、`EvidenceQualityGate`、`PublicEvidenceRecoveryService`（01-05 已完成或 05 将完成）
- 不要求 06 一次性覆盖全部字段路径类型（先以 `coreFeatures` 和 `pricing` 为最小可行字段建立模型，再扩展）
- 不在 06 中做跨字段 evidence graph（`CrossFieldEvidenceLinker / EvidenceGraphBuilder` 保留为后续 07+ 迭代）
- 不要求规则分类器理解所有非标准定价表达（漏判通过字段证据路径和 repair 闭环处理，误判必须继续被防噪门槛压住）

只有 06 这类计划落地，task66 相关问题才算开始真正触及上游根因，而不只是继续增强失败后的治理链路。

