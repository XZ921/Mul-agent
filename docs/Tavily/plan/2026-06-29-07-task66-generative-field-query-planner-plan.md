# Task66-07 生成式字段 Query Planner：Tavily 天花板收口 Implementation Plan

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。本计划在 `master` 上直接修改，用户自行提交，执行过程不要创建提交。

**目标：** 把 06 留下的 `FieldEvidenceQueryPlanner` if-field 硬编码占位，升级为**生成式**字段 query planner：系统能为任意 required 字段，从 `queryIntents + expectedSignals + sourceTypes + dimension` 组合生成多条语义互补 query，自动决策 `includeDomains`，并把"正文可用性"作为独立评分维度，从而在真实流水线里复现 `docs/Tavily/tavily-search-test-summary.md` PoC 证明的搜索质量天花板。

**定位（必读）：** 本计划是 Tavily 目录这条线的**收口阶段**。Tavily 目录从 01 到 07 一直追求同一个目标——让系统在真实流水线里跑出 PoC 证明的天花板。天花板 = PoC 里人工做的三件事（写互补 query、挑 include_domains、按正文可用性筛素材）被系统自动完成。07 达成即该目标达成。**采集链路到此站上天花板、停止深修，转入 4.x runtime contract（详见 `docs/specs/project-evolution-roadmap.md`）。**

**架构：** 不重建 06 的执行骨架。06 已交付「字段 query 逐条执行 + 覆盖闭环再入 + verified 短路收紧」。07 只替换 planner 内部实现，对外接口 `FieldEvidenceQuery` 与 `plan(...)` 签名保持不变，使 06 的 `SearchExecutionCoordinator / TavilyFastLaneProvider / CollectorAgent` 无需改动即可消费更优 query。新增 `FieldQueryComposition`（组合生成规则）、`IncludeDomainPlanner`（域名决策）、`ContentUsabilityScorer`（正文可用性评分，与现有来源可信度分解耦）。

**Tech Stack:** Java 17、Spring Boot、Jackson、Lombok、JUnit 5、Mockito、AssertJ、现有 CoverageContract、现有 Tavily Fast Lane、现有 06 字段证据闭环。

---

## 0. 07 的边界与"天花板"验收口径

### 0.1 为什么 07 存在

- 06 的 `FieldEvidenceQueryPlanner.buildQueries(...)` 是 if-field 硬编码：仅 `coreFeatures / pricing` 有真实互补 query，其余字段落兜底单 query（见 06 计划 Task 1 Step 4 的 MVP 边界声明）。
- risks 文档问题二/三点名的最根本根因是"query 没能表达真实字段目标""固定 family 级关键词拼接"。06 动了执行骨架，但 query 生成仍是模板搬家。
- PoC（`tavily-search-test-summary.md` 测试一/测试二）已证明：3 条互补 query + 精准 include_domains + 按正文可用性筛选 = 高质量素材（14 唯一 URL、9 强、10 域名）。07 的任务就是把这套人工策略变成系统能力。

### 0.2 天花板的可验收定义

07 承诺的"跑出天花板"是工程可验收的，不是"真实公网必然命中"：

- **任意 required 字段**（不限 `coreFeatures / pricing`）都能产 ≥2 条语义互补 query，且不掉进单条兜底。
- 互补性可度量：同一字段路径的多条 query 在**措辞/检索视角**上有差异（不是同一句加关键词），用 token 重合度断言。
- `includeDomains` 由 planner 按 `sourceTypes + competitor domain + dimension` 自动决策，而非只在 query 含 `site:` 时被动回填。
- 正文可用性评分与来源可信度评分**解耦**：导航壳/登录壳即使来自官方高信任域名，可用性分也必须被压低（对齐 spec 1.1）。
- if-field 分支被删除；新增任意字段不需要改 planner 代码即可获得互补 query。

### 0.3 不在 07 中做（天花板之上，留给后续）

