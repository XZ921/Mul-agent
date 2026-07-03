# 采集路由主从反转(plan 10 档 A + 档 B)+ plan 11 quota 校准 — 实施计划

> 2026-07-02。编号续 11。执行序:08(进得来)→09(不丢)→11(执行自限)→**10(主从反转)**。
> 本计划把 plan 10 的档 A(解除壳页短路)+ 档 B(主从反转)合并落地,并在 B 之后立即校准 plan 11 的 field query 预算 quota。

## Context(为什么做这个改动)

**终极目标**(`docs/specs/2026-06-30-two-axis-closure-direction.md`):用正常竞品端到端跑出一份带 sourceUrls 的成功报告。阻断点 0(最上游)= 采集丰富度——搜到的东西就是地基,garbage in garbage out。

**task79 实证**(数据库核验,抖音开放平台 vs B站开放平台):任务 STOPPED,`quality_check` score=34,BLOCKER=结构字段(定价策略/短板与风险)缺证据。根因是证据太少:`evidence_source` 表只入库 3 行、有效独立源仅 2 个:
- 抖音 = `developer.open-douyin.com/` 首页壳页,仅 433 字节(PUBLIC_EVIDENCE_RECOVERY 兜底)
- B站 = 同一 URL 重复入库两行(content_md5 相同)
- field query:53 planned → executable=1 → skipped=50(全 SKIPPED_OVER_BUDGET),唯一执行那条 resultCount=0

**plan 10 §0 实证**:同样的第三方 query 直打 Tavily,5 条全有正文(6000~30000 字);family 直采拼 `/docs /help` 顶层壳路径只落 433 字壳页。结论:连官方资料搜索都比直采采得好,"只能搜到壳"是**系统短路**,不是数据缺失。

**用户决策**:档 B(主从反转)是必须的,不是"先档 A 验证再看要不要档 B"——因为档 A 只是症状修复(保留官网决定者地位,壳页时才放行搜索),档 B 才真正落地文档 §50"官网是给 Tavily 更好参考、不是决定,就像 PoC 那样",收敛两套打架的设计哲学。执行顺序:A+B 一起做 → B 之后立即校准 plan 11 quota → 沿用抖音 vs B站重跑验证。

**关键架构事实**(已读码确认):official family **没有** `tool-provider-keys` 映射(仅 news/github 有),所以 official 场景不走 RoutingSearchSourceProvider 的 provider-role 机制,而走 `directDiscoveryPlanner` + `shouldSkipSupplementForDirectDiscovery`。对 task79(开放平台=official)真正卡搜索的短路点是 **A1(coordinator)**,A2(provider router)对 official 当前不生效但要一并修正以防 news/github 场景复发。

---

## 设计边界(继承 plan 10)

- 只做两件事:解除壳页短路(档 A)+ 主从反转(档 B)。**不重建** CoverageContract / EvidenceQualityGate / planner / ranker / scorer。
- 官方是排序权重不是准入门槛(证据准入原则:官方是权重非门槛)。反转后 direct-path 仍作高权重 seed。
- 不追求每字段必命中。quota 校准目标是"别再空转",不是"发全量"。
- TDD:每档先写失败单测 → 实现转绿 → 定向回归 → 端到端复跑。

### 反症状修复的两条铁律(本计划自我约束)

> 这是回应"要避免计划方法偏症状修复、根因能力被反复后移"。plan 12 自审时发现自己有两处曾踩在症状线上,已按下列铁律改正,写在此处防止实现时回退。

1. **档 A 与档 B 是同一件事(反转),不是"A 打补丁 + B 改配置"两张皮。**
   `shouldSkipSupplementForDirectDiscovery`(官方直采够了就短路搜索)这个方法的语义**本身就是 plan 10 要推翻的"设计二(官网是决定者)"**。
   给它"加一个可用正文闸门让它别误判壳页"= 在本该退位的短路特权上打补丁——config 反转了、代码里的短路特权还留着靠闸门挡,就是"接缝没接上"。
   **根因改法:档 B 到位后,direct-discovery 短路特权应被削去/降级为排序信号,而不是加闸门继续当门禁。** 见档 A1(改为削权版)。

