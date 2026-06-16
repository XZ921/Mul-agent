# 采集执行层专项诊断

## 文档目的

本文档用于把 `搜索与采集` 链路中的“采集执行问题”从既有 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md) 总诊断中单独抽出，形成可直接驱动后续实施计划的专项诊断基线。

这份文档只回答以下问题：

1. 当前系统为什么经常“搜到了 URL，但拿不到可用证据”。
2. 页面采集失败、正文提取失真、反爬误判、证据截断分别发生在哪一层。
3. 哪些问题是 `P0` 级 blocker，必须先修。
4. 这些问题应该如何映射到后续 `Wave 7+` 的计划，而不是继续混在搜索策略优化里。

---

## 诊断边界

### 本文档纳入范围

本文档只诊断从“搜索已选出正式采集目标”到“下游拿到可消费证据”之间的采集执行层问题，主要覆盖：

1. [PlaywrightPageCollector.java](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java)
2. [PageContentExtractionSupport.java](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java)
3. [SearchBrowserProperties.java](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java)
4. [SchemaExtractorAgent.java](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java)
5. `selectedTargets -> collected pages -> evidence content` 之间的运行期契约

### 本文档不纳入范围

以下问题仍归 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md) 或下游专题，不在本文档内展开：

1. query 生成、多引擎路由、候选验证、候选排序、正式选源
2. `SearchPolicyResolver`、`Source Family Catalog`、provider role 主辅路由
3. Schema 设计本身是否合理
4. 分析推理、报告写作、质量审查的独立缺陷

---

## 当前结论

当前系统的主瓶颈已经从“能不能找到页面”明显转移为“能不能把找到的页面稳定转成证据”。

更准确地说，当前采集执行层同时存在四个连续断点：

1. **资源获取断点**：页面可能没有被真正拿到，或拿到的是前端壳、challenge 页、登录页、空内容页。
2. **正文提取断点**：即使页面已打开，也未必拿到真正高价值正文，结构化区块更容易丢失。
3. **反爬判定断点**：系统对 blocked / challenge / 正常页面的识别过粗，既会漏检，也会误杀。
4. **证据交付断点**：即使采集层拿到了较长正文，下游消费时仍可能被粗暴截断，导致高价值部分丢失。

因此，当前很多“结果不好”的根因，并不是搜索没有找到 URL，而是采集执行层没有把这些 URL 转化成稳定、可追溯、可被下游消费的证据包。

---

## 现场症状

结合 2026-06-15 已记录的真实任务现场，当前采集执行层已经暴露出以下症状：

1. 真实任务 `37` 最终报告证据接口仅返回 1 条有效证据，且证据质量明显不足。
2. 真实任务 `39` 初次 `execute` 曾因 Playwright `__adopt__` / 反爬信号进入 `WAITING_INTERVENTION`，说明网页采集链路在真实站点上并不稳定。
3. 搜索回放、`sourceUrls`、`searchAudit` 已基本可见，但采集阶段仍缺少正式的失败分类、采集质量信号与恢复语义。

这些现场症状不能单独证明每一个底层原因，但足以说明：**采集执行层已经成为搜索与采集链路的现实 blocker。**

---

## 关键诊断证据

### 1. HTTP 先行策略把“轻量命中”错误当成“采集完成”

在 [PlaywrightPageCollector.java:101](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java:101) 附近，当前采集入口先执行 `collectByHttp(...)`，并在 HTTP 成功后直接返回：

```java
CollectedPage httpPage = collectByHttp(url, competitorName, sourceType);
if (httpPage.isSuccess()) {
    return httpPage;
}
```

问题不只是“多浪费了一次请求”，更在于：

1. 采集完成条件被定义成了“HTTP 返回了足够长的文本”，而不是“拿到了满足证据目标的页面内容”。
2. 对现代 `official / docs / pricing / release notes` 页面，HTTP 可能拿到的是 hydration 之前的壳、半成品正文或缺少关键结构块的静态片段。
3. 一旦 `isMeaningfulHttpContent(...)` 误判成功，浏览器链路根本不会启动。