- **不做跨字段 evidence graph**（`CrossFieldEvidenceLinker / EvidenceGraphBuilder`）——PoC 从未要求，属天花板之上，留给 4.x 之后。
- **不重建** 06 的执行骨架、CoverageContract、EvidenceQualityGate。
- **不承诺**真实公网每个字段都命中；公开资料不存在时仍输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`，与 07 planner 是否正确无关。
- **不新增** gate / audit / recovery / repair 外围层——07 是提成功率的核心改造，不是失败治理（对齐 risks 红线三）。

### 0.3.1 证据准入原则（贯穿全字段，07 必须落地）

用户确立的核心原则，07 的 query 生成、域名决策、可用性评分三处都必须遵守：

1. **官方是权重，不是门槛。** **所有字段**（不限 pricing）都不排斥非官方来源（博客、测评、教程、行业报告）。非官方源永远可进入证据，只是可信度**权重低于**官方。官方占比应当重，但不能排他。
2. **官方缺失或单薄时，主动找高质量第三方补充。** 不能因为官方采到一个 verified 候选（哪怕是壳页/内容少）就停止补采。真实的第三方证据优于官方壳页硬凑。
3. **红线是"不编造 + 可追溯 + 人工终审"，不是"必须官方"。** 只要内容真实、来源可追溯、报告明确标注其为第三方来源及可信度等级，就可以使用——最终由人工审查。

落地约束：
- query 生成（Task 2/3）：每个字段除官方视角外，至少生成一条**第三方视角** query（测评 / 教程 / 收费对比 / 行业报告角度），不只官方文档。
- 域名决策（Task 4）：`IncludeDomainPlanner` 对所有字段允许第三方域名，官方域名只作**优先**而非**排他**约束。
- 可用性评分（Task 5）：`ContentUsabilityScorer` 以**正文质量 + 真实性**为主导，来源类型只作**权重调节**，不得因"非官方域名"直接压到不可用。
- **安全边界（不可省）**：每条第三方证据的 `sourceUrls` 必须完整可追溯；落库与报告中必须携带来源层级标记（官方 / 第三方）及可信度权重，否则人工终审无法分辨。
- 技术债标记：现有 `CoverageFieldContract.allowOfficialOnly` 是**写了无人读的死字段**（全代码库无消费方），且其布尔门槛语义与本原则冲突。07 不依赖它；后续应以连续 source weight 取代，详见诊断文档技术债条目。

- **不新增** gate / audit / recovery / repair 外围层之外，本节原则不引入新的拦截逻辑，只调整 query 视角、域名约束与评分权重。

### 0.4 方法论红线自检（对齐 `2026-06-29-task66-planning-methodology-risks.md`）

- **红线一**：PoC 已证明更优策略（多 query + include_domains + 可用性筛选），07 把三者全部纳入交付，不是只修失败症状。
- **红线二**：生成式 planner 是 03→04→05→06 一路占位/后移的根因，07 是它的终点，不再后移。
- **红线三**：07 核心目的是**提成功率**（直接改善 query 质量、命中质量、来源多样性），不是失败治理。
- **红线四（三层）**：配置层 = CoverageContract 字段（已存在）；运行时携带 = `FieldEvidenceQuery`（06 已存在）；主执行链消费 = 06 的 TavilyFastLane（已存在）。07 只换"生成"这一环，三层其余不动。
- **红线五**：07 直接回答"为什么会搜到这些东西"——因为 query 由字段语义组合生成（含官方+第三方视角）、域名按官方优先非排他约束、证据按正文质量主导评分（壳页即使来自官方也被压低）。

---

## 1. 当前 06 后工程事实

- 已存在：`FieldEvidenceQuery / FieldEvidenceQueryPlanner`（06 交付），planner 内部为 if-field 硬编码，仅 `coreFeatures / pricing` 有真实互补 query。
- 已存在：`CoverageEvidencePath`，字段含 `pathKey / sourceTypes / queryIntents / expectedSignals / required / successCriteria / failureStatus`——这些就是生成式 planner 的输入信号源，但 06 的 planner 几乎没用 `expectedSignals`。
- 已存在：`CoverageFieldContract`，含 `field / queryIntents / evidencePaths / targetEvidenceTypes`。
- 已存在：06 的 `TavilyFastLaneProvider` 逐条执行 `FieldEvidenceQuery`、`SearchExecutionCoordinator` 收紧 verified 短路、`CollectorAgent` 覆盖闭环再入——07 不动这些。
- 已存在但需解耦：来源可信度分与正文可用性分仍可能混用（spec 1.1 点名），导航壳/登录壳可能被官方域名或文本长度掩盖。

## 2. 文件结构

- 修改：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlanner.java`（删除 if-field，改组合生成）
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalog.java`（全字段证据路径补第三方 sourceType，解除"第三方绑死 weaknesses"）
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolver.java`（全字段证据路径补第三方 sourceType）
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldQueryComposition.java`（组合生成规则）
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/IncludeDomainPlanner.java`（include_domains 决策）
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/search/ContentUsabilityScorer.java`（正文可用性评分，与来源可信度解耦）
- 修改：消费正文可用性分的评分汇聚点（执行时按实际代码定位，候选：`SourceCandidateRanker` 或 EvidenceQualityGate 评分入口）
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlannerGenerativeTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/IncludeDomainPlannerTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/search/ContentUsabilityScorerTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/integration/Task66GenerativeQueryPlannerSystemTest.java`
- 新建进度记录：`docs/Tavily/progress/2026-06-29-task66-07-generative-query-planner-progress.md`