2. **quota 不用"预估单条耗时反推能跑几条"的魔数闸门。**
   plan 11 §0 已痛批:"魔数按今天量级拍,架构反转后量级变大、魔数立即失效"。把 `6000` 改成 `2000` 只是换个魔数,B 反转放大 query 量后下次还失效——根因能力又被后移一轮。
   **根因改法:数量上按 priority 放行足够多高价值 query,实际能跑多少交给 plan 11 档 B 已有的循环内 deadline 时间熔断决定。** 见 quota 校准(改为去魔数版)。

---

## 档 A — 解除壳页短路

问题本质:两个短路点都"只数个数、不看正文质量",壳页只要能打开 + sourceType 对就计入。

### A0. 抽公共候选级可用性判定(先做,A1/A2 复用)

**张力**:`ContentUsabilityScorer.score` 吃 `CollectedPageView`(需 bodyText),但 A1/A2 短路发生在候选阶段,手里只有 `SourceCandidate`(无 fullContent 字段)。强行抓正文会把秒级 field query 拖成分钟级验证。

**方案**:在 `search/CandidateOwnershipPolicy.java` 新增候选级两层方法,只吃 `SourceCandidate` 已有轻量信号:

```java
public boolean hasAnyContentSignal(SourceCandidate candidate)
public boolean hasSatisfyingContentSignal(SourceCandidate candidate)
```

两层判定必须分开,避免把"不是极薄壳"误当成"足以让搜索短路":
- `hasAnyContentSignal` 只回答"这不是完全无正文信号的空壳"。可接受 `prefetchedRawContentLength >= MIN_USEFUL_BODY_LENGTH(120)`、`contentCompleteness=FULL_ENOUGH` 或 `fastLaneUsable=true`。
- `hasSatisfyingContentSignal` 才能用于 A1/A2 的短路满足判定。只接受 `fastLaneUsable=true`、`contentCompleteness=FULL_ENOUGH`,或更高长度阈值(建议 `prefetchedRawContentLength >= 500`,与 Tavily official doc 中等可用门槛靠齐),不能用 120 直接满足。
- `contentCompleteness=THIN`、命中 `isUtilityGatePage(candidate, null)`、无任何正文信号 → 两层都判 false。

具体口径(全部基于已存在字段,无需抓正文):
- `Boolean.TRUE.equals(getFastLaneUsable())` → 可用
- `"FULL_ENOUGH".equalsIgnoreCase(getContentCompleteness())` → 可用;`"THIN"` → 不可用
- `getPrefetchedRawContentLength() != null && >= MIN_USEFUL_BODY_LENGTH(120)` → 仅表示有正文信号
- `getPrefetchedRawContentLength() != null && >= SATISFYING_BODY_LENGTH(500)` → 可用于短路满足
- 命中已有 `isUtilityGatePage(candidate, null)`(登录/验证码壳) → 不可用
- 兜底:无任何正文信号 → 视为壳/未知,不计入满足

**口径统一**:把长度阈值抽成 `ContentUsabilityScorer.MIN_USEFUL_BODY_LENGTH = 120` 与 `ContentUsabilityScorer.SATISFYING_BODY_LENGTH = 500` 公开常量,供 CandidateOwnershipPolicy 引用,消除口径漂移。EvidenceQualityGate 的 nav>=4+linkRatio 是页面级(吃真实正文),职责不同,保持不动。

### A1. 削去 direct-discovery 短路特权(`SearchExecutionCoordinator.java:1542-1563` `shouldSkipSupplementForDirectDiscovery`)

**定性(反症状铁律 1)**:这个方法"官方直采够数就跳过 public search 补源"的语义,是 plan 10 要推翻的"设计二(官网是决定者)"。**不是给它加可用性闸门,而是随档 B 削去它的短路特权。**

