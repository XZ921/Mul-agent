# Task66 数据库实证诊断：一次失败的三层混合原因

> 2026-06-29。本文不基于推演，而是直接从 `ecommerce_agent` 库 `analysis_task / report / evidence_source` 表读取 `task_id=66` 的真实落库数据，拆解这次执行失败的三个**相互独立**的原因。目的是为 07（采集链路天花板收口）提供实证依据，并为采集链路封板后的下一份诊断（质量审查链路）锚定证据。

---

## 0. 结论先行

`task_id=66` 的失败是**三层混合原因**，不是单一的"搜索采集差"：

1. **任务模板错配**（最致命，与采集无关）：开放平台主题用了标准版模板，强制要 `pricing / weaknesses`，而官方文档天然不含定价与自身短板。唯一的 BLOCKER 由此而来。
2. **采集证据高度重复 + 壳页冒充**（采集链路问题，07 可修）：6 条证据实为 2 份正文复制而成；B站那份是导航壳页，不是真文档。
3. **评分/门禁口径问题**（质量审查链路，07 不碰）：`formatting_issue` 等 MAJOR 来自 Writer 措辞与 Reviewer 规则，与证据是否采到无关。

三者叠加把 `quality_score` 压到 72、`quality_passed=false`、任务 FAILED。

---

## 1. 任务现场（analysis_task）

```
id                  = 66
task_name           = Tavily Phase1 9093真测-抖音B站官方文档-20260627-204210
subject_product     = 短视频平台开放生态与开发者能力
competitor_names    = ["抖音","哔哩哔哩"]
competitor_urls     = ["https://open.douyin.com/","https://open.bilibili.com/"]
analysis_dimensions = ["开放平台","开发者生态","产品功能"]
source_scope        = ["官网","产品文档"]
report_template     = 标准版
status              = FAILED
error_message       = 质量闭环未达到通过条件，请检查评审结果
```

注意：**入口 URL 是好入口**（`open.douyin.com` / `open.bilibili.com` 开放平台），维度也是正向的开放平台/开发者生态/产品功能。这次失败不能归咎于"入口太弱"。

---

## 2. 报告评分现场（report）

```
quality_score             = 72        （未达通过线）
quality_passed            = false
evidence_count            = 6
writer_evidence_state     = PARTIAL_SOURCE
citation_gap_severity     = HIGH
missing_citation_sections = ["pricing","weaknesses","conclusion","report_conclusion"]
```

quality_issues 共 4 条，分布：

| section | type | level | severity | dimension |
|---------|------|-------|----------|-----------|
| 4.2 对比劣势与潜在风险 | missing_citation | **BLOCKER** | ERROR | 修订可执行性 |
| 定价策略 | coverage_gap | MAJOR | WARNING | 修订可执行性 |
| 6. 证据完整性与搜索覆盖说明 | formatting_issue | MAJOR | WARNING | 修订可执行性 |
| 定价策略、短板与风险 | coverage_gap | MAJOR | STRUCTURE_COMPLETENESS | 结构完整性 |

唯一 BLOCKER 落在 `4.2 对比劣势与潜在风险`，suggestion 是"需要引用具体平台能力目录或第三方报告来证明B站开放广度的局限性"——本质是 **weaknesses 类字段无公开证据却被写出结论**。

---

## 3. 采集证据现场（evidence_source）

6 条证据的 URL / 类型 / 正文长度：

```
https://open.bilibili.com                | OFFICIAL | DIRECT_LOCATOR  | full_len=1290
https://open.bilibili.com/docs           | DOCS     | FAMILY_TEMPLATE | full_len=1290
https://open.bilibili.com/documentation  | DOCS     | FAMILY_TEMPLATE | full_len=1290
https://open.douyin.com                  | OFFICIAL | DIRECT_LOCATOR  | full_len=8960
https://open.douyin.com/docs             | DOCS     | FAMILY_TEMPLATE | full_len=8960
https://open.douyin.com/documentation    | DOCS     | FAMILY_TEMPLATE | full_len=8960
```

### 3.1 实为 2 份正文，被灌成 6 条（md5 验证）

- B站 3 条 full_content md5 全为 `13f482b95986ff7333ca6f7789f8c225`（完全相同）
- 抖音 3 条 full_content md5 全为 `28d2ee83f08582b43a3a73e236de96ea`（完全相同）