---

## 执行 Task 列表（开工前全部标记 ⬜ 未进行）

| Task | 内容 | 状态 |
|------|------|------|
| Task 1 | 生成式 planner failing test（任意字段 ≥2 条互补 query） | ⬜ 未进行 |
| Task 2A | 全字段证据路径补第三方 sourceType（解除第三方绑死 weaknesses） | ⬜ 未进行 |
| Task 2 | `FieldQueryComposition` 组合生成规则 | ⬜ 未进行 |
| Task 3 | 重写 `FieldEvidenceQueryPlanner` 删除 if-field | ⬜ 未进行 |
| Task 4 | `IncludeDomainPlanner` 自动域名决策 | ⬜ 未进行 |
| Task 5 | `ContentUsabilityScorer` 可用性分与可信度解耦 | ⬜ 未进行 |
| Task 6 | 系统测试：天花板验收 + 06 回归 | ⬜ 未进行 |
| Task 7 | 审计、进度文档与采集链路封板声明 | ⬜ 未进行 |

---

### Task 1: 生成式 planner failing test

**Files:**
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlannerGenerativeTest.java`

- [ ] **Step 1: 编写 failing test——任意非硬编码字段也能产 ≥2 条互补 query**

关键断言（用 06 没有 if-field 分支的字段，如 `positioning`）验证组合生成生效：

```java
@Test
void shouldGenerateComplementaryQueriesForFieldWithoutHardcodedBranch() {
    CoverageFieldContract positioning = CoverageFieldContract.builder()
            .field("positioning")
            .status(CoverageFieldStatus.REQUIRED)
            .evidencePaths(List.of(CoverageEvidencePath.builder()
                    .pathKey("OFFICIAL_POSITIONING")
                    .sourceTypes(List.of("OFFICIAL"))
                    .queryIntents(List.of("BRAND_POSITIONING", "MARKET_SEGMENT"))
                    .expectedSignals(List.of("POSITIONING_BLOCK", "SLOGAN_BLOCK"))
                    .required(true)
                    .build()))
            .minimumAttemptedPaths(1)
            .minDistinctEvidenceCount(2)
            .build();

    List<FieldEvidenceQuery> queries = planner.plan("哔哩哔哩", positioning, List.of("bilibili.com"));

    // 天花板核心断言：未硬编码字段也产 >=2 条，不掉兜底单条
    assertThat(queries).hasSizeGreaterThanOrEqualTo(2);
    // 互补性：query 之间 token 重合度不能过高（不是同句加词）
    assertThat(maxPairwiseTokenOverlap(queries)).isLessThan(0.7);
    // 每条 query 带可解释来源
    assertThat(queries).allSatisfy(q -> assertThat(q.getReason()).isNotBlank());
}
```

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceQueryPlannerGenerativeTest test
```

Expected: FAIL，06 的 if-field planner 对 `positioning` 只产 1 条兜底 query。

---

### Task 2A: 全字段证据路径补第三方 sourceType（解除"第三方绑死 weaknesses"）