改法(与档 B 同批落地,二者是一件事):
- **首选(根因)**:档 B 反转后,official 走 provider-role 主搜索路径,`shouldSkipSupplementForDirectDiscovery` 对 official 场景**不再主导补源决策**——让它退化为"官方直采候选进池子参与排序",不再拥有阻断搜索的权力。即在 `shouldSupplement`(:1506-1530)调用链里,official 主搜索模式下不再让这个短路点提前 return false。
- **过渡兜底(若一步移除风险大)**:短路条件从"verified 计数达标"收紧为"**可满足短路的正文 verified 达标**"(用 A0 的 `hasSatisfyingContentSignal` 过滤得 `satisfyingVerifiedCount`,仅 `>= minVerifiedCount` 才允许短路)。但这只是过渡——**最终目标是官方主搜索路径下该短路点失去主导权**,不能停在"加了闸门就算完",否则就是被批判的"接缝没接上"。
- 保留该方法给"非 official、确有稳定直采源"的 family 作弱信号,不全局删除。

### A2. 改 `RoutingSearchSourceProvider` primarySatisfied(`RoutingSearchSourceProvider.java:135-137`)

- 现状 `primaryCandidateCount += providerCandidates.size()`——只数个数。
- 改法:计数时按 `hasSatisfyingContentSignal` 过滤后再累加。给 `RoutingSearchSourceProvider` 注入 `CandidateOwnershipPolicy`(新增带 policy 的构造器,保留现有测试构造器)。
- 效果:PRIMARY 全壳页时 `primarySatisfied` 不成立,不在 113-119 行跳过 AUXILIARY(public search)。

---

## 档 B — 主从反转(official family:搜索为主、直采为辅)

目标:PUBLIC_SEARCH/Tavily 成为 official 字段采集主入口,direct-path 降为搜索候选池的高权重 seed;官网可信度保留为排序权重,不再拥有"短路整个搜索"的特权。

### B1. `application.yml`(两处 profile:默认 ~263-297、prod ~600+)official family

- `primary-tools` / `auxiliary-tools` 重排:使 `PUBLIC_SEARCH` 成为主取证手段,`WEB_SCRAPER`/`JINA_READER` 降为"对搜索已发现的官网 URL 做正文补抓"。
- 给 official 补 `tool-provider-keys` 映射(参考 news:`PUBLIC_SEARCH: qianfan` / 或 tavily),让 official 也能走 provider-role 机制,使 A2 对 official 生效。
- `direct-path-templates` 保留但作为"已知高价值 seed URL"喂给搜索/排序加权,不作为主采集入口。
- 评估 `run-auxiliary-when-primary-satisfied`(225/611 行):A1/A2 修好后壳页自然不满足、搜索继续,此项可保持 false(实现上等价);若回归发现仍短路则放开。

### B2. 执行顺序保证"搜索先行、直采补抓"

- `TavilyBootstrapPlanner`(已存在)对弱入口候选先跑 Tavily,task79 抖音根域已触发 bootstrap——确认反转后 bootstrap 产出的第三方候选**不被后续短路/选源丢弃**(依赖档 A 解除短路)。
- 确认 CollectorAgent toolchain 装配按新 primary-tools 顺序执行(搜索先行),而非"先 direct-path 直采"。

### B3. 官网仍保留排序权重

- 官方候选在 `SourceCandidateRanker` 的可信度加权不动(官方是权重非门槛),只取消其"短路搜索"特权。

---

## quota 校准(plan 11,B 之后立即做 —— 去魔数版)

- **病灶(反症状铁律 2)**:`SearchPolicyResolver.resolveExecutableFieldEvidenceQueryQuota`(:184-189)= `baseSearchTimeoutMillis / FIELD_QUERY_PER_QUERY_TIMEOUT_MILLIS(6000)`。task79 里 base≈6~12s → quota=1。
  真病根**不是"6s 这个数太大"**,而是"**用预估单条耗时反推能跑几条**当唯一硬闸门"这个机制错了——它依赖魔数,B 反转放大 query 量后必然再失效。把 6000 换成 2000 只是换个魔数,是被 plan 11 §0 点名要避免的后移。