[PlaywrightPageCollector.java:425](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java:425) 附近还存在 `SPA_SHELL_SIGNALS` 检测，这说明系统已经意识到“HTTP 可能拿到 SPA 外壳”，但当前设计仍然把 HTTP 放在执行入口最前面。

这暴露的不是单个 heuristics 问题，而是更深层的语义问题：

1. 当前采集策略是 `transport-first`，不是 `evidence-first`。
2. 系统没有按数据源家族、页面类型或证据期望来决定 `http-first` 还是 `browser-first`。
3. 系统也没有在 HTTP 命中后继续做“证据充分性复核”，而是直接视为采集成功。

### 2. 浏览器链路缺少正式的页面就绪模型

即使进入 Playwright，系统当前也缺少一个正式的“页面已经可采”的判定模型。

这会导致以下风险：

1. 页面 DOM 已加载，但正文还未渲染。
2. 页面已渲染首屏，但价格表、功能块、FAQ、文档目录仍在延迟加载。
3. 页面发生 iframe、context reset、脚本重挂载或 challenge 跳转时，系统没有把它们识别为独立失败模式。

任务 `39` 暴露出的 `__adopt__` / 反爬信号，更像是页面上下文被重建、Frame 被接管、challenge 脚本介入后的症状，而不是普通“网络慢一下”。  
这说明当前浏览器链路不是简单需要“多重试一次”，而是需要正式化：

1. 页面导航完成判定
2. 正文稳定完成判定
3. challenge / blocked / login required 判定
4. 重试与降级路径

### 3. 正文提取过度依赖固定 CSS 选择器

[PageContentExtractionSupport.java:28](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java:28) 起，当前浏览器正文提取主要依赖一组固定 selector：

```javascript
const selectors = [
  'article', 'main', '[role="main"]', '.article', '.article-body',
  '.article-content', '.post-content', '.entry-content', '.markdown-body',
  '.docs-content', '.doc-content', '.documentation', '.content',
  '.content-body', '.prose', 'section'
];
```

这套规则对博客、文档站、GitHub 页面是有帮助的，但它的本质仍然是“约定 class 命名的正文页启发式”，对以下页面天然不稳：

1. SaaS 官网产品页
2. 私有 class 命名的营销页
3. 组件化定价页
4. 多列布局的功能说明页
5. FAQ、feature grid、pricing cards 之类结构化区块主导的页面

更关键的是，当前逻辑并没有把这些高价值区块当成正式对象抽出来，而是先抽“疑似正文块”，再从中选出一个“最佳内容块”。

### 4. 正文评分模型偏向“长文本块”，不等于“高价值证据块”

[PageContentExtractionSupport.java:122](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java:122) 附近，当前评分逻辑主要根据：

1. 文本长度
2. `article / main / content / docs / prose` 等命名信号
3. `body` 惩罚
4. 噪音标记
5. 链接密度
6. 长行数量

这会带来两个根本性问题：

1. “最长的块”不一定是“最有业务价值的块”。
2. 结构化页面的高价值信息常常不是长正文，而是价格表、对比表、FAQ、release notes、目录树、参数区块。

因此，当前提取逻辑即使不返回空内容，也仍然可能返回：

1. 过度混杂的整页正文
2. 错选的长页脚文本
3. 被导航、推荐、页脚混入的低纯度文本
4. 丢失关键结构块后的残缺正文

### 5. blocked signals 只有英文词表，采集判定会漏检也会误杀

[SearchBrowserProperties.java:48](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java:48) 当前默认 `blockedSignals` 为：

```java
List.of(
    "captcha",
    "unusual traffic",
    "verify you are human",
    "access denied",
    "robot check",
    "security check"
);
```

这会产生两类对称问题：

1. **中文反爬漏检**：例如“请输入验证码”“检测到异常访问”“请完成安全验证”等页面无法被稳定识别。
2. **正常英文页面误杀**：例如安全文档、风控文档、API 文档里出现 `security check`、`captcha` 等术语时，可能被错误当成 blocked。