**根因：** 现状 `AnalysisDimensionMappingCatalog` 把第三方来源（`PUBLIC_REVIEW_OR_NEWS / REVIEW / NEWS / THIRD_PARTY_REVIEW`）只配给 weaknesses 维度；coreFeatures / positioning / targetUsers / summary / strengths / pricing 的 `sourceTypes` 全是 `OFFICIAL/DOCS`。这与证据准入原则 0.3.1 直接冲突——它让"是否允许第三方"绑死在字段类型上。Task 2 的 planner 即使想为正向字段生成第三方 query，输入契约里也没有第三方 sourceType 作依据。**必须先在契约层全字段解绑第三方，planner 的全字段第三方视角才有根。**

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalog.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolver.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalogTest.java`（已存在，追加用例）

- [ ] **Step 1: failing test——正向字段的证据路径也含第三方 sourceType**

```java
@Test
void everyFieldMappingShouldIncludeThirdPartySourceType() {
    // 用纯正向维度（无任何"风险/劣势"语义），证明第三方准入与字段语义无关
    List<AnalysisDimensionMapping> mappings = catalog.resolve(
            List.of("开放平台", "开发者生态", "产品功能"), List.of("官网"));

    // CAPABILITY_INTRO（coreFeatures/positioning/...）也必须带第三方来源
    AnalysisDimensionMapping capability = mappings.stream()
            .filter(m -> "CAPABILITY_INTRO".equals(m.getDimensionKey()))
            .findFirst().orElseThrow();
    assertThat(capability.getSourceTypes())
            .as("正向字段也应允许第三方来源，官方只是权重")
            .anySatisfy(t -> assertThat(t).isIn("REVIEW", "NEWS", "OPEN_WEB"));
    assertThat(capability.getEvidencePathKeys())
            .anySatisfy(k -> assertThat(k).contains("PUBLIC_REVIEW_OR_NEWS"));
}
```

Expected: FAIL，现状 CAPABILITY_INTRO 只有 `OFFICIAL/DOCS`。

- [ ] **Step 2: 全字段映射追加第三方来源（官方优先，第三方补充）**

在 `AnalysisDimensionMappingCatalog` 的**每一条**维度映射（不止 weaknesses）的 `sourceTypes` 末尾追加第三方类型、`evidencePathKeys` 追加第三方路径。顺序上官方/文档在前（权重高），第三方在后（补充）：

```java
// CAPABILITY_INTRO 示例（其余维度同理）：
.evidencePathKeys(List.of("OFFICIAL_PUBLIC_PROFILE", "DOCS_API_GUIDE", "PUBLIC_REVIEW_OR_NEWS"))
.sourceTypes(List.of("OFFICIAL", "DOCS", "REVIEW", "NEWS"))
.queryIntents(List.of("OFFICIAL_DOCS", "API_DOCS", "SDK_GUIDE", "THIRD_PARTY_REVIEW"))
```

同样在 `CoverageContractResolver` 里手工构造的字段契约（pricing/strengths/coreFeatures 等的 `evidencePath(...)`）中，为每个字段的证据路径补一条第三方 path（如 `PUBLIC_REVIEW_OR_NEWS`，sourceTypes 含 `REVIEW/NEWS`）。

> **原则约束**：这是"全字段加第三方补充路径"，不是"把官方降级"。官方路径仍 required、仍排在前、权重仍最高；第三方路径作为**非排他的补充来源**，可设 `required=false`，命不命中都不阻断，但允许被采纳。

- [ ] **Step 3: 运行映射测试**

```powershell
mvn -pl backend -Dtest=AnalysisDimensionMappingCatalogTest,CoverageContractResolverTest test
```

Expected: PASS。全字段（含正向字段）证据路径均含第三方来源；既有官方路径与字段状态不被破坏。

---

### Task 2: `FieldQueryComposition` 组合生成规则

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldQueryComposition.java`
- Test: 复用 Task 1 测试

- [ ] **Step 1: 定义组合生成的输入与视角**

`FieldQueryComposition` 把一条 `CoverageEvidencePath` 拆成多个**检索视角**，每个视角组合不同的 `queryIntent + expectedSignal + sourceType 词面`，复现 PoC"3 条互补 query 各换视角"的策略：