- **根因改法(去魔数)**:把"数量截断"和"时间自限"解耦成两套机制,各司其职:
  1. **数量层**:quota 不再由"预估耗时反推"决定。改为**按 `FieldEvidenceQuery.priority` 排序,放行足够多的高优先级 query**(如放行全部 required 字段的 top-priority 一条 + 第三方路径 top-N),不用单条耗时做除法。
  2. **时间层**:实际能跑多少,交给 **plan 11 档 B 已落地的循环内 deadline 时间熔断**(`fieldEvidenceExecutionDeadlineEpochMillis` + 每条起跑前剩余预算检查)决定。跑不完的自然标 `SKIPPED_BUDGET_EXHAUSTED`,可观测。
  - 效果:数量上不再被"6s"魔数压到 1;时间上仍有 deadline 兜底不会吊死。**无论 B 反转后量级怎么变,这套都不失效**(数量看 priority、时间看 deadline,都不依赖"单条预估耗时"魔数)。
- **保留约束**:时间层预算仍基于**放大前**预算(不能用 `ensureMinimumTimeoutForFieldEvidenceQueries` 放大值反推)。
- **测试**:`SearchExecutionCoordinatorFieldEvidenceBudgetTest#shouldSendOnlyExecutableQueriesWithinPreInflationBudget` 需改写——从"断言 executable 数 = 预算/6s"改为"断言 executable 按 priority 放行了高价值 query(不再恒为 1)、且实际执行截断由 deadline 熔断而非数量魔数负责"。

---

## 附带小补丁(纳入本次,单独 commit)

- **P1 B站同 URL 重复入库去重**:`CollectorAgent.java:396` save 前,按 canonicalUrl(+ 可选 content md5)在节点内 `seenKeys` 判重,跳过重复。回滚=移除判重块。
- **P2 抖音壳页兜底降级,不要误杀 recovery 候选**:`PublicEvidenceRecoveryService` 生成候选阶段**只做** `CandidateOwnershipPolicy.hasCompetitorDomainOwnershipSignalForCandidate`(:212)+ `isUtilityGatePage` / mediator 过滤。不要在这里调用 A0 的正文可用性方法,因为 recovery 候选刚生成时没有正文信号,直接调用会把未知候选误杀。
  - 正文可用性判断放到选源/落库质量阶段:正式入选或持久化前,若采集结果为 433 字壳页、`hasSatisfyingContentSignal=false`、或 EvidenceQualityGate 标为 `NAVIGATION_SHELL/THIN_CONTENT`,则降权或不计为可满足字段证据。
  - 验收目标:recovery 仍能提出同域候选,但 433 字首页壳页不再作为"有效证据"贡献给字段覆盖或最终报告。

---

## 失败单测设计(红 → 绿)

- **A0**(新增 `CandidateOwnershipPolicyTest` 或扩展):壳页候选(prefetchLen<120、无 fastLaneUsable)→ `hasAnyContentSignal=false` 且 `hasSatisfyingContentSignal=false`;中等正文候选(prefetchLen=150)→ `hasAnyContentSignal=true` 但 `hasSatisfyingContentSignal=false`;富正文候选(FULL_ENOUGH、fastLaneUsable=true 或 prefetchLen>=500)→ 两层均 true;登录壳 → 两层均 false。
- **A1**(扩展 `SearchExecutionCoordinatorFieldEvidenceTest`):official 主搜索路径下,direct-discovery 短路点**不再阻断 public search 补源**(即便直采候选计数达标);过渡兜底期则断言"全是壳页/仅 120~499 字弱正文时 `shouldSkipSupplementForDirectDiscovery=false`,带满足级正文才 true"。核心是验证短路特权被削,而非闸门更严。
- **A2**(扩展 `RoutingSearchSourceProviderTest`):PRIMARY 返回壳页候选时 `primarySatisfied` 不成立、AUXILIARY 仍被调用。
- **B**(config 契约 / `SearchProviderRoleContractTest`):official family 补 tool-provider-keys 后,tavily/PUBLIC_SEARCH 对 official 解析为主取证角色;direct-path 仍作 seed。
- **quota**(改写 `SearchExecutionCoordinatorFieldEvidenceBudgetTest`):断言 executable 按 priority 放行高价值 query(不再恒为 1、不依赖"预算/6s"魔数),实际执行截断由 deadline 熔断负责。
- **P2**(新增/扩展 `PublicEvidenceRecoveryServiceTest` + `CollectionTargetSelectorTest` 或 collector 持久化测试):recovery 生成阶段保留同域候选,但采集后被判为薄壳的页面不贡献字段覆盖/不作为满足级正式证据。