这说明当前 blocked 判定仍然停留在“词表触发”，还没有升级为多信号决策：

1. 页面标题
2. URL 模式
3. DOM 结构
4. 按钮文本
5. challenge 跳转行为
6. 页面上下文重建信号

### 6. 采集结果仍然被当成“大段正文字符串”向下游交付

[SchemaExtractorAgent.java:524](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java:524) 当前会把单条证据正文截断到 `8000` 字符：

```java
if (content != null && content.length() > 8000) {
    content = content.substring(0, 8000) + "...(truncated)";
}
```

这暴露的不是 Extractor 单点问题，而是采集交付契约本身仍然过粗：

1. 采集层交付的是“整页正文大字符串”，不是可分块 evidence bundle。
2. 下游为了控制上下文窗口，只能按字符数截断。
3. 一旦价格表、FAQ、功能区块位于正文后半段，高价值信息会在进入 LLM 前直接丢失。

因此，当前系统不是“采集成功后被 LLM 稍微看少一点”，而是“采集结果的正式交付形态还不适合下游消费”。

### 7. 采集阶段缺少自己的正式事实源

当前系统已经基本形成了 `searchAudit`、search replay、search checkpoint 的正式化方向，但采集段仍然缺少对等的正式事实源。

这会导致：

1. 系统知道 `selectedTargets` 是什么，但不知道每个 target 最后由哪个执行器处理。
2. 系统知道页面失败了，但不知道失败发生在 `fetch / render / extract / anti-bot / truncate` 的哪一层。
3. `rerun / resume` 可以复用搜索结果，但还不能稳定从采集阶段的细粒度检查点继续。
4. UI 和回放无法清楚区分“搜索失败”和“采集失败”。

这意味着当前系统的“可观测性闭环”仍然主要停留在搜索，不在采集。

---

## 阻塞层级重排

| 阻塞级别 | 主矛盾 | 代表代码锚点 | 若不先修会发生什么 |
| --- | --- | --- | --- |
| `P0` | 采集成功语义错误，HTTP 轻量命中会提前短路浏览器正式采集 | [PlaywrightPageCollector.java:101](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java:101)、[PlaywrightPageCollector.java:425](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java:425) | 正确 URL 也可能只被采成 SPA 壳、半成品正文或缺少关键结构块的页面 |
| `P0` | 浏览器链路缺少页面就绪与 challenge 正式判定 | [PlaywrightPageCollector.java](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java)、任务 `39` 现场症状 | 系统无法稳定区分“页面没开完”“被反爬拦截”“上下文被重建”“正文尚未出现” |
| `P0` | 正文提取模型过度依赖固定 selector 与长文本评分 | [PageContentExtractionSupport.java:28](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java:28)、[PageContentExtractionSupport.java:122](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java:122) | 即使浏览器打开了页面，也可能拿到错正文、空正文或残缺正文 |
| `P1` | blocked 判定只靠英文词表，中文场景漏检、英文文档误杀 | [SearchBrowserProperties.java:48](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java:48) | blocked / challenge / normal page 三者会持续混淆，影响重试、降级与人工接管 |
| `P1` | 采集结果向下游交付仍是粗粒度大字符串 | [SchemaExtractorAgent.java:524](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java:524) | 价格表、FAQ、功能区块等高价值信息可能在进入 LLM 前就被截断丢失 |
| `P1` | 采集阶段没有正式审计、回放、恢复检查点 | 运行时 collector output 与 replay 现状 | 系统只能看到“结果不好”，却无法准确解释“采集到底坏在哪一步” |
| `P2` | 采集执行器仍然事实上只有一个网页实现 | [PlaywrightPageCollector.java](/E:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java) | 后续即使接入 API / feed，也会继续被错误压扁成网页采集问题 |

---

## 根因归并

把上述症状收口之后，当前采集执行层的根因可以归并为四条：

### 1. 采集完成条件定义错误