```java
// 视角来源（组合，不是 if-field）：
// 1. queryIntent 维度：每个 intent 生成一条以该 intent 为主词的 query
// 2. expectedSignal 维度：把期望信号（如 PRICING_BLOCK / FEATURE_BLOCK）翻译为自然语言检索词
// 3. sourceType 维度：OFFICIAL/DOCS/PRICING 决定 query 的来源约束词（官方文档 / 定价页 / 帮助中心）
// 4. dimension 维度：竞品名 + 分析维度名作为锚点
```

- [ ] **Step 2: expectedSignal → 自然语言检索词映射表**

建立 signal 到中文检索词的可扩展映射（配置化，不写死在 if 里）：

```java
private static final Map<String, List<String>> SIGNAL_TERMS = Map.of(
        "PRICING_BLOCK", List.of("定价", "收费", "套餐", "计费"),
        "FEATURE_BLOCK", List.of("功能", "能力", "特性"),
        "DEVELOPER_DOCS_BLOCK", List.of("开发者文档", "API", "SDK", "接入"),
        "POSITIONING_BLOCK", List.of("市场定位", "品牌定位"),
        "LIMITATION_OR_POLICY_BLOCK", List.of("限制", "条款", "协议", "规则")
        // 新增字段只需扩这张表，不改 planner 逻辑
);
```

新增字段类型时只扩映射表，**不增 if 分支**——这是"任意字段可生成"的关键。

- [ ] **Step 3: 组合去重与互补性保证**

同一路径的多视角 query 用 token 集合去重，重合度超阈值的视角合并，保证产出的是互补 query 而非近重复。验收对齐 Task 1 的 `maxPairwiseTokenOverlap < 0.7`。

- [ ] **Step 4: 为每个字段生成第三方视角 query（落地 0.3.1 原则一，全字段无例外）**

除官方视角外，组合生成必须**为每一个字段**额外产出至少一条**第三方视角** query——**不区分字段类型**。这不是"pricing/weaknesses 才配第三方"，而是 summary / positioning / targetUsers / coreFeatures / strengths / pricing / weaknesses **全部字段一视同仁**：官方是权重，第三方是所有字段都享有的补充来源。

第三方视角同样用组合生成（不是 if-field），通用规则：`name + <字段自然语言> + <第三方语料词:评测/实测/对比/教程/用户反馈/解读>`，叠加该字段 `expectedSignals` 翻译出的检索词。示例（仅示意，不得据此写成 if 分支）：

```java
// 通用第三方视角（对任意字段成立）：
//   name + fieldNaturalTerm + "评测 实测 对比"
//   name + fieldNaturalTerm + "教程 解读 用户反馈"
// 字段特例只是语料更贴切，不是"只有它们才有第三方视角"：
//   coreFeatures -> name + "功能 实测 体验评测"
//   positioning  -> name + "市场定位 行业分析 解读"
//   pricing      -> name + "收费 价格 评测 计费教程"
//   weaknesses   -> name + "缺点 吐槽 对比劣势 用户反馈"
```

第三方视角 query 的 `sourceType` 标为 `REVIEW / NEWS / OPEN_WEB`，由 Task 4 的 `IncludeDomainPlanner` 决定不约束域名。验收：

- **任意字段**（用一个无任何"风险/劣势"语义的正向字段如 `coreFeatures` 或 `positioning` 验证）产出的 query 中，至少 1 条是第三方视角（sourceType 为 REVIEW/NEWS/OPEN_WEB，非 site: 官方、非"官方文档"措辞）。
- 官方视角仍占多数（官方是权重，第三方是补充），但任意字段的第三方视角数都不得为 0。
- 测试必须覆盖**正向字段**而非只测 pricing/weaknesses，证明第三方准入与字段语义无关。

第三方视角 query 的 `sourceType` 标为 `REVIEW / NEWS / OPEN_WEB`，由 Task 4 的 `IncludeDomainPlanner` 决定不约束域名。验收：

- `pricing / weaknesses` 等"官方常缺"字段，产出的 query 中至少 1 条是第三方视角（非 site: 官方、非"官方文档"措辞）。
- 官方视角仍占多数（官方是权重，第三方是补充），但第三方视角不得为 0。