`FAMILY_TEMPLATE` 发现机制把 `/`、`/docs`、`/documentation` 三个 URL 当作不同证据，但抓回的是同一份正文。`evidence_count=6` 是虚高，真实独立证据只有 **2 份**。

### 3.2 B站那份是导航壳页

B站 1290 字符正文开头实测：

```
[主站](...) 开放平台 开平文档中心 开平管理中心 账号管理 应用管理 授权管理
直播 创作者服务中心 互动玩法 登录|注册 立即加入 ... 产品服务 业务开放 ...
```

这是**导航/入口壳**，不是可抽取字段的文档正文。抖音那份 8960 字符是真实可用文档（含开放能力、SDK、小程序、交易产品等实质内容）。所以两个竞品的证据质量本身就不对等：抖音真实可用，B站基本是壳。

---

## 4. 三层原因与 07 的关系

| 原因 | 性质 | 数据证据 | 07 能否解决 |
|------|------|---------|------------|
| 1. 标准版模板强制 pricing/weaknesses | 任务设计错配 | 唯一 BLOCKER 在 4.2 劣势章节；missing=[pricing,weaknesses,...] | ❌ 改模板即可，无需 07 |
| 2. 6 条证据=2 份正文复制 | 采集去重缺失 | md5 两两相同 | ✅ 07 Task 2 `minDistinctEvidenceCount` 去重 |
| 3. B站壳页冒充文档 | 正文可用性未独立评分 | 1290 字符全是导航词 | ✅ 07 Task 5 `ContentUsabilityScorer` 压低壳页 |
| 4. formatting_issue 等 MAJOR | Writer 措辞 + Reviewer 规则 | "移除推测标记，直接使用完整URL" | ❌ 质量审查链路，07 不碰 |

---

## 5. 对规划的指向

### 5.1 07 的真实价值被这份数据证实

原因 2、3 正是 07 的去重与壳页压分要解决的——`task_id=66` 提供了它们的真实样本（md5 重复 + 1290 字符导航壳）。07 不是空想的优化，它修的是已落库的真实缺陷。

### 5.2 但 07 救不了 task66 本身的通过

task66 的**唯一 BLOCKER 是模板错配**（原因 1），这靠 07 无法消除。要让这类开放平台任务通过，必须用**能力介绍模板**（不把 pricing/weaknesses 设为 blocker），而不是只减字段。

### 5.3 下一份诊断的锚点

原因 4（`formatting_issue`、Reviewer 修订可执行性维度）与 `docs/specs/project-evolution-roadmap.md` 第 238 行点名的"score/qualityGate/deliveryStatus 语义混乱"是同一条链路。采集链路（07）封板后，这就是"找 4.x 收敛点"要补的**质量审查链路诊断**的起点。

### 5.4 技术债：`allowOfficialOnly` 是写了无人读的死字段

代码核查（2026-06-29）发现 `CoverageFieldContract.allowOfficialOnly`（默认 true）存在严重技术债：

- `CoverageContractResolver` 多处写入该字段（pricing=true、weaknesses=false 等），看似在区分"哪些字段只认官方"。
- 但全代码库 grep `getAllowOfficialOnly / isAllowOfficialOnly` **零消费方**——没有任何下游逻辑读取它过滤证据。它是个**写了没人读的死字段**。
- 真正决定"非官方源吃不吃亏"的是别处：`TavilyPrefetchedContentGate` 的 `officialDomainMatched` 质量分逻辑（非官方域名拿不到高 tier）+ task 的 `source_scope`。

更深的问题：`allowOfficialOnly` 的**布尔门槛语义本身就错**。按用户确立的证据准入原则（官方是权重非门槛、所有字段都不排斥非官方源），它应被替换为**连续的 source weight**。07 不依赖该字段，也不引入官方排他；正式以 source weight 取代留作后续重构。


---

## 6. 复现这份诊断的查询

```sql
-- 任务现场
SELECT id, task_name, report_template, status, error_message FROM analysis_task WHERE id = 66;
-- 评分现场
SELECT quality_score, quality_passed, evidence_count, missing_citation_sections, quality_issues FROM report WHERE task_id = 66;
-- 采集现场 + 去重验证
SELECT url, source_type, discovery_method, length(full_content) AS full_len, md5(full_content) FROM evidence_source WHERE task_id = 66 ORDER BY url;
```

数据库：`ecommerce_agent`（dev profile，`127.0.0.1:5432`，容器 `pgvector/pgvector:pg16`）。