当前系统实际上把“拿到一段看起来像正文的文本”当成了“采集成功”。  
但业务真正需要的是：

1. 拿到正确页面
2. 拿到正确正文
3. 拿到高价值结构块
4. 拿到可追溯证据
5. 让下游还能继续消费

### 2. 页面采集和证据提取仍然是同一层 heuristics

当前实现把“浏览器打开页面”和“找到最佳正文块”压在同一个轻量流程里，缺少中间层：

1. 页面是否真的可采
2. 页面是否被 challenge
3. 哪些结构块被命中
4. 内容是否达到证据期望

### 3. 采集策略没有按来源家族和页面类型分化

尽管搜索层已经开始走 `Source Family Catalog`，但采集层仍然近似“一把尺子量所有 URL”：

1. 官网产品页
2. 定价页
3. 文档页
4. 新闻正文
5. GitHub release 页

这些页面的“正确采法”并不相同，但当前系统还没有正式表达这些差异。

### 4. 采集结果缺少正式对象化

当前采集结果更像“页面标题 + 大段正文”，而不是：

1. 资源定位信息
2. 正文片段
3. 结构化区块
4. 质量信号
5. 失败分类
6. 恢复检查点

这会让后续所有能力都只能围绕字符串打补丁。

---

## 与现有计划的关系

这份诊断不是对 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md) 的否定，而是它的继续分层。

两者关系应理解为：

1. `CollectorAgent.md` 解决“搜索与采集大链路为什么失真”。
2. 本文解决“在搜索已经找到目标之后，采集执行为什么仍然拿不到证据”。

这份诊断与 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 的对应关系如下：

1. `Wave 7`：先把采集执行缝、最小任务包、执行协调器、执行器注册表和采集检查点骨架正式化，不预判一整套扩展字段全集
2. `Wave 8`：网页采集执行器加固，重点处理 HTTP 先行、页面就绪、challenge、正文提取、结构块提取，并把真实失败模式反向沉淀为正式字段与状态分类
3. `Wave 9`：采集审计、采集回放、包级重跑与恢复
4. `Wave 10`：如果 `GitHub API` 或 `News API` 同时存在 discovery 和 collection 两层 owner，则在本波次正式把结构化采集 owner 收口到 `ApiDataCollectionExecutor`
5. `Wave 12`：采集结果与下游证据契约闭环，解决 `8000` 字截断背后的交付模型问题

---

## 修复后能解决什么，不能解决什么

### 能解决

如果采集执行层修好，至少能直接改善：

1. 正确 URL 被采成空壳或薄内容的问题
2. SPA / hydration 页面早退为 HTTP 成功的问题
3. challenge / blocked / 正常页面混淆的问题
4. 定价、文档、FAQ、release notes 等高价值页面的正文缺失问题
5. 任务最终只剩极少数有效证据的问题

### 不能单独解决

即使采集执行层修好，以下问题仍不会自动消失：

1. 搜索 query 与候选排序是否足够好
2. Extractor 是否能稳定转成正确 schema
3. Analyzer 是否按证据约束做推理
4. Writer / Reviewer 是否能把高质量证据转成高质量结论

因此，采集专项修复是必要前置，但不是整个业务质量闭环的全部答案。

---

## 诊断结论

当前系统在 `搜索与采集` 链路上的主要现实 blocker，已经不是“搜索找不到 URL”，而是“采集执行没有把 URL 变成稳定证据”。

这一定性成立的依据不是单一代码 smell，而是多层连续断点同时存在：

1. `HTTP-first` 提前短路正式采集
2. 浏览器链路缺少页面就绪与 challenge 模型
3. 正文提取过度依赖 selector heuristics
4. 结构块没有一等抽取地位
5. blocked 判定只有英文词表
6. 下游仍按大字符串截断消费采集结果
7. 采集阶段没有自己的正式事实源

因此，后续若继续推进 `搜索与采集`，必须把“采集执行层”视为独立专题，而不是继续把它当成 `PlaywrightPageCollector` 的几个局部 patch。