---

### Task 3: 重写 `FieldEvidenceQueryPlanner` 删除 if-field

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlanner.java`

- [ ] **Step 1: 用 `FieldQueryComposition` 替换 `buildQueries` 的 if-field 分支**

删除 `if ("COREFEATURES"...)`、`if ("PRICING"...)` 和兜底单条逻辑，改为：

```java
private List<String> buildQueries(String competitorName, String fieldName,
                                  CoverageEvidencePath path, List<String> preferredDomains) {
    return fieldQueryComposition.compose(competitorName, fieldName, path, preferredDomains);
}
```

- [ ] **Step 2: 保证向后兼容**

06 已有的 `FieldEvidenceQueryPlannerTest`（`coreFeatures / pricing`）必须继续通过——组合生成对这两个字段的产出也要满足"≥4 条 / 含 API_DOCS、SDK_GUIDE 等"。若组合产出措辞与 06 硬编码不同，调整的是 06 测试里**对具体字符串的断言**，但不能把数量断言降级。

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceQueryPlannerTest,FieldEvidenceQueryPlannerGenerativeTest test
```

Expected: PASS。两类字段都走组合生成且 `positioning` 等新字段也达标。

---

### Task 4: `IncludeDomainPlanner` 自动域名决策

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/IncludeDomainPlanner.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/IncludeDomainPlannerTest.java`

- [ ] **Step 1: failing test——域名由 sourceType + 竞品域名自动决策**

复现 PoC 测试二"include_domains 强制限定官方域名"的策略，但自动化：

```java
@Test
void shouldPlanOfficialDomainsForOfficialSourceType() {
    List<String> domains = planner.planIncludeDomains(
            "哔哩哔哩", "bilibili.com",
            List.of("OFFICIAL", "DOCS"),
            "OFFICIAL_POSITIONING");
    // OFFICIAL/DOCS 路径应约束到竞品官方域名族，而非全网
    assertThat(domains).contains("bilibili.com");
    assertThat(domains).anySatisfy(d -> assertThat(d).contains("bilibili"));
}

@Test
void shouldReturnEmptyForThirdPartySourceType() {
    // 第三方类（REVIEW/NEWS）不约束域名 = 允许全网第三方源进入（对齐证据准入原则 0.3.1）
    // 注意：空 includeDomains 表示"不限制"，不是"排除"——第三方源因此可进，只是评分权重低
    List<String> domains = planner.planIncludeDomains(
            "哔哩哔哩", "bilibili.com", List.of("REVIEW"), "THIRD_PARTY_REVIEW");
    assertThat(domains).isEmpty();
}

@Test
void shouldNotExcludeThirdPartyEvenForOfficialField() {
    // 官方字段也只是"官方优先"，不排他：official 域名进 includeDomains 作加权锚点，
    // 但不得据此拒绝非官方候选（排他逻辑不在 planner，也不应存在于下游）
    List<String> domains = planner.planIncludeDomains(
            "哔哩哔哩", "bilibili.com", List.of("OFFICIAL"), "OFFICIAL_POSITIONING");
    // 约束官方域名仅用于"优先命中"，调用方不得把它当 allowlist 排除其他来源
    assertThat(domains).contains("bilibili.com");
}
```

- [ ] **Step 2: 接入 planner 产出的 `FieldEvidenceQuery.includeDomains`**

把 06 里"仅当 query 含 `site:` 才回填域名"的被动逻辑，替换为 `IncludeDomainPlanner` 主动决策。OFFICIAL/DOCS/PRICING 把官方域名族写入 `includeDomains` 作为**优先命中锚点**；REVIEW/NEWS/OPEN_WEB 返回空（不约束，允许全网第三方）。

> **关键（对齐 0.3.1 原则一）**：`includeDomains` 是"优先"而非"排他"。即便官方字段写入了官方域名，调用方（06 的 TavilyFastLane）**不得**把它当 allowlist 去拒绝非官方候选——非官方源仍可进入，只是在 Task 5 评分中权重更低。07 不引入任何"非官方即拒"的过滤。

Run:

```powershell
mvn -pl backend -Dtest=IncludeDomainPlannerTest test
```

Expected: PASS。

---

### Task 5: `ContentUsabilityScorer` 可用性分与可信度解耦

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/ContentUsabilityScorer.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/ContentUsabilityScorerTest.java`