---

## 执行顺序

1. A0 失败单测 → 实现 `hasAnyContentSignal` / `hasSatisfyingContentSignal` + 抽常量 → 绿。
2. **A1+B 合并**失败单测 → 削去 direct-discovery 短路特权 + config 反转 official 主从(二者是一件事,同批落地) → 绿。过渡兜底可先用可用性闸门,但同一步内推进到"官方主搜索路径下短路点失去主导权"。
3. A2 失败单测 → 改 `RoutingSearchSourceProvider` + 注入 policy,使用 `hasSatisfyingContentSignal` 计 primary satisfied → 绿。
4. B 契约单测(`SearchProviderRoleContractTest` official 角色解析) → 绿。
5. quota 去魔数校准(数量按 priority 放行 + 实际截断交 deadline 熔断) + 改写 budget 单测 → 绿。
6. P1 / P2 小补丁 + 单测 → 绿。P2 只在选源/落库质量阶段降级薄壳,不在 recovery 候选生成阶段用正文信号硬拦。
7. **定向回归**(高危清单):`RoutingSearchSourceProviderTest`、`RoutingSearchSourceProviderPrimaryAuxiliaryTest`、`SearchExecutionCoordinatorTest#shouldSkipPublicSearchSupplementWhenOfficialDirectCandidatesAlreadyVerified`、`SearchExecutionCoordinatorFieldEvidenceTest`(两条 direct discovery)、`SearchPolicyResolverTest`、`SearchProviderRoleContractTest`、`SourceFamilyDirectDiscoveryPlannerTest`、`SourceCandidateRankerTest`、`CollectionTargetSelectorTest`、`SearchExecutionCoordinatorFieldEvidenceBudgetTest`。
8. **链路回归**:`SearchAndCollectionDeepDiscoveryIntegrationTest`、`SearchAndCollectionGoldenMasterTest`。
9. **端到端复跑 —— 两条独立验收,不可混为一谈**(详见下方「验证竞品选择」):
   - **9a 档 B 的 KPI = 采集丰富度**(用抖音 vs B站 task79 同款,只为 before/after 对比):evidence_source 独立源数 > 2、出现 open.douyin/open.bilibili 以外第三方域名、至少 2 条正文 >2000 字、field query executed >1、无重复 URL。**这条只看采集,不看报告过不过。**
   - **9b 第一份成功报告 = 换正常竞品**(有真实定价/产品页,非开放平台;推荐名单见下方「验证竞品选择」):报告不因结构字段缺证据 BLOCKER、quality 通过。**开放平台的模板错配会永远卡结构字段,不能用它判断"成功报告",否则会把模板问题误判成档 B 没生效。**

---

## 主旨对齐与验证竞品选择(防走偏)

**主旨(不可偏离)**:Tavily 目录所有计划的终点是——推翻"官网为主"、改用 Tavily 多渠道搜索提升证据丰富度,最终像 PoC 那样跑出**第一份成功报告**。档 B 主从反转就是这件事本身,方向没偏。

**但"跑出成功报告"到不了终点,还差一个模板分型的坎——而验证用的抖音/B站开放平台正好踩在这个坎上:**

- 收口方向文档 §60/§105 明确:开放平台被强套标准版模板要 pricing/weaknesses,官方天然没有 → 结构字段 BLOCKER。**这在"开局之前",LLM 大脑和采集都救不了它**,需靠模板分型解决(不属于本计划)。
- task79 的 BLOCKER 恰恰是"定价策略/短板与风险缺证据"= **模板错配的症状**。
- **陷阱**:即使档 B 完美搜回多渠道证据,开放平台没有"定价",标准版模板仍要该字段,报告仍 BLOCKER。此时若重跑抖音 vs B站看到还 STOPPED,极易**误判成"档 B 没生效"**→ 回去改档 B → 在与采集无关的原因上打转 = 真正的走偏。