- [ ] **Step 1: failing test——官方域名壳页可用性分必须被压低**

直接打击 spec 1.1 点名的"官方域名/文本长度掩盖导航壳/登录壳"：

```java
@Test
void shouldScoreNavShellLowEvenWhenFromTrustedOfficialDomain() {
    ContentUsabilityScore score = scorer.score(CollectedPageView.builder()
            .url("https://app.bilibili.com")          // 高信任官方域名
            .sourceTrust(0.95)                          // 来源可信度高
            .bodyText("下载 安卓 iOS TV PC 车机 扫码下载") // 但正文是导航壳
            .structuredBlocks(List.of())
            .build());
    // 来源可信度高 != 正文可用性高
    assertThat(score.getUsability()).isLessThan(0.4);
    assertThat(score.getReasons()).contains("NAV_SHELL_DETECTED");
}

@Test
void shouldScoreThirdPartyHighWhenContentIsRichAndReal() {
    // 落地证据准入原则 0.3.1：非官方来源正文质量高时，可用性分必须高，
    // 来源类型只调权重、不得把高质量第三方内容压到不可用。
    ContentUsabilityScore score = scorer.score(CollectedPageView.builder()
            .url("https://some-tech-blog.com/bilibili-open-pricing")  // 第三方博客
            .sourceTrust(0.5)                                          // 来源可信度中等
            .bodyText("哔哩哔哩开放平台计费说明：基础接口免费，超出额度按调用量计费，"
                    + "直播开放能力需企业认证，具体档位为...")        // 正文真实、信息密度高
            .structuredBlocks(List.of("PRICING_BLOCK"))
            .build());
    // 正文质量主导：第三方但内容好 => 可用性高
    assertThat(score.getUsability()).isGreaterThan(0.6);
    // 来源层级被标记，供人工终审与报告标注（安全边界）
    assertThat(score.getSourceTier()).isEqualTo("THIRD_PARTY");
}
```

- [ ] **Step 2: 在评分汇聚点引入可用性维度**

执行时先定位实际评分汇聚点（`SourceCandidateRanker` 或 EvidenceQualityGate 评分入口），把 `ContentUsabilityScore` 作为**独立维度**纳入，最终证据强度 = f(来源可信度, 正文可用性)，且可用性可对总分**封顶**（壳页即使来源可信也不得升级为强证据）。

> **关键（对齐 0.3.1）**：来源类型在评分里只是**权重项**，不是**门槛项**。官方加权、第三方降权，但高质量第三方正文不得被压到"不可用"。评分结果必须输出 `sourceTier`（OFFICIAL / THIRD_PARTY），供落库、报告标注和人工终审区分来源——这是第三方证据可用的安全前提。
>
> 注意：这是改动评分主路径，`EvidenceQualityGateTest / CollectorAgentEvidenceQualityGateTest` 是关键回归闸门，Task 6 必须全绿。

Run:

```powershell
mvn -pl backend -Dtest=ContentUsabilityScorerTest test
```

Expected: PASS。

---

### Task 6: 系统测试——天花板验收 + 06 回归

**Files:**
- Create: `backend/src/test/java/cn/bugstack/competitoragent/integration/Task66GenerativeQueryPlannerSystemTest.java`

- [ ] **Step 1: 天花板验收系统测试**

复用 06 的两个样本输入，断言生成式 planner 的端到端效果：

```java
// 1. 任意 required 字段都进入多 query（不止 coreFeatures/pricing）
// 2. OFFICIAL 字段的 query 携带自动决策的 includeDomains
// 3. 壳页（app.bilibili.com）可用性分被压低，不升级为强证据
// 4. 无公开证据时仍输出 NO_PUBLIC_EVIDENCE_AFTER_SEARCH（不回退）
```