**因此把验证拆成两条正交标准(见执行顺序 9a/9b):**
1. **档 B 成功与否 只看采集丰富度**(独立源数、第三方域名、正文长度像不像 PoC)——用抖音 vs B站做 before/after 对比即可,与报告过不过无关。
2. **第一份成功报告 必须换正常竞品**(有真实定价/产品页,按 §105"绝不用模板错配案例")——否则永远卡在开放平台的模板坎上,拿不到那份报告还会怪罪档 B。

**次要项纪律(防根因后移)**:官网为主反转是本轮唯一根因。quota 空转、URL 去重、壳页降级是**档 B 跑通后的补丁**,只在直接挡住采集丰富度时才动,不喧宾夺主;模板分型是**另一条独立主线**,不塞进本计划。

### 什么是"正常竞品"(9b 用,可直接拿来跑)

**判据(三条都要满足,才规避得开模板错配)**:
1. **有真实公开定价页**(pricing/plans,标准版模板要 pricing,不能天然为空)。
2. **有独立产品/功能页**(供 summary/coreFeatures 字段取证)。
3. **有第三方评测/对比**(供 weaknesses/短板字段取证,弥补官方不自曝)。
> 反例:开放平台、开发者 API 平台、内部中台——没有面向终端用户的定价,天然缺 pricing → 必踩模板坎,禁用于 9b。

**推荐名单(按稳妥度排序,已用实测/常识判断证据可得性)**:

| 优先级 | 竞品对 | 为什么选它 |
|---|---|---|
| **首选** | **Notion vs Obsidian**(或 Notion vs Flomo) | 定价页公开清晰,第三方评测对比海量。已实测 Notion 定价 query 5/5 全过闸门(Forbes/Reddit/官网),证据密度最高,最可能一次跑出成功报告。 |
| 备选1 | **Figma vs Sketch** | SaaS 设计工具,定价/功能/评测三类证据齐全,中英文材料都多。 |
| 备选2 | **石墨文档 vs 腾讯文档** | 纯中文场景,贴合系统中文 RAG 与报告语言;定价与功能页公开,第三方测评多。 |

**首推 Notion vs Obsidian**:定价证据实测密度最高(降低"证据不足"这个与档 B 无关的干扰),且中英文第三方评测都丰富,是拿"第一份成功报告"最干净的靶子。若要纯中文闭环,用石墨 vs 腾讯文档。

---

## 验证方式

- 单测/回归:`mvn -pl backend -DskipITs -Dtest=<类名> test`;链路回归全绿。
- 端到端:按 9a/9b 两条独立标准。9a 用抖音 vs B站查 `evidence_source`/`task_node` 快照对比 task79 采集丰富度 before/after;9b 换正常竞品验证报告能否过。用 docker exec postgres psql 查库。
- 契约测试翻转(如 `shouldSkipPublicSearchSupplement...`)是**预期的契约细化**——测试数据补"可用正文才跳过",用 A0 单测独立锁住可用性边界,防止误改成"永不短路"。

---

## 风险与回滚

- **契约测试翻转是预期的**,但风险是误改成"永远不跳过/无限补源"。缓解:A0 单测独立锁两层边界;`MIN_USEFUL_BODY_LENGTH=120` 与 `SATISFYING_BODY_LENGTH=500` 都抽常量可调。
- **A1/A2 误杀合法短正文页** → 永不短路无限补源。缓解:短路满足只影响是否跳过搜索,不删除候选;A0/A1/A2 分三个 commit,可单独 revert。
- **P2 误杀 recovery 候选**。缓解:recovery 生成阶段不调用正文可用性方法,只做 domain/utility gate;薄壳降级后移到选源/落库质量阶段。
- **quota 放太宽 collect 又变慢**。缓解:min quota 设小(3)+ 依赖 plan 11 循环内 deadline 熔断;per-query 常量可配。
- **档 B 影响所有 family**(plan 10 自评风险中)。缓解:B 单独 commit;链路回归 + 端到端 9a 采集丰富度作为总闸;若 9a 采集仍差,按层定位(evidence 行数=A 没解;搜索没先行=B;query 没发=quota)再按 commit 回退。**注意:9b 报告若仍 BLOCKER 但 9a 采集已丰富,那是模板错配(另一条主线),不是档 B 没生效,不要回退档 B。**
- 每步独立 commit,便于分层回滚。