- [ ] **Step 2: 06/Tavily 全量回归**

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryPlannerTest,FieldEvidenceQueryPlannerGenerativeTest,IncludeDomainPlannerTest,ContentUsabilityScorerTest,DimensionEvidencePlanFactoryTest,FieldEvidenceCoverageAggregatorTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorPublicRecoveryTest,EvidenceQualityGateTest,CollectorAgentEvidenceQualityGateTest,CollectorAgentFieldEvidenceLoopTest,Task66FieldFirstEvidenceLoopSystemTest,Task66GenerativeQueryPlannerSystemTest,Task66CoverageContractRegressionTest" test
```

Expected: PASS。证明 07 在不破坏 06 执行骨架的前提下把 query 生成升级到天花板。

---

### Task 7: 审计、进度文档与采集链路封板声明

**Files:**
- Create: `docs/Tavily/progress/2026-06-29-task66-07-generative-query-planner-progress.md`

- [ ] **Step 1: 写入 07 进度记录**

- [ ] **Step 2: 写采集链路封板声明**

在进度文档结尾写明（这是 07 区别于 01-06 的关键动作）：

```markdown
## 采集链路封板声明

Tavily 目录的目标——让系统跑出 PoC 证明的搜索采集天花板——至此达成：
系统能自动生成互补 query、自动决策 include_domains、按正文可用性独立评分。

采集链路到此停止深修。后续不再新增 Tavily 子阶段修补 query/采集质量。
- 跨字段 evidence graph 等天花板之上的能力，归入 4.x 之后，不在 Tavily 目录扩展。
- 下一步按 docs/specs/project-evolution-roadmap.md：把"字段→query→执行→评分"
  runtime 缺失沉淀为采集链路引擎级诊断，转向补其他空白链路诊断，凑 4.x 收敛点。
```

---

## Definition of Done

- if-field 硬编码分支从 `FieldEvidenceQueryPlanner` 删除，改为 `FieldQueryComposition` 组合生成。
- 任意 required 字段（含 06 未硬编码的 `positioning / targetUsers / strengths` 等）都能产 ≥2 条语义互补 query，互补性可由 token 重合度断言。
- 新增字段类型只需扩 `SIGNAL_TERMS` 映射，无需改 planner 逻辑。
- **每个字段都产出至少一条第三方视角 query**（pricing/weaknesses 等官方常缺字段尤其如此），官方视角占多数但第三方不为 0（落地 0.3.1 原则一）。
- **第三方准入与字段语义无关（全字段一视同仁）**：契约层（Task 2A）所有字段的证据路径都含第三方 sourceType；正向字段（coreFeatures/positioning 等）与 weaknesses 一样能用第三方来源。测试以正向字段验证，不只测 pricing/weaknesses。
- `IncludeDomainPlanner`：官方类把官方域名写入 `includeDomains` 作**优先锚点**（非排他），第三方类返回空（允许全网）；调用方不得把官方域名当 allowlist 排除非官方候选。
- `ContentUsabilityScorer` 与来源可信度解耦：正文质量主导评分，来源类型只调权重——官方壳页被压低、高质量第三方正文拿高分；输出 `sourceTier`（OFFICIAL/THIRD_PARTY）供报告标注与人工终审。
- 全链路无"非官方即拒"的过滤逻辑；第三方证据可进入，靠权重+人工终审而非门槛把关。
- 06 既有测试（`FieldEvidenceQueryPlannerTest` 等）与全量 Tavily 回归集合全绿。
- 进度文档含**采集链路封板声明**，明确 Tavily 目录目的达成、停止深修、转向诊断收敛。

## 不在 07 中做

- 不做跨字段 `EvidenceGraphBuilder / CrossFieldEvidenceLinker`（天花板之上，4.x 之后）。
- 不重建 06 执行骨架、CoverageContract、EvidenceQualityGate。
- 不新增 gate/audit/recovery/repair 外围层。
- 不重构 `allowOfficialOnly` 死字段为 source weight（07 只确保不依赖它、不引入官方排他；正式以连续 source weight 取代留作后续，见诊断文档技术债）。
- 不承诺真实公网每个字段必然命中；无证据仍输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`。
- 不在 Tavily 目录开 08 继续修采集——后续走 roadmap 的链路诊断收敛路径。
