# 代码细节

## 一、搜索

### SearchEngineProperties

#### 1、默认引擎选型：bing 和 baidu 都是“广告重灾区”

```
public SearchEngineProperties() {
        // 保留一组默认引擎，避免配置缺失时整个浏览器搜索链路直接失效。
        put("bing", new EngineConfig("Bing", "https://www.bing.com/search", "q", true));
        put("google", new EngineConfig("Google", "https://www.google.com/search", "q", false));
        put("baidu", new EngineConfig("百度", "https://www.baidu.com/s", "wd", true));
        put("duckduckgo", new EngineConfig("DuckDuckGo", "https://duckduckgo.com/", "q", false));
    }
```

#### 2、简陋的别名归一化（`normalizeEngineKey`）强行锁死 Bing

```
public String normalizeEngineKey(String engineKey) {
        if (!StringUtils.hasText(engineKey)) {
            return "bing";
        }
        String normalized = engineKey.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ddg" -> "duckduckgo";
            case "msedge", "chrome", "chromium" -> "bing"; // 强行映射
            default -> normalized;
        };
    }
```

**致命问题**：当上游没有指定搜索引擎，或者因为使用自动化浏览器传入了浏览器类型（如 `chrome`、`chromium`、`msedge`）时，这个类会**一把推地把它们全部强行映射到 "bing"**。

**质量影响**：这意味着哪怕你想用别的引擎，只要配置稍有模糊，它就会自动让你降级去使用广告满天飞的 Bing 搜索。

#### 3、兜底逻辑（`resolveFirstEnabledEngineKey`）可能进一步恶化质量

```
public String resolveFirstEnabledEngineKey() {
        for (Map.Entry<String, EngineConfig> entry : entrySet()) {
            if (entry.getValue() != null && entry.getValue().isEnabled()) {
                return entry.getKey();
            }
        }
        return null;
    }
```

由于它是继承自 `LinkedHashMap`，会严格按照插入顺序遍历。在默认配置下，如果 primary 挂了，首当其冲被选中的就是 `bing`。这就从底层代码机制上锁死了你很难逃脱 Bing 和百度的“广告围剿”。

### 优化方案：

#### 1、私域垂直 API 负责精准抓取干货（主力），公网搜索引擎负责全网查漏补缺（辅助）

![image-20260606124221507](C:\Users\Lenovo\AppData\Roaming\Typora\typora-user-images\image-20260606124221507.png)

### CollectionTargetSelector

#### 1、排序策略被 `DISCARDED` 兜底逻辑带偏（放行了低分广告）

```
List<SourceCandidate> rankedCandidates = candidates.stream()
        .sorted(Comparator.comparing(
                        (SourceCandidate candidate) -> "DISCARDED".equalsIgnoreCase(candidate.getSelectionStage()))
                .thenComparing(SourceCandidate::getTotalScore, Comparator.reverseOrder()))
        .toList();
```

**问题所在**：虽然这里使用 `thenComparing` 优先把 `DISCARDED`（被验证丢弃的）链接往后排，但它的第二排序依据依然是 **`SourceCandidate::getTotalScore`（总分）**。

**致命结果**：正如我们之前分析的，大厂首页的广告由于域名权威分高，它的 `TotalScore` 天然极高。即使它被安检员判定为 `"DISCARDED"`，只要进入这个排序列表，它顶着极高的总分，依然会**死死压住那些分数稍低但验证通过（Verified）的补源干货链接**。最终数量一旦达到 `targetCount`，干货直接被挤出局。

#### 2、对 `attemptedTargets` 盲信（直接继承了安检员的漏网之鱼）

```
for (SearchCollectionTarget attemptedTarget : attemptedTargets.values()) {
    if (attemptedTarget.getCandidate() != null
            && Boolean.TRUE.equals(attemptedTarget.getCandidate().getVerified())
            && selectedUrls.add(attemptedTarget.getCandidate().getUrl())) {
        selected.add(attemptedTarget);
        // ...
```

**问题所在**：还记得 `CandidateVerifier` 里的免检漏洞吗？只要是 `OFFICIAL` 渠道，验证器直接无脑返回 `verified = true`。

**致命结果**：由于选择器第一步毫无保留地信任 `getVerified()`，导致那些挂着官方域名的纯营销广告首页（比如阿里云服务器售卖页）无条件直接晋级，直接占满了前排的 `selected` 名额。

#### 3、数据流设计非原子性（并发安全与快照不一致隐患）

在 `SearchExecutionCoordinator` 调用时，它必须按顺序执行：`selectTargets` $\rightarrow$ `markSelectedCandidates` $\rightarrow$ `refreshSelectedTargets`。

- **问题所在**：`refreshSelectedTargets` 内部使用了 `new LinkedHashMap<String, SourceCandidate>()` 进行二次遍历组装。
- **致命结果**：为了解决“状态快照不一致”的问题，代码在外部进行了多次流式操作。这属于典型的非原子性设计。在高并发的任务 DAG 中，这种复杂的映射和刷新极易引发并发修改异常（`ConcurrentModificationException`），且代码显得比较臃肿。

### 优化方案：

#### 1、引入“数据源出身血统划分”（Tier 梯队判定机制）

**核心思路**：打破目前“纯看综合分（TotalScore）”的垄断逻辑。因为大厂营销首页的域名权威分天然极高，按总分排序极易把真正的干货挤出局。

**具体做法**：在选择器内部建立一个**降维打击的梯队机制**：

- **第一梯队（Tier 1）**：运行期通过验证，且包含核心业务信号（如撞中价格、计费、接口等中文特征词）的链接。
- **第二梯队（Tier 2）**：人工或规划期明确指定的直达 URL，或者属于高价值的技术文档专属域（如包含 `help.`、`docs.`、`support.` 的子域名）。
- **第三梯队（Tier 3）**：普通的公网全网搜索自然结果。
- **第四梯队（Tier 4）**：明确属于大厂营销首页（如 `www.` 开头）或在安检阶段被标记为 `DISCARDED` 的链接。

**业务效果**：选择器在圈定目标时，**必须攒满高梯队（Tier 1/2）的链接，才允许低梯队链接进来凑数**。即使大厂首页广告综合分高达 0.99，也会因为被丢入 Tier 4 从而无缘最终的正式采集列表。

#### 2、实施“硬核去噪”过滤器链（Filter Chain Pattern）

**核心思路**：在进行数量截取（`limit`）前，强行增加一轮针对互联网营销乱象的业务滤网。

**具体做法**：建立一个**营销级关键词黑名单拦截器**。检查候选源的 URL 文本，凡是包含如 `promotion`（晋升/促销）、`activity`（活动）、`coupon`（优惠券）、`seckill`（秒杀）或 `b2b` 等强引流词汇的链接，在选择器内部**实行一票否决制，直接从列表中剔除**。

**业务效果**：在临门一脚的阶段，彻底切断由于搜索引擎竞价排名带进来的营销垃圾活动页，保障流向下游组件的原材料纯净度。

#### 3、宁缺毋滥的“降级兜底风控”

**核心思路**：优化目前“只要数量不够，连被丢弃的（DISCARDED）链接也强行拿来凑数”的过载兜底策略。

**具体做法**：在补充兜底目标时，增加一个**干货饱和度水位线**。例如：当系统已经积攒了满足 `targetCount / 2` 以上的高质量干货链接时，如果后续候选池里剩下的全是低质量或被丢弃的链接，系统应当**选择宁缺毋滥，果断拒绝将这些垃圾链接选为正式目标**。

**业务效果**：保证节点的“数据洁癖”，宁可让下游智能体少读几篇网页，也绝不让它去读垃圾广告页，从而大幅提升后续 AI 抽取的整体效率。

#### 4、动态权重配比（对接 YAML 配置文件）

**核心思路**：把排序的核心权重开放给实验室的配置文件。

**具体做法**：让选择器依赖的打分分值具备动态微调能力。在 `application.yml` 中暴露出相关性、时效性、权威度的权重比例（比如针对最新变动任务：将时效性权重调高至 0.5，把传统的域名权威度权重压低至 0.1）。

**业务效果**：选择器不需要变动逻辑，就能根据不同的调研场景（是要看最新公告，还是看经典权威技术架构），自动拿到更符合当前业务期望的 Target 列表。

#### 5、收拢写操作，消除非原子性时序隐患

**核心思路**：解决目前 `selectTargets` $\rightarrow$ `markSelectedCandidates` $\rightarrow$ `refreshSelectedTargets` 三步流式连招在高并发下的状态不一致风险。

**具体做法**：将“筛选目标”与“回填标记/语义刷新”封装在选择器内部的**单个原子方法**中。如果由于业务原因必须拆分，在内部涉及多轮映射（Map）和状态覆写时，引入线程安全的容器（如 `ConcurrentHashMap` 或写时复制列表）或加锁机制。

**业务效果**：确保任务在并发 DAG 引擎调度中运行时，不会因为时序抖动导致详情页和审计日志看到两套不一致的选源说明，提高系统整体的工程健壮性。

### CandidateVerifier

#### 1、致命后门：官方渠道无条件“免检放行”

```
private boolean isVerified(SourceCollector.CollectedPage page,
                               String sourceType,
                               List<String> matchedSignals) {
        if (!isUsableCollectedPage(page)) {
            return false;
        }
        // 核心漏洞所在：只要是官方渠道，直接返回 true 通关，不校验任何文本特征！
        if ("OFFICIAL".equalsIgnoreCase(sourceType)) {
            return true;
        }
        return matchedSignals.stream().anyMatch(signal -> !signal.startsWith("domain:"));
    }
```

**【深度原因分析】** 

这段代码对 `OFFICIAL` 来源采取了盲目信任策略。这就解释了为什么搜索引擎返回的“阿里云服务器售卖首页”或“全网降价大促营销页”能够百分之百通过安检。因为它们的域名确实属于官方资产，触发这条规则后直接流于形式、免检放行，导致下游的选择器和采集器直接被这些营销垃圾塞满。

#### 2、特征词库单一：纯英文、死板包含匹配导致“严重误杀”与“噪声放行”

```
private List<String> expectedKeywords(String sourceType) {
        return switch (sourceType == null ? "" : sourceType.toUpperCase(Locale.ROOT)) {
            // 全是英文词，没有针对国内云厂商进行本地化汉化
            case "DOCS" -> List.of("docs", "documentation", "help", "guide", "api", "reference");
            case "PRICING" -> List.of("pricing", "plan", "plans", "billing", "subscription", "enterprise");
            case "NEWS" -> List.of("blog", "news", "changelog", "update", "release", "announcement");
            case "REVIEW" -> List.of("review", "reviews", "rating", "customer", "compare", "g2", "capterra");
            default -> List.of("official", "product", "platform", "homepage");
        };
    }
```

#### 以及与之配合的暴力 `contains` 撞击逻辑：

```
private List<String> collectMatchedSignals(SourceCandidate candidate,
                                               SourceCollector.CollectedPage page,
                                               String sourceType) {
        Set<String> signals = new LinkedHashSet<>();
        // 简单地拼接 URL、标题和正文
        String combined = (safe(candidate.getUrl()) + "\n" + safe(page == null ? null : page.getTitle())
                + "\n" + safe(page == null ? null : page.getContent())).toLowerCase(Locale.ROOT);

        for (String keyword : expectedKeywords(sourceType)) {
            // 纯粹、死板的字符串包含判定
            if (combined.contains(keyword)) {
                signals.add(keyword);
            }
        }
        // ...
```

**【深度原因分析】**

 国内主流云厂商的最核心计费技术文档或公告，全篇基本都是纯中文（如“云数据库 RDS 详细价格表”、“费用调整通知”）。因为你的特征词表里只有英文的 `"pricing"` 或 `"billing"`，**真正的中文高价值干货页面会因为一个英文词都没撞中，被无情标记为未通过，直接降级为 `DISCARDED` 垃圾候选**；相反，某些营销页只要在侧边栏带了一句通用的 “API Reference” 英文菜单，就会被死板地捞起来，噪声巨大。

#### 3、工程设计性能浪费：验证与正式采集阶段发生“双重爬取”

```
for (SourceCandidate candidate : uniqueCandidates) {
            // 在验证阶段，就已经调用过 collector 满负荷去请求网络、拉取网页实体了
            SourceCollector.CollectedPage page = sourceCollector.collect(
                    candidate.getUrl(),
                    competitorName,
                    sourceType
            );
            // ...
            // 将已经完整抓下来的 page 包装进了 target 结构中返回
            SearchCollectionTarget target = SearchCollectionTarget.builder()
                    .updatedCandidate(updatedCandidate)
                    .collectedPage(page)
                    .build();
```

**【深度原因分析】** 在这段逻辑里，安检员已经吭哧吭哧把全量网页正文爬完了。然而，在下游的 `CollectorAgent` 拿到正式目标列表执行业务时（回顾之前的 `CollectorAgent.java`）：

```
// 对应 CollectorAgent.java 内部逻辑
    SourceCollector.CollectedPage page = target.getCollectedPage() != null
            ? target.getCollectedPage()
            : sourceCollector.collect(url, config.getCompetitorName(), sourceType);
```

由于你的系统在此之前数据流契约传递不紧密，导致这里大概率又对目标厂商的同一个 URL 进行了重复的全量 HTTP 或浏览器请求。这种“双重爬取”在极短时间内对目标网站形成了高频冲击，不仅大大增加了公网 IP 被厂商反爬虫系统（`browserBlockedReason`）拉黑的风险，还白白浪费了实验室服务器的线程资源与执行时间。

### 优化方案：

#### 1. 废除 `OFFICIAL` 渠道的免检权

- **功能描述**：彻底移除 `isVerified` 方法中针对官方渠道直接返回 `true` 的硬编码后门。所有候选源链接无论域名背景如何，一律平等地接受内容特征信号词库的盘查。
- **业务成效**：从源头斩断披着大厂域名外衣的营销首页、优惠券秒杀活动页，确保流向下游的全部是与调研主题强相关的具体文档内容。

#### 2. 本地化多语言汉化，丰富“中文语义特征词库”

- **功能描述**：重构 `expectedKeywords` 词表策略。针对国内竞品调研的特殊性，在不同的业务类型下强力追加中文行业特征词：
  - **价格分类（PRICING）**：引入 `["计费", "价格", "收费", "费用", "定价", "套餐", "降价", "计费概述"]`。
  - **文档分类（DOCS）**：引入 `["文档", "帮助中心", "指南", "手册", "用户指南", "参考手册"]`。
- **业务成效**：彻底解决因为纯英文词表导致的“高价值中文计费页面被误杀、降级”的尴尬现状，大幅度提高中文干货情报的召回率。

#### 3. 建立“惩罚阻断机制”，引入一票否决黑名单词库

- **功能描述**：目前的验证器只有正向奖励（包含关键词通关），缺乏反向阻断。应当新增一个营销噪声阻断特征词库（`blockedKeywords`）。一旦网页的标题、URL 或正文文本中高频、大面积出现诸如 `["新客户特惠", "抽奖", "分销", "秒杀", "代金券", "立即购买"]` 等纯营销噱头词，实行一票否决，直接打上 `DISCARDED` 标签予以丢弃。

#### 4. 落地“一次采集，终身受用”的内存快照复用策略

- **功能描述**：充分利用 `CandidateVerifier` 已经完整打捞回来的 `CollectedPage` 实体对象。在编排器和下游的 `CollectorAgent` 中，强制规定：**优先且高优读取验证期已经缓存好的网页正文快照（即 `target.getCollectedPage()`）**。只有当快照为空或断点失效时，才允许对互联网重新发起二次网络撞击。
- **业务成效**：将节点对竞品厂商网站的请求压力直降 50% 以上，在完美保护实验室公网 IP 不被拉黑的同时，将正式采集阶段的执行耗时直接压低到毫秒级。

#### 5. 开启轻量级“大模型语义裁决”兜底网

- **功能描述**：在配置文件中增设一个智能裁决开关。当字符串匹配规则处于模糊地带、难以断定时，不让大模型去读几万字的长篇正文（浪费 Token），而是仅仅将网页的 `Title`、`URL` 以及命中零星的信号词丢给本地轻量级小模型，让它花 0.5 秒时间做一个极简的二分类语义裁决：`“请判定该网页属于纯技术文档/计费公告，还是属于营销活动广告？请仅回答【是】或【否】”`。利用小模型的自然语言理解能力完成最后一公里的高精度去噪收口。

### PromptTemplateService

#### 1、搜索模板装载逻辑“串线”，把中文 YAML 模板整桶灌进英文模板池

```
private void loadSearchQueryTemplates() {
        // ...
        templates.putAll(queryTemplates);
        queryTemplates.forEach((key, value) -> {
            if (key.startsWith("search-")) {
                englishSearchTemplates.put(key, value);
            }
        });
    }
```

以及：

```
private List<String> buildEnglishSearchQueries(String competitorName,
                                               String sourceType,
                                               String domainHint) {
        // ...
        switch (normalizedType) {
            case "DOCS" -> {
                queries.add(renderEnglishSearchQuery("search-docs-primary", variables));
                queries.add(renderEnglishSearchQuery("search-docs-secondary", variables));
            }
            // ...
        }
}
```

**问题所在**：`englishSearchTemplates` 顾名思义应该只承载英文模板；但 `loadSearchQueryTemplates` 会把 `prompts/search-queries.yml` 中的全部 `search-*` 条目无差别写进去。而现在这个 YAML 文件本身写的是中文模板，比如 `"定价 价格 套餐"`、`"产品更新 发布日志"`、`"评测 评价 对比"`。

**致命结果**：只要竞品名本身不带汉字，系统就会进入 `buildEnglishSearchQueries`，然后再从一个**已经被中文模板污染的“英文模板池”**里拿 Query，最终打出类似：

- `Notion 定价 价格 套餐`
- `site:notion.so Notion 文档`

这种“半中半英”的混搭搜索指令。它不是完全不能用，但对于海外 SaaS、英文官网、国际搜索引擎来说，召回结果稳定性会明显塌陷，噪声很大。

#### 2、模板名叫“官网域内搜索”，模板内容却偷偷变成“文档域内搜索”

```
search-official-domain: "site:{domainHint} {competitorName} 文档"
```

**问题所在**：这段模板虽然在 `search-queries.yml` 里，但真正负责装载、渲染和下发它的是 `PromptTemplateService`。问题的本质不是 YAML 里写错了一个词，而是这个类把一个**语义已经漂移的模板**当成了稳定能力继续往下游发。

**致命结果**：当系统要做“官网域内二次检索”时，它实际上会天然偏向 `docs`、`help`、`guide`、`support` 这些文档类页面，而不是产品首页、产品概览页、功能介绍页。这等于是从 Query 模板层就把 `OFFICIAL` 场景偷偷扭成了 `DOCS` 场景。

### 优化方案：

#### 1、彻底拆分中英文模板源

- **功能描述**：至少拆成 `search-queries-zh.yml` 和 `search-queries-en.yml` 两套模板源，中文竞品和英文竞品严格走不同模板池，禁止继续共享同一份 YAML。
- **业务成效**：避免“英文竞品却打出中文检索词”的严重语义污染，让全局召回结果更稳定。

#### 2、把“官网域内搜索”模板修回它本来的业务语义

- **功能描述**：把 `search-official-domain` 从“文档搜索”改回“官网/产品/主页”语义，例如围绕 `official / homepage / product / 产品 / 官网 / 首页` 这些词构造。
- **业务成效**：官方入口检索不再天然偏向文档站，而会更准确地打捞产品主入口。


### HeuristicSourceDiscoveryService

#### 1、预览模式与正式规划模式不是“轻量差异”，而是两套不同世界

```
private List<SourcePlan> discoverInternal(String competitorName,
                                          List<String> providedUrls,
                                          List<String> requestedScopes,
                                          boolean previewOnly) {
        // ...
        List<SourceCandidate> searchCandidates = previewOnly
                ? List.of()
                : searchSourceProvider.search(competitorName, scopes);
```

**问题所在**：创建页预览时，`previewOnly=true` 会直接把 `searchCandidates` 砍成空列表。这意味着前端在“任务创建预览”里看到的来源规划，实际上压根没有经历真实搜索补源。

**致命结果**：用户在创建任务时看到的候选来源覆盖、分支说明、候选数量，和真正执行时看到的结果，不是“少一点细节”这么简单，而是**根本不是同一套候选现场**。这会导致：

- 预览页误导用户判断这次任务会怎么搜
- 用户以为来源策略很克制，执行时却突然冒出大量搜索候选
- 后续定位问题时，预览态与运行态完全对不上

#### 2、每个 scope 只保留前 5 条候选，容易把后面真正的干货直接腰斩

```
private static final int MAX_CANDIDATES_PER_SCOPE = 5;

private List<SourceCandidate> mergeCandidates(List<SourceCandidate> heuristicCandidates,
                                              List<SourceCandidate> searchCandidates) {
        // ...
        List<SourceCandidate> ranked = candidateRanker.rankAndDeduplicate(merged);
        return ranked.size() > MAX_CANDIDATES_PER_SCOPE ? ranked.subList(0, MAX_CANDIDATES_PER_SCOPE) : ranked;
}
```

**问题所在**：在真实互联网环境里，前 3~5 条搜索结果很容易被营销页、导航页、帮助中心入口、跳转页占满。你这里在规划期就把每个 scope 的候选源强行截断成 5 条，相当于在还没开始运行时验证之前，就把后面的高价值干货候选直接腰斩了。

**致命结果**：一旦前排被噪声占领，运行期根本拿不到更多备选，只能围着前几条垃圾页面死磕，补源质量上限被提前锁死。

### 优化方案：

#### 1、预览模式至少要返回“与正式模式同构的占位现场”

- **功能描述**：即使为了性能不在预览页执行真实搜索，也应该返回“预计会走哪些搜索 provider、预计会生成哪些 query、预计候选池会如何扩展”的结构化占位信息，而不是直接把搜索补源整体归零。
- **业务成效**：让创建页预览与真实执行保持同构，避免用户误判任务的真实搜索路径。

#### 2、把 `MAX_CANDIDATES_PER_SCOPE=5` 改成可配置动态阈值

- **功能描述**：允许根据不同来源类型、不同竞品、不同任务模式动态放宽候选上限，而不是一刀切卡死在 5。
- **业务成效**：在搜索结果受污染时，系统仍然有机会保留更多后排干货候选供运行期验证。


### RoutingSearchSourceProvider

#### 1、当前路由器是“无脑全量串扫”，不管前面命中够不够都继续把后面 provider 全打一遍

```
public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        List<SourceCandidate> mergedCandidates = new ArrayList<>();
        Map<String, SearchSourceProvider> providersByKey = indexProvidersByKey();

        for (String providerKey : resolveProviderOrder()) {
            SearchSourceProvider provider = providersByKey.get(normalizeProviderKey(providerKey));
            // ...
            List<SourceCandidate> providerCandidates = provider.search(competitorName, requestedScopes);
            if (!providerCandidates.isEmpty()) {
                mergedCandidates.addAll(providerCandidates);
            }
        }

        return sourceCandidateRanker.rankAndDeduplicate(mergedCandidates);
}
```

**问题所在**：这个路由器当前没有“命中即止”“预算水位”“每个 provider 结果上限”“全局搜索预算”这些概念。只要路由顺序里挂着，就会全部顺序执行一遍。

**致命结果**：哪怕前面的 `qianfan` 或 `serpapi` 已经返回了足够高质量的候选，系统依然会继续打 `browserPreview` 和 `http`。这会导致：

- 无意义增加外部搜索 API 成本
- 重复引入质量更差的后排噪声候选
- 让浏览器预览这种重型补源能力频繁白白出手

#### 2、路由器和运行期阶段语义彻底错位，表面叫“HTTP”，底层却可能把全部 provider 再跑一遍

```
private List<String> providerOrder = List.of("qianfan", "serpapi", "browserPreview", "http");
```

**问题所在**：这个类本身作为规划期“多 provider 聚合器”没有问题；但一旦被运行期编排器当成“HTTP 回退”的具体执行器来调用，它的语义就彻底错了。

**致命结果**：外部审计、前端展示和日志会看到一个 `HTTP_FALLBACK` 标签，实际底下却可能是：

- 千帆成功
- SerpApi 成功
- 浏览器预览成功
- 或 HTTP 成功

你最终根本不知道哪一条 provider 真正救了场，整条审计语义被打烂。

### 优化方案：

#### 1、给路由器补上“结果充足即停止”的预算闸门

- **功能描述**：为聚合器引入全局预算、每 provider 上限和候选水位，一旦高质量候选已足够，就不再继续横扫后面的 provider。
- **业务成效**：降低无意义的搜索 API 调用成本，减少噪声输入。

#### 2、禁止把聚合路由器直接当成“某个运行期阶段”的执行器

- **功能描述**：规划期路由器只负责“聚合多 provider 候选”；运行期若要走 `HTTP`、`BROWSER`、`HEURISTIC` 等阶段，必须绑定到单一、语义明确的执行器。
- **业务成效**：杜绝“阶段名”和“真实执行器”不一致的审计灾难。


### HttpSearchSourceProvider

#### 1、它偷偷绕开了全局模板系统，自己又手写了一套英文 Query

```
private String buildQuery(String competitorName, String scope) {
        return switch (scope) {
            case "DOCS" -> competitorName + " docs documentation help";
            case "PRICING" -> competitorName + " pricing plans";
            case "NEWS" -> competitorName + " blog news changelog product update";
            case "REVIEW" -> competitorName + " review G2 Capterra comparison";
            default -> competitorName + " official website";
        };
}
```

**问题所在**：你明明已经有 `PromptTemplateService + search-queries.yml` 这套统一 Query 策略了，`QianfanSearchSourceProvider` 和 `SerpApiSearchSourceProvider` 也都复用了它；唯独这个类自己偷偷写了一套硬编码英文拼接逻辑。

**致命结果**：你后面去调优 `search-queries.yml` 时，会出现一个极其割裂的系统：

- 千帆生效
- SerpApi 生效
- 浏览器预览生效
- 通用 HTTP Provider 完全不生效

也就是说，你根本没有“一套统一搜索指令策略”，而是至少同时维护着两套。

#### 2、审计字段 `searchEngine` 被错误写成了 endpoint 地址

```
candidates.add(SourceCandidate.builder()
        // ...
        .searchQuery(buildQuery(competitorName, scope))
        .searchEngine(properties.getEndpoint())
        .resultRank(index)
        .selectionStage("PLANNED")
        .build());
```

**问题所在**：`searchEngine` 这个字段从名字到语义，都应该表示稳定的“搜索引擎/渠道标识”。但这里你塞进去的却是完整的 endpoint URL。

**致命结果**：后续前端、报表、审计看见 `searchEngine` 时，拿到的是一串地址，而不是 `http / google / bing / serpapi` 这类稳定维度。历史统计和问题归因会被彻底污染。

#### 3、同一个 scope 只打一条 Query，召回覆盖面天然弱于其他 provider

```
for (String scope : scopes) {
    candidates.addAll(searchScopeWithRetry(competitorName, scope));
}
```

而 `searchScope` 内部只走一条：

```
String query = buildQuery(competitorName, scope);
```

**问题所在**：`Qianfan` 和 `SerpApi` 都会针对一个 scope 打多条 query 变体，这个类却只打单条。

**致命结果**：一旦这唯一一条 query 措辞不佳、语言不匹配、搜索 API 偏好不同，这个 provider 的召回结果就会瞬间塌空，容错极差。

### 优化方案：

#### 1、强制改造为统一走 `PromptTemplateService`

- **功能描述**：废除私有 `buildQuery`，改为统一从模板服务获取 query 列表。
- **业务成效**：一次调整模板，所有 provider 同步生效，彻底结束搜索词策略分叉。

#### 2、把 `searchEngine`、`providerKey`、`endpoint` 三个概念拆开

- **功能描述**：`searchEngine` 记录稳定引擎名，`providerKey` 记录补源渠道，`endpoint` 如有需要单独放元数据。
- **业务成效**：日志、报表和前端展示拿到的是稳定维度，而不是会频繁变化的 URL 字符串。


### QianfanSearchSourceProvider

#### 1、对同一 scope 的所有 Query 一把梭全跑，没有“命中即止”与全局预算

```
private List<SourceCandidate> searchScope(String competitorName, String scope) {
        List<String> queries = promptTemplateService.buildSearchQueries(competitorName, scope, null);
        if (queries.isEmpty()) {
            return List.of();
        }
        List<SourceCandidate> candidates = new ArrayList<>();
        for (String query : queries) {
            candidates.addAll(searchWithRetry(competitorName, scope, query));
        }
        return candidates;
}
```

**问题所在**：这个类没有“只要已经有足够好结果就停”的收手机制。只要模板生成了多条 query，它就全部顺序跑完。

**致命结果**：一方面白白放大外部 API 调用成本；另一方面会把后续低质量 query 命中的噪声结果继续加进候选池，稀释前面高质量 query 的优势。

#### 2、去重只按原始 URL 字符串做，带追踪参数的同页会被当成不同候选

```
private List<SourceCandidate> deduplicateCandidates(List<SourceCandidate> candidates) {
        // ...
        if (!seen.add(candidate.getUrl())) {
            continue;
        }
}
```

**问题所在**：这里只是把 `candidate.getUrl()` 当原始字符串做去重，没有做 URL 归一化。带 `utm`、锚点、尾部斜杠、跳转参数的同一页面，很容易被当成多个不同候选混进来。

**致命结果**：候选池会出现“看起来数量很多，实际上全是同一页变体”的假繁荣，后续排序、验证和正式采集都被浪费。

### 优化方案：

#### 1、引入“命中足够即停”的 Query 预算闸门

- **功能描述**：对同一 scope 设定“已拿到 N 条高质量候选就停止后续 Query”的阈值。
- **业务成效**：减少不必要的 API 调用，把好 Query 的收益最大化。

#### 2、统一接入 URL 归一化去重器

- **功能描述**：按协议、主机、路径归一化后再做去重，必要时剔除常见追踪参数。
- **业务成效**：防止候选池被同一页面的多个变体刷屏。


### SerpApiSearchSourceProvider

#### 1、它和 `QianfanSearchSourceProvider` 一样，也存在“多 Query 全打满”的成本失控问题

```
private List<SourceCandidate> searchScope(String competitorName, String scope) {
        List<String> queries = promptTemplateService.buildSearchQueries(competitorName, scope, null);
        if (queries.isEmpty()) {
            return List.of();
        }
        List<SourceCandidate> candidates = new ArrayList<>();
        for (String query : queries) {
            candidates.addAll(searchWithRetry(competitorName, scope, query));
        }
        return candidates;
}
```

**问题所在**：这条链路没有任何“够了就停”的策略，完全是模板生成多少条，它就全打多少条。

**致命结果**：对于本来就成本更高的海外搜索接口来说，这种“全打满”策略会非常烧钱，而且还会把很多低价值的尾部结果一起引回来。

#### 2、它的去重逻辑和千帆一样，同样只按原始 URL 字符串判重

```
private List<SourceCandidate> deduplicateCandidates(List<SourceCandidate> candidates) {
        // ...
        if (!seen.add(candidate.getUrl())) {
            continue;
        }
}
```

**问题所在**：完全没有做 URL 归一化。

**致命结果**：海外搜索结果里常见的带参数跳转页、跟踪页、重定向落地页，会被重复计数，虚增候选数量。

### 优化方案：

#### 1、为 SerpApi 侧增加更严格的调用预算

- **功能描述**：对单 scope 最多允许执行多少 query、累计拿到多少候选就停，全部做显式限制。
- **业务成效**：控制外部搜索成本，避免低价值长尾 query 烧钱。

#### 2、与千帆侧共用同一套归一化去重组件

- **功能描述**：统一 URL 归一化策略，而不是每个 provider 各自手写一份朴素判重。
- **业务成效**：减少重复候选，提升整体候选池纯度。


### SearchExecutionCoordinator

#### 1、回退顺序里公开支持 `HEURISTIC`，真正执行时却根本没这个分支

```
private List<String> defaultSearchFallbackOrder(String searchMode) {
        if ("HEURISTIC_ONLY".equalsIgnoreCase(searchMode)) {
            return List.of("PLANNED", "HEURISTIC");
        }
        return List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP");
}
```

但执行逻辑只有：

```
for (String stage : resolveSearchFallbackOrder(config)) {
    if ("BROWSER".equals(stage) && browserModeEnabled && !browserExecuted) {
        // ...
    }

    if ("HTTP".equals(stage) && httpModeEnabled && !httpExecuted) {
        // ...
    }
}
```

**问题所在**：这已经不是“功能不完善”，而是**配置层、计划层、运行层三者直接矛盾**。你向外承诺了 `HEURISTIC` 是正式回退阶段，但代码里压根没有任何执行分支。

**致命结果**：用户、前端、日志都以为系统走过启发式回退，实际上根本没有。这种“假能力”会严重误导排障和治理。

#### 2、运行期新增候选会被错误保留成 `PLANNED`，审计阶段语义错乱

```
private List<SourceCandidate> normalizeCandidates(List<SourceCandidate> candidates,
                                                  String stage,
                                                  CollectorNodeConfig config) {
        // ...
        String effectiveStage = StringUtils.hasText(base.getSelectionStage()) ? base.getSelectionStage() : stage;
        String effectiveReason = StringUtils.hasText(base.getSelectionReason())
                ? base.getSelectionReason()
                : ("PLANNED".equals(stage) ? "来自规划期补源候选" : "来自运行期补源候选");
        return base.toBuilder()
                .selectionStage(effectiveStage)
                .selectionReason(effectiveReason)
                .build();
}
```

**问题所在**：这个方法表面上想做的是：“规划期候选打 `PLANNED`，运行期补源候选打 `SUPPLEMENTED` 或其他运行期阶段标记”。但实际逻辑是，只要上游 candidate 已经带了 `selectionStage`，它就一律优先保留。

而 `QianfanSearchSourceProvider`、`SerpApiSearchSourceProvider`、`HttpSearchSourceProvider`、`BrowserPreviewSearchSourceProvider` 在规划期场景里都已经给候选写了 `selectionStage("PLANNED")`。

**致命结果**：这些 provider 一旦被运行期补源复用，新增候选仍然会带着 **`PLANNED`** 身份混进运行期补源结果里。于是前端和审计日志会看到一种荒诞的现场：**明明是运行期新增的来源，却被伪装成规划期早就存在的候选。**

#### 3、恢复检查点只记住了“已选目标”，把“已尝试目标”全部忘光

```
private Map<String, SearchCollectionTarget> resolveAttemptedTargetsFromCheckpoint(SearchAuditSnapshot checkpoint) {
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        if (checkpoint == null || checkpoint.getSelectedTargets() == null || checkpoint.getSelectedTargets().isEmpty()) {
            return attemptedTargets;
        }
        appendAttemptedTargets(attemptedTargets, checkpoint.getSelectedTargets());
        return attemptedTargets;
}
```

**问题所在**：恢复时只用 `selectedTargets` 重建 `attemptedTargets`，意味着所有“曾经验证失败、曾经被阻断、曾经被丢弃”的 URL 现场全部丢失。

**致命结果**：任务一旦恢复，就可能重新去撞同一批早已证明没价值的链接，既浪费资源，又让审计链路断裂。

### 优化方案：

#### 1、把 `HEURISTIC` 要么做成真能力，要么从配置和计划里彻底删掉

- **功能描述**：如果系统支持运行期启发式回退，就补正式分支；如果不支持，就从回退顺序、计划展示和前端语义里移除。
- **业务成效**：消灭“伪能力”，让配置承诺与真实执行一致。

#### 2、运行期补源必须强制覆写阶段标记

- **功能描述**：规划期 provider 输出的 `PLANNED` 语义不能原样沿用到运行期补源结果里，运行期必须统一改写成 `BROWSER / HTTP / SUPPLEMENTED` 等稳定阶段。
- **业务成效**：审计、日志和前端终于能真实区分“早就有的候选”和“后来补出来的候选”。

#### 3、把完整 `attemptedTargets` 纳入检查点快照

- **功能描述**：检查点不只保存 `selectedTargets`，还必须保存所有验证过的尝试目标及其现场状态。
- **业务成效**：恢复执行时能够真正沿着旧现场继续，而不是半失忆重跑。


### BrowserSearchRuntimeService

#### 1、上游 Query 一旦缺失，它会直接退化成“竞品名 + 内部枚举值”

```
private List<String> resolveQueries(CollectorNodeConfig config) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (config.getSearchQueries() != null) {
            config.getSearchQueries().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(queries::add);
        }
        if (queries.isEmpty() && StringUtils.hasText(config.getCompetitorName())) {
            queries.add(config.getCompetitorName() + " " + defaultText(config.getSourceType(), "official"));
        }
        return queries.stream()
                .limit(Math.max(1, resolveMaxSearchesPerTask(config)))
                .toList();
}
```

**问题所在**：这里没有重新回到 `PromptTemplateService` 生成兜底 Query，而是直接拼了一个：

- `Notion OFFICIAL`
- `阿里云 PRICING`
- `Feishu DOCS`

这种只适合程序内部看的“技术枚举检索词”。

**致命结果**：只要规划层有一个节点漏写了 `searchQueries`，浏览器搜索质量就会瞬间塌陷。它不是“稍微差一点”，而是直接从业务查询退化成内部枚举字符串。

#### 2、一个烂 Query 会在“多引擎 × 多重试 × 结果页预览”的重型链路里被成倍放大

```
for (String engineKey : engineSequence) {
    int attempts = Math.max(1, resolveMaxRetries(config) + 1);
    for (int attempt = 1; attempt <= attempts; attempt++) {
        SearchAttemptResult result = searchOnce(
                runtimeBrowser,
                config,
                query,
                browserTraceId,
                openedResultPages,
                engineKey
        );
        // ...
    }
}
```

以及：

```
page.navigate(buildSearchUrl(engineKey, query), ...);
// ...
candidates = enrichTopResultPages(browserContext, candidates, config, openedResultPages);
```

**【深度原因分析】**

浏览器运行时这条链路本身极重：每个 Query 都可能叠加“多引擎 + 多次重试 + 打开结果页预览”。所以一旦 Query 本身就烂、就模糊、就带技术枚举味，你不是只浪费一次搜索，而是在一整条高成本浏览器流水线里**成倍放大坏 Query 的伤害**。

最终结果就是：

- 浏览器资源被垃圾搜索页吞掉
- 打开了更多低价值结果页
- 被搜索引擎、被竞品站点拦截的概率继续升高

### 优化方案：

#### 1、运行期兜底 Query 也必须回归统一模板体系

- **功能描述**：`resolveQueries` 在发现配置中没有 Query 时，应当回调 `PromptTemplateService` 动态生成，而不是直接拼接 `competitorName + sourceType`。
- **业务成效**：即使规划层偶发漏写 Query，运行期也能用一套业务可用的方式自我修复。

#### 2、在进入浏览器重型链路前增加 Query 质量闸门

- **功能描述**：对过短、重复、带内部枚举值、缺乏业务特征词的 Query 做预校验，不合格的不允许直接进入多引擎浏览器搜索。
- **业务成效**：把坏 Query 挡在浏览器大成本链路前，降低浪费与反爬风险。


### SearchProviderProperties

#### 1、`browserPreviewEnabled` 这个开关名和真实调度行为不一致，配置语义会误导人

```
/**
 * 搜索式补源配置。
 * 如果开启 browserPreviewEnabled，则规划期会优先走浏览器预览补源，否则直接走 HTTP 搜索适配层。
 */
public class SearchProviderProperties {

    private List<String> providerOrder = List.of("qianfan", "serpapi", "browserPreview", "http");

    /** true 表示规划期优先走浏览器预览补源。 */
    private boolean browserPreviewEnabled = false;
}
```

**问题所在**：这个类的注释和字段命名都在向使用者传达一个非常明确的语义: `browserPreviewEnabled=true` 代表“优先走浏览器预览补源”。但真实情况并不是这样。默认 `providerOrder` 明明还是 `qianfan -> serpapi -> browserPreview -> http`，也就是说即便把这个开关打开，浏览器预览也只是“允许参与”，并没有真的获得优先级。

**致命结果**：配置使用者会以为自己已经把规划期切到了浏览器优先模式，实际上系统仍然会先把千帆和 SerpApi 跑一遍，浏览器预览只是后排补位。这会直接误导搜索质量调优和成本排查。

#### 2、Provider 路由配置做了“读时小写化”，却没有“写时标准化”，YAML 很容易静默失效

```
public ProviderRouteProperties resolveProvider(String providerKey) {
        if (providerKey == null) {
            return null;
        }
        return providers.get(providerKey.trim().toLowerCase(Locale.ROOT));
}
```

**问题所在**：这里读取配置时，会强行把 `providerKey` 转成小写再去 `providers` 里取值；但 `providers` 这个 `Map<String, ProviderRouteProperties>` 自己并没有在装载时做统一小写化。

**致命结果**：只要配置文件里有人按自然习惯写成：

- `browserPreview`
- `serpApi`

这种驼峰式 key，运行时就可能根本取不到对应配置，最后表现成“我明明配了 enabled/failOpen，为什么完全没生效”。更糟的是，这种失效是**静默的**，没有任何显式报错。

#### 3、同一层配置同时管“路由顺序”“Provider 开关”“浏览器预览总开关”，控制面发生重叠打架

```
private List<String> providerOrder = List.of("qianfan", "serpapi", "browserPreview", "http");
private Map<String, ProviderRouteProperties> providers = new LinkedHashMap<>();
private boolean browserPreviewEnabled = false;
```

**问题所在**：这个类把三类不同性质的控制项揉在了一起：

- `providerOrder` 管顺序
- `providers` 管每个 Provider 的启停与 fail-open
- `browserPreviewEnabled` 又单独管浏览器预览可用性

这相当于浏览器预览同时受到“两套半”开关影响。

**致命结果**：一旦配置出现下面这种组合：

- `browserPreviewEnabled=true`
- `providers.browserpreview.enabled=false`
- `providerOrder` 里还保留 `browserPreview`

系统语义就会变得非常混乱。日志、文档、配置说明各说各话，后续排障成本会很高。

### 优化方案：

#### 1、把 `browserPreviewEnabled` 从“优先级语义”改成“可用性语义”，或者直接并入 `providers.browserpreview.enabled`

- **功能描述**：不要再让单个布尔字段承担“是否优先”“是否启用”两层含义。要么把它改名为 `browserPreviewAvailable`，明确只是能力开关；要么彻底删掉，统一走 `providers.browserpreview.enabled`。
- **业务成效**：配置语义会变得单一且可推理，用户不再误判规划期真实执行顺序。

#### 2、对 `providers` 的 key 做装载期标准化，并在未知 key 场景输出告警

- **功能描述**：配置绑定完成后，把 `providers` map 全量 lower-case 化；如果用户写了未识别的 provider key，启动时直接告警。
- **业务成效**：避免“配置写了但没生效”的静默事故。

#### 3、拆分控制平面：顺序、启停、预算分别独立

- **功能描述**：把“路由顺序”“渠道启停”“预算上限”拆成三组配置，而不是继续堆在一个 properties 类里互相覆盖。
- **业务成效**：调度配置更清晰，后续扩展更多 provider 时不会失控。


### SearchSourceProviderDescriptor

#### 1、`defaultEnabled=true` 和 `defaultFailOpen=true` 过于激进，新 Provider 一接入就默认进生产

```
@Builder.Default
boolean defaultEnabled = true;

@Builder.Default
boolean defaultFailOpen = true;
```

**问题所在**：这个描述符本来是“声明式元数据中心”，但它把两个最危险的默认值都设成了 `true`：

- 默认启用
- 默认失败后继续放行

**致命结果**：只要后续有人新增一个实验性 Provider，哪怕还没有经过充分治理，只要 Bean 注册进来、descriptor 配好了，它就会默认参与生产路由；即便执行失败，系统也会悄悄吞掉异常继续往后走。结果就是问题被掩盖，质量波动又难以定位。

#### 2、`capabilities` 看起来很高级，实际上目前完全是“展示型元数据”

```
@Builder.Default
List<String> capabilities = List.of();
```

**问题所在**：这个字段被设计成 Provider 能力标签，比如：

- `WEB_SEARCH`
- `CHINESE_RESULTS`
- `GLOBAL_RESULTS`
- `BROWSER_PREVIEW`

但从当前工程引用关系来看，这些标签并没有真正参与路由决策、预算分配、Query 选择或回退判断。

**致命结果**：系统表面上像是在做“能力驱动路由”，实际上仍然是纯顺序串扫。这会让后续维护者错误高估当前架构的智能程度，误以为“配置能力标签就会自动影响调度”。

#### 3、`providerKey` 缺少统一约束，重复 key 或近义 key 会在路由器里被悄悄覆盖

```
String providerKey;
```

**问题所在**：`providerKey` 是整个路由系统的稳定标识，但这里没有枚举、没有注册表、没有重复校验，全靠每个 Provider 各自手写字符串。

**致命结果**：一旦未来出现：

- 命名不统一：`browserpreview` / `browserPreview`
- 语义重复：两个 Provider 不小心用了同一个 key

路由器建索引时就可能发生覆盖，而不是显式失败。最终现场会表现成“有一个 Provider 神秘消失了”。

### 优化方案：

#### 1、把默认策略从“默认上线”改成“默认保守”

- **功能描述**：将 `defaultEnabled` 默认值改为 `false`，或者至少要求新增 Provider 必须显式在配置中开启；`defaultFailOpen` 也应按渠道风险分级，而不是全员放行。
- **业务成效**：避免实验性补源渠道未治理完就直接流入正式链路。

#### 2、要么让 `capabilities` 真正参与调度，要么删掉这层伪语义

- **功能描述**：如果保留能力标签，就必须让路由器、规划器或预算器根据标签做真实决策；如果短期做不到，就不要把它包装成架构能力。
- **业务成效**：减少“文档很高级、代码很朴素”的认知落差。

#### 3、建立 `providerKey` 注册表和启动期冲突检查

- **功能描述**：统一使用常量或枚举声明 providerKey；启动时发现重复 key 直接失败，而不是静默覆盖。
- **业务成效**：保证路由身份稳定，避免后续扩展时埋下不可见冲突。


### SearchBrowserProperties

#### 1、默认引擎链路先天偏向低质量结果源，把噪声入口直接写进了全局配置

```
private boolean enabled = true;
private String engine = "baidu";
private List<String> fallbackEngines = List.of("bing");
```

**问题所在**：这里把默认浏览器搜索主引擎设成了 `baidu`，回退引擎设成了 `bing`。这和前面 `SearchEngineProperties` 中暴露出的现实问题完全一致：默认链路天然优先落在广告密度高、首页营销页占比大的引擎上。

**致命结果**：即使业务方完全没有配错任何东西，只用了系统默认值，搜索补源的第一落点也很可能是营销页、聚合页、活动页，而不是真正的技术文档和计费公告。

#### 2、反爬阻断信号词全是英文，但默认主引擎却是中文生态，识别体系明显失配

```
private List<String> blockedSignals = List.of(
        "captcha",
        "unusual traffic",
        "verify you are human",
        "access denied",
        "robot check"
);
```

**问题所在**：这些阻断词只覆盖英文页面提示，但默认主引擎是百度，国内站点和中文搜索结果页常见的拦截文案通常是：

- 验证码
- 访问受限
- 安全验证
- 异常流量

**致命结果**：当浏览器已经明显被中文搜索引擎风控拦住时，系统可能完全识别不出来，继续把一个已经被污染的结果页当正常页面处理，造成错误重试和资源浪费。

#### 3、这个类暴露了很多可调参数，但规划期写入节点配置时并没有完整兑现，出现“假配置”现象

`SearchBrowserProperties` 暴露了：

```
private int maxRetries = 2;
private long minIntervalMillis = 3000L;
private int maxSearchesPerTask = 10;
private List<String> blockedSignals = List.of(...);
```

但 `WorkflowFactory` 在构造运行期策略时写死成了：

```
return SearchRuntimePolicy.builder()
        .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
        .maxRetries(2)
        .minIntervalMillis(3000L)
        .maxSearchesPerTask(10)
        // ...
        .blockedSignals(List.of(
                "captcha",
                "unusual traffic",
                "verify you are human",
                "access denied",
                "robot check"
        ))
        .build();
```

**问题所在**：这意味着你在 `SearchBrowserProperties` 里配的很多值，并不会稳定下发到真正执行节点里。

**致命结果**：表面上系统提供了浏览器搜索的精细化配置能力，实际上规划器偷偷回填了一套硬编码默认值。运维和研发在配置文件里调了半天，很可能根本没生效。

### 优化方案：

#### 1、重置默认引擎策略，不要把“广告重灾区”写成默认值

- **功能描述**：默认主引擎和回退引擎应改为更适合技术资料检索的组合，并允许按地区、任务类型或竞品语言动态选择。
- **业务成效**：即使零配置启动，也不会天然把用户送进低质量搜索入口。

#### 2、补齐中文反爬阻断词，并区分搜索引擎维度加载

- **功能描述**：针对百度、必应、Google 等不同引擎维护不同阻断信号词模板，而不是只放一份英文全局列表。
- **业务成效**：浏览器风控识别更准确，避免误判和无效重试。

#### 3、让 `SearchBrowserProperties` 真正成为单一事实来源

- **功能描述**：规划期生成 `SearchRuntimePolicy` 时，优先继承 `SearchBrowserProperties` 的真实配置，再允许节点级覆盖。
- **业务成效**：配置文件、节点配置、运行行为三者终于一致。


### SearchRuntimeFallbackPolicy

#### 1、回退摘要文案是写死的，完全不看真实回退顺序，容易对外“说假话”

```
public String buildSearchFallbackSummary(String failureCode, String detail) {
        String suffix = StringUtils.hasText(detail) ? "，原因：" + detail : "";
        return switch (normalizeCode(failureCode)) {
            case "browser_unavailable" -> "浏览器实例不可用，已回退到 HTTP/规划候选链路" + suffix;
            case "search_timeout" -> "浏览器搜索执行超时，已回退到 HTTP/规划候选链路" + suffix;
            case "blocked" -> "浏览器搜索疑似触发反爬或访问受限，已回退到 HTTP/规划候选链路" + suffix;
            default -> "浏览器搜索运行时失败，已回退到 HTTP/规划候选链路" + suffix;
        };
}
```

**问题所在**：这里不管当前节点实际配置的是：

- `PLANNED -> BROWSER`
- `PLANNED -> HTTP`
- `PLANNED -> BROWSER -> HEURISTIC -> HTTP`

它最后统一对外说“已回退到 HTTP/规划候选链路”。

**致命结果**：前端、日志、审计看到的是一套固定文案，真实执行走的是另一套动态回退顺序。这种静态文案会在排障时制造严重误导。

#### 2、异常分类严重依赖字符串匹配，脆弱到几乎经不起底层库升级

```
public String classifyRuntimeFailure(Throwable error) {
        String normalized = error == null || error.getMessage() == null
                ? ""
                : error.getMessage().toLowerCase(Locale.ROOT);
        if (normalized.contains("timeout")) {
            return "search_timeout";
        }
        if (normalized.contains("browser has been closed")
                || normalized.contains("target page, context or browser has been closed")
                || normalized.contains("playwright connection closed")
                || normalized.contains("connection closed")
                || normalized.contains("__adopt__")
                || normalized.contains("cannot find object")) {
            return "browser_unavailable";
        }
        return "runtime_failure";
}
```

**问题所在**：它没有基于异常类型、没有基于结构化错误码，而是纯靠英文错误消息关键字做包含判断。

**致命结果**：只要 Playwright、浏览器驱动、HTTP 客户端、甚至 JVM 底层错误文本稍有变化，这个分类器就可能立刻失真。到时你不是“分类不够准”，而是整个回退文案、前端提示、审计归因都可能一起跑偏。

#### 3、`blocked` 这个失败码在摘要文案里存在，但不属于统一异常分类产物，治理链条断裂

```
case "blocked" -> "浏览器搜索疑似触发反爬或访问受限，已回退到 HTTP/规划候选链路" + suffix;
```

**问题所在**：这个类宣称自己“统一收口浏览器不可用、搜索超时、单页采集失败等场景下的降级开关与诊断文案”，但 `blocked` 并不是 `classifyRuntimeFailure` 的正式产物，而是由别处绕道传进来的字面码。

**致命结果**：失败码来源不统一，意味着：

- 一部分错误来自异常文本分类
- 一部分错误来自页面内容识别
- 一部分错误可能来自调用方手工拼接

最终这个“统一策略中心”并没有真正统一。

### 优化方案：

#### 1、回退摘要必须根据真实执行计划动态生成

- **功能描述**：将当前节点的 `searchFallbackOrder`、已执行阶段、最终命中阶段一并传入策略层，再生成对外摘要。
- **业务成效**：前端、日志、审计看到的回退说明才会与真实现场一致。

#### 2、改用结构化失败码，不要继续依赖异常消息文本猜测

- **功能描述**：为浏览器不可用、搜索超时、反爬阻断、页面采集失败定义明确错误码，底层抛出时直接携带，而不是在策略层二次猜。
- **业务成效**：分类结果稳定，可测试、可演进，不会被底层文案变化轻易打穿。

#### 3、把 `blocked` 纳入同一套正式失败码体系

- **功能描述**：反爬阻断也应通过统一的失败码协议回传，而不是在不同链路里各自拼字符串。
- **业务成效**：真正形成可闭环的浏览器搜索治理链路。


### SourceCandidateRanker

#### 1、相关度和质量分的默认推断天然抬举官网/商业域名，广告首页会被先天加分

```
private double inferRelevance(SourceCandidate candidate) {
        String sourceType = candidate.getSourceType() == null ? "" : candidate.getSourceType();
        return switch (sourceType) {
            case "OFFICIAL" -> 0.95;
            case "DOCS" -> 0.90;
            case "PRICING" -> 0.92;
            case "NEWS" -> 0.78;
            case "REVIEW" -> 0.80;
            default -> 0.70;
        };
}
```

以及：

```
private double inferQuality(String domain, String discoveryMethod) {
        if (!StringUtils.hasText(domain)) {
            return "SEARCH".equalsIgnoreCase(discoveryMethod) ? 0.72 : 0.68;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        if (normalized.contains("g2.com") || normalized.contains("capterra.com")) {
            return 0.86;
        }
        if (normalized.startsWith("docs.") || normalized.contains("help") || normalized.contains("support")) {
            return 0.90;
        }
        if (normalized.contains(".com") || normalized.contains(".ai") || normalized.contains(".io")) {
            return 0.88;
        }
        return 0.70;
}
```

**问题所在**：只要候选被标成 `OFFICIAL`，默认相关度就是 `0.95`；只要域名是普通商业站点 `.com/.ai/.io`，质量分又给到 `0.88`。这两个默认值一叠加，官网营销首页几乎天生就是高分选手。

**致命结果**：真正的技术文档、计费细则、帮助中心页面，哪怕信息价值更高，只要上游没有显式打出更好的内容信号，也很容易在总分上输给一个品牌官网首页或活动入口页。

#### 2、重复 URL 取胜规则完全不看 `verified` 状态，运行期验证过的真干货可能输给规划期高分噪声

```
private SourceCandidate preferCandidate(SourceCandidate existing, SourceCandidate incoming) {
        int scoreCompare = Double.compare(incoming.getTotalScore(), existing.getTotalScore());
        if (scoreCompare > 0) {
            return annotateDuplicateWinner(incoming);
        }
        if (scoreCompare < 0) {
            return existing;
        }

        int publishedAtCompare = comparePublishedAt(incoming.getPublishedAt(), existing.getPublishedAt());
        if (publishedAtCompare > 0) {
            return annotateDuplicateWinner(incoming);
        }
        if (publishedAtCompare < 0) {
            return existing;
        }

        int discoveryCompare = Integer.compare(discoveryPriority(incoming.getDiscoveryMethod()),
                discoveryPriority(existing.getDiscoveryMethod()));
        if (discoveryCompare > 0) {
            return annotateDuplicateWinner(incoming);
        }
        return existing;
}
```

**问题所在**：这里决胜只看三件事：

- 总分
- 发布时间
- 发现方式

但完全不看：

- 是否已运行期验证通过
- 是否命中了核心信号词
- 是否有明确的反营销阻断信息

**致命结果**：一个规划期的高分首页候选，只要总分和时间稍微占优，就可能把一个运行期已经验证通过、明显更适合正式采集的真实干货 URL 压下去。

#### 3、URL 规范化仍然过于粗糙，`http/https`、`www` 等同页变体仍可能漏过去重

```
private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
            String host = StringUtils.hasText(uri.getHost()) ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "" : uri.getPath().replaceAll("/+$", "");
            return scheme + "://" + host + path;
        } catch (Exception e) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
}
```

**问题所在**：虽然这里已经移除了查询参数和尾斜杠，但它仍然把 `scheme` 和原始 `host` 完整保留下来。

**致命结果**：下面这些很常见的同页变体，仍然可能被当成两个不同候选：

- `http://example.com/docs`
- `https://example.com/docs`
- `https://www.example.com/docs`
- `https://example.com/docs`

这会让候选池出现“重复页面伪装成多条来源”的假繁荣。

### 优化方案：

#### 1、把默认评分从“域名优先”改成“内容信号优先”

- **功能描述**：降低 `OFFICIAL` 和普通商业域名的先天高权重，把“是否命中文档/价格/更新信号词”“是否通过运行期验证”纳入默认评分核心。
- **业务成效**：广告首页不再因为出身好就自动高分，真正有信息密度的页面更容易上位。

#### 2、重复候选仲裁必须显式引入 `verified` 与 `matchedSignals`

- **功能描述**：在 `preferCandidate` 决胜时，把运行期验证结果和核心信号命中情况放在总分之前或至少并列考虑。
- **业务成效**：让运行期真正证明过价值的来源，在重复冲突里拥有优先权。

#### 3、升级 URL 归一化规则

- **功能描述**：补齐 `http/https` 合并、`www` 折叠、常见追踪参数清洗、已知重定向宿主归并等规则。
- **业务成效**：减少同页变体刷屏，提升候选池真实纯度。


### WorkflowFactory

#### 1、规划器会把根本跑不通的 `HEURISTIC` 回退阶段正式写进节点配置，制造“假计划”

```
private List<String> buildSearchFallbackOrder() {
        return switch (resolveSearchMode()) {
            case "BROWSER_ONLY" -> List.of("PLANNED", "BROWSER");
            case "HTTP_ONLY" -> List.of("PLANNED", "HTTP");
            case "HEURISTIC_ONLY" -> List.of("PLANNED", "HEURISTIC");
            default -> List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP");
        };
}
```

**问题所在**：这个工厂是规划入口，它把 `HEURISTIC` 当成正式回退阶段写进了每个采集节点的 `nodeConfig`。但前面已经看到，运行时协调器压根没有兑现这条分支。

**致命结果**：计划层、节点配置层、执行层当场分裂。用户在创建任务时看到的是一套“支持启发式回退”的计划，真正运行时却根本不会发生。

#### 2、执行计划里永远写着 `BROWSER_SUPPLEMENT_SEARCH`，哪怕当前模式根本不可能跑浏览器

```
private SearchExecutionPlan buildDefaultSearchExecutionPlan() {
        return SearchExecutionPlan.builder()
                .stage("COLLECTOR_SEARCH_AND_COLLECT")
                .steps(List.of(
                        step("LOAD_CANDIDATES", "读取规划期候选来源", 500, "nodeConfig"),
                        step("VERIFY_TOP_CANDIDATES", "验证高优先级候选来源是否可用", 5000, "browser"),
                        step("BROWSER_SUPPLEMENT_SEARCH", "候选不足时执行浏览器增补搜索", 8000, "searchEngine"),
                        step("SELECT_TARGETS", "合并候选并选出最终采集目标", 1000, "ranker"),
                        step("COLLECT_PAGES", "抓取页面正文并持久化证据", 12000, "collector")
                ))
                .build();
}
```

**问题所在**：这个执行计划完全是硬编码模板，没有根据 `searchMode`、`search.browser.enabled`、当前 Provider 组合动态裁剪步骤。

**致命结果**：前端进度展示、日志说明、任务计划可视化都会出现浏览器步骤，哪怕当前任务根本只允许 `HTTP_ONLY`。这正好违反了你在 `AGENTS.md` 里强调的“计划与进度必须真实、可追溯”原则。

#### 3、运行期策略在规划阶段被硬编码回填，很多浏览器配置根本传不下去

```
private SearchRuntimePolicy buildDefaultSearchRuntimePolicy() {
        // ...
        return SearchRuntimePolicy.builder()
                .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
                .maxRetries(2)
                .minIntervalMillis(3000L)
                .maxSearchesPerTask(10)
                .pageTimeoutMillis(Math.max(1000, collectorProperties.getPageTimeoutSeconds() * 1000))
                .maxOpenResultPages(searchBrowserProperties.getMaxOpenResultPages())
                .resultPageTimeoutMillis(searchBrowserProperties.getResultPageTimeoutMillis())
                .maxContentLengthPerPage(searchBrowserProperties.getMaxContentLengthPerPage())
                .userAgents(defaultUserAgents)
                .blockedSignals(List.of(
                        "captcha",
                        "unusual traffic",
                        "verify you are human",
                        "access denied",
                        "robot check"
                ))
                .recoveryHint("如搜索中断，优先从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查。")
                .build();
}
```

**问题所在**：这里并没有完整继承 `SearchBrowserProperties`，而是自己又手写了一套“默认运行策略”。

**致命结果**：规划器生成出来的节点配置，和全局搜索浏览器配置之间会出现漂移。你在 `application.yml` 里调了一堆运行时参数，不代表任务节点真的会带着这些参数去执行。

#### 4、`maxSearchResults` 被规划期已有 URL 数量反向卡死，补源能力会被提前锁住上限

```
private int resolveMaxSearchResults(SourcePlan sourcePlan) {
        int configuredLimit = collectorProperties == null ? 5 : Math.max(1, collectorProperties.getMaxPagesPerCompetitor());
        int plannedUrlCount = sourcePlan.getUrls() == null ? 0 : sourcePlan.getUrls().size();
        if (plannedUrlCount <= 0) {
            return configuredLimit;
        }
        return Math.min(configuredLimit, plannedUrlCount);
}
```

**问题所在**：一旦规划期已经带了若干 URL，这里就直接把运行期 `maxSearchResults` 限制成“不超过当前已有 URL 数量”。

**致命结果**：例如某个节点当前只有 1 个低质量官网首页 URL，运行期本来应该继续搜索更多候选补进来；但由于这里把上限锁成了 `1`，整个补源空间被规划器预先掐死了。

### 优化方案：

#### 1、规划器只能写“真实可执行”的回退顺序与执行步骤

- **功能描述**：在 `WorkflowFactory` 里绑定真实运行能力，只生成当前系统能够兑现的 fallback order 和 execution steps。
- **业务成效**：任务创建页、节点配置、运行时现场三者保持一致，不再出现“计划有、执行没有”的伪能力。

#### 2、执行计划按模式动态裁剪

- **功能描述**：`HTTP_ONLY` 就不写浏览器步骤，`BROWSER_ONLY` 就不写 HTTP 兜底，`HEURISTIC` 只有在真正实现后才允许进入计划。
- **业务成效**：进度可视化终于可信，用户能够根据计划真实判断任务行为。

#### 3、节点运行策略应继承全局配置，再允许节点级覆盖

- **功能描述**：先把 `SearchBrowserProperties` 完整映射进 `SearchRuntimePolicy`，再对个别节点做差异化覆写，而不是反过来用硬编码覆盖全局配置。
- **业务成效**：配置生效路径清晰，调优成本显著下降。

#### 4、把“已有 URL 数量”和“允许搜索多少候选”两个概念彻底解耦

- **功能描述**：`maxSearchResults` 应根据任务预算、模式和质量阈值决定，而不是简单绑定到规划期已有 URL 数量。
- **业务成效**：运行期补源不会被规划期的贫瘠现场提前锁死。


### SearchProperties

#### 1、全局搜索模式只用一个裸字符串承载，缺乏枚举约束与非法值校验

```
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    /**
     * 搜索模式：
     * HEURISTIC_ONLY / HTTP_ONLY / BROWSER_ONLY / HYBRID
     */
    private String mode = "HYBRID";
}
```

**问题所在**：`SearchProperties` 整个全局搜索配置，现在只有一个 `String mode`。它没有枚举、没有白名单校验、没有启动期合法性检查。

**致命结果**：只要配置文件里写错一个字符，例如：

- `BROSWER_ONLY`
- `HBRID`

系统也不会第一时间失败，而是把这个错误字符串继续往下游传。最终表现出来的往往不是“配置错误”，而是“回退行为异常”“计划展示怪异”“某些阶段根本没执行”，排障非常痛苦。

#### 2、`search.mode` 看似是全局真相，实际上运行期会被别的配置偷偷改写

`WorkflowFactory` 里实际是这样处理的：

```
private String resolveSearchMode() {
        String configuredMode = searchProperties == null ? null : searchProperties.getMode();
        String normalizedMode = StringUtils.hasText(configuredMode)
                ? configuredMode.trim().toUpperCase(java.util.Locale.ROOT)
                : "HYBRID";
        if (!searchBrowserProperties.isEnabled() && "HYBRID".equals(normalizedMode)) {
            return "HTTP_ONLY";
        }
        if (!searchBrowserProperties.isEnabled() && "BROWSER_ONLY".equals(normalizedMode)) {
            return "HTTP_ONLY";
        }
        return normalizedMode;
}
```

**问题所在**：`SearchProperties.mode` 表面上是全局配置中心，但真正进入节点前，还会被 `SearchBrowserProperties.enabled` 二次改写。

**致命结果**：你在配置里写的是 `HYBRID` 或 `BROWSER_ONLY`，任务节点最终却可能被落成 `HTTP_ONLY`。如果前端展示、日志打印、文档说明不是同一时刻同一口径取值，就会出现“我明明配置了浏览器模式，为什么执行计划里却是 HTTP_ONLY”的语义分裂。

#### 3、一个全局模式字段同时试图描述规划期、运行期和预览期，抽象层级不对

例如浏览器预览 Provider 会自己强制写：

```
return CollectorNodeConfig.builder()
        .competitorName(competitorName)
        .sourceType(scope)
        .searchMode("BROWSER_ONLY")
        .browserSearchEnabled(Boolean.TRUE)
        // ...
        .build();
```

**问题所在**：`search.mode` 本来应该描述“正式采集节点的运行模式”；但现在预览期 Provider 也在单独覆盖它，规划期路由器又有自己的一套 Provider 顺序和开关。

**致命结果**：同一个“搜索模式”概念，在不同层里已经变成了三套东西：

- 全局配置模式
- 节点运行模式
- 预览期强制模式

这会让整个搜索链路的行为很难通过一个字段推断出来。

### 优化方案：

#### 1、把 `mode` 改成强类型枚举，并在启动期做严格校验

- **功能描述**：用 `enum SearchMode` 替代裸字符串；配置非法值时直接失败启动，而不是让错误延后到执行期爆炸。
- **业务成效**：搜索模式配置会变成“可验证的契约”，而不是纯文本约定。

#### 2、明确“配置值”和“生效值”两个概念

- **功能描述**：保留原始配置模式，同时在规划期输出一个 `effectiveSearchMode`，明确记录经过浏览器开关裁剪后的真实模式。
- **业务成效**：前端、日志、任务计划展示都能基于“真实生效值”工作，减少歧义。

#### 3、拆分全局模式、规划预览模式、运行期补源模式

- **功能描述**：不要再试图用一个字段覆盖所有阶段，应分别建模。
- **业务成效**：搜索链路的职责边界更清晰，后续扩展也不会继续缠绕。


### SearchRuntimePolicy

#### 1、这个类名叫“运行期策略”，但当前更像一份“半空壳 DTO”，默认值分散在各个调用方

```
public class SearchRuntimePolicy {

    private Boolean verifyResultPage;
    private Integer maxRetries;
    private Long minIntervalMillis;
    private Integer maxSearchesPerTask;
    private Integer pageTimeoutMillis;
    private Integer maxOpenResultPages;
    private Integer resultPageTimeoutMillis;
    private Integer maxContentLengthPerPage;
    private List<String> userAgents;
    private List<String> blockedSignals;
    private Boolean continueOnBrowserUnavailable;
    private Boolean continueOnSearchTimeout;
    private Boolean continueOnPageCollectFailure;
    private Boolean recoverPartialContentOnTimeout;
    private String recoveryHint;
}
```

而 `SearchExecutionCoordinator` 的兜底又是：

```
private SearchRuntimePolicy resolveRuntimePolicy(CollectorNodeConfig config) {
        SearchRuntimePolicy existing = config.getSearchRuntimePolicy();
        if (existing != null) {
            return existing;
        }
        return SearchRuntimePolicy.builder()
                .recoveryHint("建议从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查")
                .build();
}
```

**问题所在**：这个类本应成为“运行时策略单一事实来源”，但它本身没有默认值，也没有标准化动作。很多字段为空时，到底使用：

- `SearchBrowserProperties`
- `WorkflowFactory` 的硬编码
- `BrowserPreviewSearchSourceProvider` 的硬编码
- `SearchExecutionCoordinator` 的极简兜底

完全取决于调用路径。

**致命结果**：同样是一个 `SearchRuntimePolicy` 对象，只是换一条执行链路，实际生效的默认行为就可能不同。这个类名很强，契约能力却很弱。

#### 2、`verifyResultPage` 在顶层配置和策略对象里重复定义，优先级分裂

`CollectorNodeConfig` 顶层有：

```
private Boolean verifyResultPage;
```

`SearchRuntimePolicy` 里又有：

```
private Boolean verifyResultPage;
```

真正消费时是：

```
private boolean isResultPageVerificationEnabled(CollectorNodeConfig config) {
        if (config.getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getVerifyResultPage());
        }
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getSearchRuntimePolicy().getVerifyResultPage());
        }
        return true;
}
```

**问题所在**：这里已经不是“字段多放一层方便使用”，而是**同一语义被定义了两次，并且还存在优先级覆盖**。

**致命结果**：节点配置、前端回显、恢复执行、运行时调试时，你很难一眼判断“最终到底是不是要校验结果页”。这类双写字段非常容易埋出配置漂移问题。

#### 3、`recoveryHint` 是自由文本，和真实计划步骤强耦合但又没有结构化约束

例如：

```
private String recoveryHint;
```

以及多处写死：

```
"建议从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查"
```

**问题所在**：恢复建议本质上引用的是执行计划里的步骤编码，但这里把它存成了自由文本。

**致命结果**：只要后续步骤名调整、阶段拆分、计划裁剪，`recoveryHint` 就会立刻过时，最终对用户展示出一条“指向不存在步骤”的恢复建议。

### 优化方案：

#### 1、把 `SearchRuntimePolicy` 变成“已解析完成”的不可变策略对象

- **功能描述**：在进入运行期前，把所有 null 字段都补成确定值，再下发给执行器。
- **业务成效**：不同链路看到的是同一份完整策略，不再四处分散找默认值。

#### 2、去掉与顶层配置重复的字段，或者明确只允许单向覆盖

- **功能描述**：像 `verifyResultPage` 这类字段，只保留一层；如果必须保留两层，就必须输出明确覆盖链路。
- **业务成效**：减少配置分裂和歧义。

#### 3、把恢复提示从自由文本改成结构化恢复协议

- **功能描述**：使用 `recoveryStepCode`、`recoveryReasonCode`、`recoveryMessage` 这类结构化字段，而不是一整句字符串。
- **业务成效**：恢复建议可验证、可国际化、可随着执行计划稳定演进。


### BrowserPreviewSearchSourceProvider

#### 1、预览期自己强制造了一套“迷你浏览器运行策略”，和正式执行链路并不一致

```
private CollectorNodeConfig buildPreviewConfig(String competitorName, String scope) {
        return CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .sourceType(scope)
                .searchMode("BROWSER_ONLY")
                .browserSearchEnabled(Boolean.TRUE)
                .verifyResultPage(Boolean.FALSE)
                .searchQueries(buildQueries(competitorName, scope))
                .maxSearchResults(Math.max(1, properties.getResultsPerScope()))
                .searchRuntimePolicy(SearchRuntimePolicy.builder()
                        .verifyResultPage(Boolean.FALSE)
                        .maxRetries(1)
                        .minIntervalMillis(1000L)
                        .maxSearchesPerTask(2)
                        .pageTimeoutMillis(Math.max(1000, collectorProperties.getPageTimeoutSeconds() * 1000))
                        .maxOpenResultPages(1)
                        .userAgents(StringUtils.hasText(collectorProperties.getUserAgent())
                                ? List.of(collectorProperties.getUserAgent())
                                : List.of())
                        .recoveryHint("浏览器预览补源失败时可回退到 HTTP 或启发式候选。")
                        .build())
                .build();
}
```

**问题所在**：这个 Provider 没有复用正式节点的搜索模式和运行策略，而是自己硬编码出一份“预览专用迷你版”。

**致命结果**：创建页看到的浏览器预览效果，并不是正式任务运行时会看到的真实效果。你可能在预览里只跑了 2 条 Query、只开了 1 个结果页、只重试 1 次，而正式执行时却是完全不同的一套预算和容错策略。

#### 2、预览 Query 生成时永远丢掉 `domainHint`，官网域内检索能力在这里直接退化

```
private List<String> buildQueries(String competitorName, String scope) {
        return promptTemplateService.buildSearchQueries(competitorName, scope, null);
}
```

**问题所在**：这里调用模板服务时，第三个参数永远写死为 `null`。这意味着像 `site:{domainHint}` 这种域内约束 Query，在浏览器预览阶段根本打不出来。

**致命结果**：预览期更容易搜到泛网页、聚合页、营销页，而不是官网域内真正有价值的文档和价格页面。于是用户在创建任务时看到的是一套偏噪声的预览现场。

#### 3、预览失败和“没有搜到结果”被混成一种现场，丢失诊断语义

```
BrowserSearchRuntimeResult previewResult = browserSearchRuntimeService.search(previewConfig);
if (previewResult.getCandidates().isEmpty()) {
    log.debug("browser preview search returned no candidates, competitor={}, scope={}, summary={}",
            competitorName, scope, previewResult.getSummary());
    continue;
}
```

**问题所在**：这里只要候选为空，就统一 `continue`。它不会把“浏览器不可用”“疑似被反爬拦截”“Query 质量太差”“确实没有结果”区分开来。

**致命结果**：创建页预览很可能安静地看起来“只是没结果”，但真实原因其实是浏览器挂了、被封了或者 Query 有问题。用户和研发都会被误导。

#### 4、预览结果被统一打回 `PLANNED`，预览现场的重要执行语义被抹平

```
private List<SourceCandidate> normalizePreviewCandidates(List<SourceCandidate> previewCandidates) {
        return previewCandidates.stream()
                .map(candidate -> candidate.toBuilder()
                        .discoveryMethod("BROWSER_PREVIEW")
                        .selectionStage("PLANNED")
                        .selectionReason("规划期通过浏览器预览补源生成候选来源")
                        .reason(buildPreviewReason(candidate.getReason()))
                        .build())
                .toList();
}
```

**问题所在**：虽然 `discoveryMethod` 还保留了 `BROWSER_PREVIEW`，但更细的运行时语义基本都被压平了。

**致命结果**：后续如果要回放“这个候选是哪个引擎、哪条 Query、是否疑似受阻时得到的”，预览链路提供的证据会明显不够。

### 优化方案：

#### 1、让预览策略继承正式运行策略，再做明确预算裁剪

- **功能描述**：不要再新造一份迷你策略，而是从正式策略克隆后显式收缩预算。
- **业务成效**：预览与正式执行保持同构，结果更可信。

#### 2、预览期也应尽可能传入 `domainHint`

- **功能描述**：如果已有官网 URL、候选域名或用户输入网址，应当提取出来参与 Query 生成。
- **业务成效**：官网域内高价值页面的召回率会明显提升。

#### 3、把“无结果”和“失败/受阻”区分输出

- **功能描述**：为预览阶段单独输出结构化失败原因，而不是只写 debug 日志。
- **业务成效**：创建页能真正反映搜索链路当前状态，便于用户和研发及时干预。


### CollectorNodeConfig

#### 1、类注释和字段注释已经明显落后于真实业务语义，契约文档开始“说假话”

例如这里写的是：

```
/**
 * 用户明确指定的竞品官网、文档入口或特定的目标页面 URL 列表
 */
private List<String> competitorUrls;

/**
 * 采集的来源分类（例如：官方渠道 "OFFICIAL"、第三方渠道 "THIRD_PARTY" 等）
 */
private String sourceType;

/**
 * 搜索失败时的回退兜底策略顺序（例如：优先百度，失败后回退至必应或 HTTP 补源）
 */
private List<String> searchFallbackOrder;
```

但 `WorkflowFactory` 实际写入的是：

```
return CollectorNodeConfig.builder()
        .competitorName(competitorName)
        .competitorUrls(sourcePlan.getUrls())
        .sourceType(sourcePlan.getSourceType())
        .searchFallbackOrder(buildSearchFallbackOrder())
        // ...
        .build();
```

而 `searchFallbackOrder` 真实内容是：

```
List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP")
```

**问题所在**：`competitorUrls` 现在并不只是“用户明确指定 URL”，它还承载规划期自动发现 URL；`sourceType` 也不再是“OFFICIAL/THIRD_PARTY”这种二分类；`searchFallbackOrder` 更不是“百度/必应”这种引擎顺序，而是补源阶段顺序。

**致命结果**：这个类本来应该是全链路最重要的节点契约，但它自己的注释已经与真实行为脱节。后续前端、排障人员、新开发同学都会被误导。

#### 2、同一个对象同时承载“静态节点配置”和“运行态现场”，职责已经混杂

```
private SearchRuntimePolicy searchRuntimePolicy;
private SearchExecutionPlan searchExecutionPlan;
private SearchAuditSnapshot searchAuditCheckpoint;
```

**问题所在**：`CollectorNodeConfig` 名字上是“节点配置”，但它里面已经混入了：

- 运行期策略
- 执行计划
- 审计检查点

尤其 `searchAuditCheckpoint` 明显属于运行态、可变现场，而不是静态配置。

**致命结果**：一个本该用来定义“任务应该怎么跑”的对象，同时又承担“任务已经跑到了哪里”的职责。这会让序列化、持久化、恢复执行、前端展示和变更审计全部缠在一起。

#### 3、顶层字段和嵌套策略字段重复建模，最终生效规则只能去代码里猜

例如：

```
private Boolean verifyResultPage;
private Long searchTimeoutMillis;
private SearchRuntimePolicy searchRuntimePolicy;
```

运行期消费时又是：

```
private boolean isResultPageVerificationEnabled(CollectorNodeConfig config) {
        if (config.getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getVerifyResultPage());
        }
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getSearchRuntimePolicy().getVerifyResultPage());
        }
        return true;
}
```

以及：

```
private long resolveSearchTimeoutMillis(CollectorNodeConfig config, SearchExecutionPlan executionPlan) {
        if (config.getSearchTimeoutMillis() != null && config.getSearchTimeoutMillis() >= 0) {
            return config.getSearchTimeoutMillis();
        }
        // ...
}
```

**问题所在**：`CollectorNodeConfig` 顶层保留了一批运行控制字段，`SearchRuntimePolicy` 里又有另一批，有些重叠、有些分裂。

**致命结果**：最终到底哪个字段生效，只能看具体消费代码。这个对象已经失去了“看配置就能理解行为”的可读性。

### 优化方案：

#### 1、先修正文档契约，再修正数据契约

- **功能描述**：立即修正字段注释和 JSON 契约说明，确保类注释先说真话；再逐步收敛字段含义。
- **业务成效**：降低后续维护误解和前后端协作成本。

#### 2、拆分静态配置对象与运行态快照对象

- **功能描述**：`CollectorNodeConfig` 只保留静态输入与策略配置；执行计划和审计快照应迁移到独立运行态载体。
- **业务成效**：配置和现场分层清晰，恢复与审计更健壮。

#### 3、合并重复控制字段，建立明确覆盖链

- **功能描述**：对顶层字段和 `SearchRuntimePolicy` 做收口，保留一份主配置，并明确节点级覆盖顺序。
- **业务成效**：节点行为可预测，不必靠读源码推测优先级。


### SearchExecutionPlan

#### 1、类模型定义得很完整，但规划阶段只填了一半字段，导致“计划对象”先天残缺

```
public class SearchExecutionPlan {

    private String stage;
    private List<String> searchQueries;
    private List<String> fallbackOrder;
    private Integer targetCount;
    private Integer minVerifiedCount;
    private List<SearchExecutionStep> steps;
}
```

但 `WorkflowFactory` 真实构建默认计划时只写了：

```
private SearchExecutionPlan buildDefaultSearchExecutionPlan() {
        return SearchExecutionPlan.builder()
                .stage("COLLECTOR_SEARCH_AND_COLLECT")
                .steps(List.of(
                        step("LOAD_CANDIDATES", "读取规划期候选来源", 500, "nodeConfig"),
                        step("VERIFY_TOP_CANDIDATES", "验证高优先级候选来源是否可用", 5000, "browser"),
                        step("BROWSER_SUPPLEMENT_SEARCH", "候选不足时执行浏览器增补搜索", 8000, "searchEngine"),
                        step("SELECT_TARGETS", "合并候选并选出最终采集目标", 1000, "ranker"),
                        step("COLLECT_PAGES", "抓取页面正文并持久化证据", 12000, "collector")
                ))
                .build();
}
```

**问题所在**：`searchQueries`、`fallbackOrder`、`targetCount`、`minVerifiedCount` 这些真正能解释“系统为什么这样搜”的字段，在规划阶段并没有一并固化进去。

**致命结果**：前端和日志如果直接读取规划期的 `SearchExecutionPlan`，拿到的是一份字段不完整的“半计划”，根本不足以解释真实搜索策略。

#### 2、`SearchExecutionPlan` 和 `CollectorNodeConfig` 之间存在重复事实源，计划对象并不是第一真相

运行时补全时是：

```
return basePlan.toBuilder()
        .searchQueries(resolveSearchQueries(config, basePlan))
        .fallbackOrder(resolveSearchFallbackOrder(config))
        .targetCount(targetCount)
        .minVerifiedCount(minVerifiedCount)
        .build();
```

而 `resolveSearchQueries` 又优先读 `CollectorNodeConfig`：

```
if (config.getSearchQueries() != null && !config.getSearchQueries().isEmpty()) {
    return config.getSearchQueries();
}
if (executionPlan != null && executionPlan.getSearchQueries() != null) {
    return executionPlan.getSearchQueries();
}
```

**问题所在**：这说明 `SearchExecutionPlan` 不是一份真正独立的计划对象，而只是运行时从 `CollectorNodeConfig` 衍生出来的另一个展示层副本。

**致命结果**：当两边字段不一致时，谁是准的、谁是旧的、谁是给前端看的，完全靠调用方约定。这会让计划对象越来越像“临时拼出来的 UI ViewModel”。

#### 3、初始化计划时会把步骤运行现场全部清空，恢复语义很脆弱

```
private SearchExecutionPlan initializePlan(SearchExecutionPlan plan) {
        List<SearchExecutionStep> steps = plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()
                ? defaultSteps()
                : plan.getSteps().stream()
                .map(step -> step.toBuilder()
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .message(null)
                        .startedAt(null)
                        .completedAt(null)
                        .build())
                .toList();
        return SearchExecutionPlan.builder()
                .stage(plan == null ? "COLLECTOR_SEARCH_AND_COLLECT" : plan.getStage())
                .steps(new ArrayList<>(steps))
                .build();
}
```

**问题所在**：不管上一次计划里步骤已经跑到什么状态，这里初始化时都会统一清空。

**致命结果**：如果后续真要依赖计划对象本身做断点恢复、现场回放或历史比对，这种“初始化即洗白”的设计会让计划对象丧失历史价值。

### 优化方案：

#### 1、规划阶段就生成完整计划，不要把关键字段留到运行时再补

- **功能描述**：创建节点时就把 Query、FallbackOrder、TargetCount、MinVerifiedCount 一并写入。
- **业务成效**：计划对象在任务创建时就是可展示、可审计、可解释的。

#### 2、明确计划对象与节点配置对象的主从关系

- **功能描述**：要么让 `SearchExecutionPlan` 成为唯一事实源，要么让它明确退化为只读展示模型。
- **业务成效**：减少重复字段和事实冲突。

#### 3、区分“模板计划初始化”和“恢复现场重建”

- **功能描述**：新任务走模板初始化，断点恢复走历史现场重建，不要共用同一套清空逻辑。
- **业务成效**：恢复语义更真实，审计链更完整。


### SearchAuditSnapshot

#### 1、快照模型缺少“已尝试但未选中”的完整现场，天然不适合做高质量恢复

```
public class SearchAuditSnapshot {

    private SearchExecutionTrace executionTrace;
    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressHistory;
    private List<SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> selectedTargets;
}
```

而恢复时是这样用的：

```
private Map<String, SearchCollectionTarget> resolveAttemptedTargetsFromCheckpoint(SearchAuditSnapshot checkpoint) {
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        if (checkpoint == null || checkpoint.getSelectedTargets() == null || checkpoint.getSelectedTargets().isEmpty()) {
            return attemptedTargets;
        }
        appendAttemptedTargets(attemptedTargets, checkpoint.getSelectedTargets());
        return attemptedTargets;
}
```

**问题所在**：这个快照模型里只有 `selectedTargets`，没有：

- attemptedTargets
- verifiedButRejectedTargets
- discardedTargets
- blockedAttempts

**致命结果**：恢复时系统只能从“曾被正式选中”的目标里恢复现场，之前那些已经验证失败、被阻断、被丢弃的 URL 统统失忆。恢复质量天然不完整。

#### 2、快照里同时携带完整候选列表、完整进度历史、完整已选目标，体量会快速膨胀

```
private List<SearchProgressSnapshot> progressHistory;
private List<SourceCandidate> sourceCandidates;
private List<SearchCollectionTarget> selectedTargets;
```

而进度发布时还会频繁复制：

```
progressListener.accept(SearchExecutionUpdate.builder()
        .executionPlan(executionPlan)
        .latestProgress(latest)
        .progressSnapshots(snapshotHistory)
        .sourceCandidates(sourceCandidates == null ? List.of() : new ArrayList<>(sourceCandidates))
        .selectedTargets(selectedTargets == null ? List.of() : new ArrayList<>(selectedTargets))
        .executionTrace(executionTrace)
        .build());
```

**问题所在**：候选越多、进度越细、已选目标越多，这个快照就越胖。它不是一个轻量 checkpoint，而是越来越像“全量运行现场打包”。

**致命结果**：前端推送、数据库存储、节点配置序列化、任务详情回放都会持续吃到大对象负担，后期规模一上来问题会很明显。

#### 3、`latestProgress` 与 `progressHistory` 并存，存在重复状态源

```
private SearchProgressSnapshot latestProgress;
private List<SearchProgressSnapshot> progressHistory;
```

**问题所在**：这两个字段表达的是同一组进度状态的两个视角，但没有任何机制保证 `latestProgress == progressHistory.last()`。

**致命结果**：只要某次更新顺序、序列化、恢复回填有偏差，前端就可能看到“顶部状态”和“时间线末尾状态”不一致的奇怪现场。

### 优化方案：

#### 1、把恢复所需的尝试现场补齐为正式快照字段

- **功能描述**：至少补上 `attemptedTargets`、`discardedTargets`、`blockedAttempts` 等恢复关键现场。
- **业务成效**：任务恢复才真正有资格叫“断点续跑”，而不是半失忆重启。

#### 2、为快照做轻重分层

- **功能描述**：轻量 checkpoint 只保留恢复必需字段；详细审计现场另存扩展对象或单独事件流。
- **业务成效**：降低对象膨胀和前端传输压力。

#### 3、统一进度状态源

- **功能描述**：要么只保留 `progressHistory` 并按需取最后一条，要么只保留 `latestProgress` 加增量事件。
- **业务成效**：减少重复状态，避免前端读到两套进度真相。


### CandidateVerificationResult

#### 1、验证批次结果同时返回三套高度重叠的数据，内存和序列化体积被无意义放大

```
public class CandidateVerificationResult {

    private List<SourceCandidate> updatedCandidates;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> verifiedTargets;
}
```

而构建时又是：

```
List<SourceCandidate> updatedCandidates = new ArrayList<>();
List<SearchCollectionTarget> attemptedTargets = new ArrayList<>();
List<SearchCollectionTarget> verifiedTargets = new ArrayList<>();

for (SourceCandidate candidate : uniqueCandidates) {
    SourceCollector.CollectedPage page = sourceCollector.collect(...);
    // ...
    SourceCandidate updatedCandidate = candidate.toBuilder()
            .verified(verified)
            .verificationReason(verificationReason)
            .matchedSignals(matchedSignals)
            .selectionStage(verified ? "VERIFIED" : "DISCARDED")
            .selectionReason(verified ? "运行期验证通过，允许直接进入正式采集" : "运行期验证未通过，降级为候选兜底")
            .build();
    SearchCollectionTarget target = SearchCollectionTarget.builder()
            .candidate(updatedCandidate)
            .collectedPage(page)
            .build();

    updatedCandidates.add(updatedCandidate);
    attemptedTargets.add(target);
    if (verified) {
        verifiedTargets.add(target);
    }
}
```

**问题所在**：`updatedCandidates` 已经持有完整候选元数据；`attemptedTargets` 又把这份元数据塞进 `candidate` 后再带上一份 `collectedPage`；`verifiedTargets` 还是 `attemptedTargets` 的子集。三份数据高度重叠。

**致命结果**：一轮验证一多，内存占用、快照持久化、事件推送都会被这类重复对象撑大，而且后续一旦某处只更新了其中一份，三套结果就可能出现语义漂移。

#### 2、它只暴露“尝试过”和“验证通过”，却没有把“验证失败现场”正式建模出来

```
private List<SearchCollectionTarget> attemptedTargets;
private List<SearchCollectionTarget> verifiedTargets;
```

**问题所在**：当前调用方如果想知道：

- 哪些 URL 被验证失败
- 失败原因分布是什么
- 是否存在被阻断、无正文、命中噪声词等类别

只能自己拿 `attemptedTargets - verifiedTargets` 做差集推断。

**致命结果**：验证结果明明是整个补源治理链的核心现场，却没有一个正式、直接可读的“失败结果集合”。后续恢复、审计、报表都要靠二次推断，可靠性很差。

### 优化方案：

#### 1、把验证结果收敛为一份主结果集，再用轻量索引表达子集关系

- **功能描述**：保留一份 canonical 的 `targets` 结果集，再附带 `verifiedIds`、`discardedIds` 或状态字段，而不是三套平行列表。
- **业务成效**：减少对象重复和状态漂移风险。

#### 2、把失败结果和失败分类显式输出

- **功能描述**：新增 `rejectedTargets`、`failureReasonStats` 或结构化失败码统计。
- **业务成效**：验证失败现场可直接消费，恢复与诊断链路更完整。


### BrowserSearchRuntimeResult

#### 1、`fallbackSuggested` 把“推荐回退”“必须回退”“已经回退”混成了一个布尔值

`BrowserSearchRuntimeService` 在多种场景都返回：

```
return BrowserSearchRuntimeResult.builder()
        .candidates(List.of())
        .executedQueries(List.of())
        .searchEngine(getSearchEngineName())
        .summary("浏览器搜索已全局关闭(search.browser.enabled=false)，已回退到 HTTP/规划候选链路")
        .fallbackSuggested(true)
        .browserTraceId(null)
        .build();
```

以及：

```
return BrowserSearchRuntimeResult.builder()
        .summary("当前节点未启用浏览器补源，允许继续走回退补源链路")
        .fallbackSuggested(true)
        .build();
```

还有：

```
return BrowserSearchRuntimeResult.builder()
        .summary(summary)
        .fallbackSuggested(filteredCandidates.isEmpty())
        .blockedReason(blockedReason)
        .blockedCount(blockedCount.get())
        .browserTraceId(browserTraceId)
        .build();
```

**问题所在**：`fallbackSuggested` 这个字段现在承担了至少三种不同语义：

- 浏览器链路被关闭，系统已经确定要回退
- 节点没启用浏览器，本就不该走这条路
- 浏览器执行完了但没拿到结果，建议考虑回退

**致命结果**：调用方看到一个 `true`，根本不知道这是“配置级禁用”“运行时失败”还是“正常空结果”。布尔值把重要的状态语义全部压扁了。

#### 2、结果对象只留了一个 `searchEngine`，但真实执行过程可能跑了多引擎序列

```
private String resolveResultEngine(List<SourceCandidate> filteredCandidates) {
        if (filteredCandidates != null) {
            for (SourceCandidate candidate : filteredCandidates) {
                if (candidate != null && StringUtils.hasText(candidate.getSearchEngine())) {
                    return candidate.getSearchEngine();
                }
            }
        }
        return getSearchEngineName();
}
```

而摘要里又在汇总多个引擎：

```
String executedEngineSummary = summarizeEngines(filteredCandidates, engineSequence);
String summary = filteredCandidates.isEmpty()
        ? buildEmptyResultSummary(blockedReason)
        : "浏览器搜索执行 " + executedQueries.size() + " 个 query，经由 " + executedEngineSummary + " 提取到 "
        + filteredCandidates.size() + " 条候选来源";
```

**问题所在**：真正执行时可能经历了多引擎尝试，但最终结果对象只保留一个 `searchEngine` 字符串，而且还是“第一个命中候选里的引擎”。

**致命结果**：审计和前端回放会误以为整次浏览器搜索只使用了单一引擎，实际的多引擎降级现场被压缩丢失。

#### 3、`summary` 继续承担机器状态和人类说明双重职责

```
private String summary;
private String blockedReason;
```

**问题所在**：失败码只剩下一个宽泛的 `blockedReason` 字符串，其他重要状态靠 `summary` 自由文本表达。

**致命结果**：前端和后端如果想做结构化判定，还是得去猜文本内容。这个结果对象没有真正承担起“运行时返回协议”的职责。

### 优化方案：

#### 1、把回退语义拆成结构化状态

- **功能描述**：至少拆成 `fallbackRecommended`、`fallbackExecuted`、`fallbackReasonCode` 这类结构化字段。
- **业务成效**：调用方可以准确理解浏览器结果为什么为空、下一步该怎么处理。

#### 2、把引擎现场改成多值结构

- **功能描述**：新增 `enginesTried`、`enginesSucceeded` 或 `engineSequence` 回传，而不是只给单一 `searchEngine`。
- **业务成效**：多引擎回退现场可回放、可审计。

#### 3、减少对自由文本 `summary` 的依赖

- **功能描述**：保留人类可读摘要，但核心状态全部转为结构化字段。
- **业务成效**：前端和治理逻辑不必再靠解析自然语言工作。


### SearchExecutionTrace

#### 1、这个类已经膨胀成“搜索阶段大杂烩”，大量字段重复承载配置、计划、结果和恢复语义

```
public class SearchExecutionTrace {

    private String traceVersion;
    private String searchMode;
    private List<String> searchQueries;
    private List<String> fallbackOrder;
    private Integer plannedCandidateCount;
    private Integer verifiedCandidateCount;
    private Integer supplementedCandidateCount;
    private Integer selectedCandidateCount;
    private List<String> selectedUrls;
    private String supplementMethod;
    private String browserSearchEngine;
    private String browserTraceId;
    private List<String> browserExecutedQueries;
    private String browserSearchSummary;
    private Boolean providerFallbackUsed;
    private Long searchTimeoutMillis;
    private Long searchElapsedMillis;
    private Boolean circuitBroken;
    private Boolean degraded;
    private String degradationReason;
    private SearchRuntimePolicy runtimePolicy;
    private String browserBlockedReason;
    private Integer browserBlockedCount;
    private String fallbackDecision;
    private String recoveryCheckpoint;
    private String recoveryAdvice;
    private Boolean resumedFromCheckpoint;
    private String checkpointSource;
    private LocalDateTime generatedAt;
}
```

**问题所在**：这个对象同时装了：

- 计划配置：`searchMode/searchQueries/fallbackOrder/runtimePolicy`
- 执行结果：`selectedUrls/selectedCandidateCount`
- 浏览器现场：`browser*`
- 降级现场：`degraded/degradationReason/fallbackDecision`
- 恢复现场：`recoveryCheckpoint/recoveryAdvice/resumedFromCheckpoint`

它已经不是“执行轨迹”，而是几乎把搜索阶段所有解释字段全打包进来了。

**致命结果**：这个对象会越来越像一个松散的大 Map。任何新需求都可能继续往里面塞字段，最终既难维护，也难做真正稳定的版本治理。

#### 2、浏览器专属字段硬塞进通用轨迹模型，导致轨迹形状高度稀疏

```
private String browserSearchEngine;
private String browserTraceId;
private List<String> browserExecutedQueries;
private String browserSearchSummary;
private String browserBlockedReason;
private Integer browserBlockedCount;
```

**问题所在**：这些字段只在浏览器补源链路有意义；当模式是 `HTTP_ONLY`、`HEURISTIC_ONLY` 或纯规划候选时，它们天然为空。

**致命结果**：前端和日志消费方会面对一个大量字段“有时有、有时全空”的轨迹对象，必须手动理解不同模式下哪些字段才有效，复杂度持续上升。

#### 3、它继续携带完整 `SearchRuntimePolicy`，轨迹对象又和配置对象耦死在一起

```
private SearchRuntimePolicy runtimePolicy;
```

**问题所在**：轨迹本应记录“当时生效了什么”，但这里直接把完整策略对象再嵌一次。

**致命结果**：一方面对象体积继续膨胀；另一方面轨迹、节点配置、审计快照之间又多出一份重复事实源。

### 优化方案：

#### 1、把执行轨迹拆成基础轨迹和模式扩展轨迹

- **功能描述**：基础轨迹只保留所有模式共享的字段；浏览器、HTTP、启发式各自用扩展结构承载专属现场。
- **业务成效**：轨迹模型更稳定，前端消费也更清晰。

#### 2、限制轨迹对象只承载“事实”，不要继续兼做配置和解释层

- **功能描述**：计划字段只保留引用或摘要，详细配置回到计划/配置对象本身。
- **业务成效**：减少事实源重复和模型膨胀。

#### 3、为轨迹模型做明确版本治理

- **功能描述**：不是只保留一个 `traceVersion` 字符串，而是明确字段版本演进和兼容策略。
- **业务成效**：后续扩展不会把旧前端和旧数据回放链路打崩。


### SearchProgressSnapshot

#### 1、进度百分比把 `FAILED` 和 `SKIPPED` 也当成“完成”，会制造非常怪异的进度感知

```
int completedSteps = (int) steps.stream()
        .filter(step -> step.getStatus() == SearchExecutionStep.StepStatus.SUCCESS
                || step.getStatus() == SearchExecutionStep.StepStatus.FAILED
                || step.getStatus() == SearchExecutionStep.StepStatus.SKIPPED)
        .count();
int progressPercent = totalSteps == 0 ? 0 : (int) Math.round((completedSteps * 100.0D) / totalSteps);
```

**问题所在**：这里把失败和跳过步骤都计入 completed。

**致命结果**：一个任务完全有可能出现：

- `status = FAILED`
- `progressPercent = 100`

或者大量关键步骤被跳过，但进度条依然快速走满。对于用户来说，这不是“完成”，而是“执行已终止/已降级”。

#### 2、快照里 `status`、`degraded`、`degradationReason` 三套字段继续叠加，状态语义被分裂

```
private String status;
private Boolean degraded;
private String degradationReason;
```

以及生成逻辑：

```
if (steps.stream().anyMatch(step -> step.getStatus() == SearchExecutionStep.StepStatus.FAILED)) {
    status = "FAILED";
} else if (degraded) {
    status = "DEGRADED";
} else if (completedSteps >= totalSteps && totalSteps > 0) {
    status = "SUCCESS";
} else {
    status = "RUNNING";
}
```

**问题所在**：这里已经存在一套字符串状态；同时又单独保留了 `degraded` 布尔值和 `degradationReason`。

**致命结果**：调用方既要看 `status`，又要看 `degraded`，还要看 `degradationReason`，才能完整理解当前状态。快照对象没有真正把状态语义收口。

#### 3、`currentStep` 和 `currentStepCode` 继续并存，展示层和协议层事实重复

```
private String currentStep;
private String currentStepCode;
```

**问题所在**：`currentStep` 本质是基于 `currentStepCode` 和执行计划现算出来的人类展示文案，不是独立事实。

**致命结果**：一旦计划步骤名称调整、国际化文案变化或恢复回放阶段引用了旧计划，`currentStep` 和 `currentStepCode` 就可能不一致。

### 优化方案：

#### 1、把“执行结束程度”和“执行健康状态”拆开

- **功能描述**：`progressPercent` 只描述推进程度，失败/降级状态单独建模，不要再混着算。
- **业务成效**：进度条和状态提示不会互相打架。

#### 2、统一状态表达

- **功能描述**：保留一个强类型状态枚举，再附带可选的降级原因码。
- **业务成效**：调用方读取状态更简单，语义更稳定。

#### 3、只保留 `currentStepCode`，显示文案按需派生

- **功能描述**：协议层传递稳定 code，展示层自己映射文案。
- **业务成效**：减少重复事实和文案漂移。


### SearchExecutionUpdate

#### 1、它不是“增量更新协议”，而是在每次进度变化时整包推送完整现场

```
public class SearchExecutionUpdate {

    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressSnapshots;
    private List<SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> selectedTargets;
    private SearchExecutionTrace executionTrace;
}
```

而发布时又是：

```
progressListener.accept(SearchExecutionUpdate.builder()
        .executionPlan(executionPlan)
        .latestProgress(latest)
        .progressSnapshots(snapshotHistory)
        .sourceCandidates(sourceCandidates == null ? List.of() : new ArrayList<>(sourceCandidates))
        .selectedTargets(selectedTargets == null ? List.of() : new ArrayList<>(selectedTargets))
        .executionTrace(executionTrace)
        .build());
```

**问题所在**：每次 update 都携带：

- 完整执行计划
- 完整历史进度
- 完整候选列表
- 完整已选目标
- 完整执行轨迹

这不是事件增量，而是“当前整包现场快照”。

**致命结果**：随着候选池变大、进度历史增长、目标数增加，前端推流和节点运行期输出都会越来越重。

#### 2、更新对象缺少顺序号、事件类型和变更面，消费方只能做全量替换

```
private SearchProgressSnapshot latestProgress;
private List<SearchProgressSnapshot> progressSnapshots;
```

**问题所在**：这个模型没有：

- `updateSeq`
- `eventType`
- `changedSections`

消费方根本不知道这次更新究竟变了什么，只能整包覆盖。

**致命结果**：前端很难做高效的增量渲染和乱序保护，恢复回放也难判断事件先后。

### 优化方案：

#### 1、把 `SearchExecutionUpdate` 改成真正的增量事件模型

- **功能描述**：每次只推送当前步骤变化、候选增量或目标增量，而不是全量现场。
- **业务成效**：流量和对象体积显著下降，前端更容易消费。

#### 2、补上顺序号和事件类型

- **功能描述**：至少增加 `updateSeq`、`eventType`、`changedKeys`。
- **业务成效**：消费方可以做幂等、乱序保护和增量合并。


### SearchCollectionTarget

#### 1、目标对象本身没有稳定身份，只能在外部用 `candidate.url` 临时充当 key

```
public class SearchCollectionTarget {

    private SourceCandidate candidate;
    private SourceCollector.CollectedPage collectedPage;
}
```

外部使用时是：

```
private void appendAttemptedTargets(Map<String, SearchCollectionTarget> attemptedTargets,
                                    List<SearchCollectionTarget> newTargets) {
        for (SearchCollectionTarget target : newTargets) {
            if (target == null || target.getCandidate() == null || !StringUtils.hasText(target.getCandidate().getUrl())) {
                continue;
            }
            attemptedTargets.put(target.getCandidate().getUrl(), target);
        }
}
```

**问题所在**：这个对象自己没有 `targetId`、没有稳定 key，整个系统只能假设“URL 就是唯一身份”。

**致命结果**：一旦同一个真实页面出现参数变体、跳转变体或 URL 规范化前后的差异，目标身份就会变得脆弱，恢复与去重都容易出问题。

#### 2、它直接携带完整 `CollectedPage`，导致目标对象非常重

```
private SourceCollector.CollectedPage collectedPage;
```

**问题所在**：`SearchCollectionTarget` 既用于：

- attemptedTargets
- verifiedTargets
- selectedTargets
- SearchExecutionResult
- SearchAuditSnapshot

这意味着同一份页面正文快照会随着目标对象被层层复制、层层嵌套传播。

**致命结果**：目标对象从“采集目标标识”变成了“页面正文运输车”，对象膨胀严重。

#### 3、对象里没有明确表达“为什么被选中/为什么被保留”的 target 级语义

当前只能透过 `candidate` 间接拿：

```
private SourceCandidate candidate;
```

**问题所在**：目标级决策语义全部寄生在 `candidate.selectionStage`、`candidate.selectionReason` 上。

**致命结果**：一旦候选元数据在后续排序、验证、刷新阶段发生变化，目标对象自己的选中理由就不再稳定。

### 优化方案：

#### 1、为正式采集目标补稳定身份

- **功能描述**：新增 `targetId` 或规范化 URL key，目标对象自己具备身份标识。
- **业务成效**：恢复、比较、审计更稳定。

#### 2、把页面正文快照改成引用或轻量摘要

- **功能描述**：目标对象只挂页面快照引用、hash 或摘要，详细正文另存。
- **业务成效**：显著减轻结果对象和快照对象体积。

#### 3、把 target 级决策语义显式挂出来

- **功能描述**：新增 `targetStage`、`targetReasonCode`、`targetSummary` 等字段。
- **业务成效**：目标为什么被正式采集，不再依赖外部推断。


### SearchExecutionResult

#### 1、它已经和 `SearchAuditSnapshot` 形成高度重叠，结果对象与快照对象几乎双份存储同一现场

```
public class SearchExecutionResult {

    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot progressSnapshot;
    private List<SearchProgressSnapshot> progressSnapshots;
    private List<SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> selectedTargets;
    private String reasoningSummary;
    private SearchExecutionTrace executionTrace;
    private SearchAuditSnapshot auditSnapshot;
}
```

而 `auditSnapshot` 本身又持有：

- `executionPlan`
- `latestProgress`
- `progressHistory`
- `sourceCandidates`
- `selectedTargets`
- `executionTrace`

**问题所在**：`SearchExecutionResult` 自己装了一份完整现场，`auditSnapshot` 里又再装一份几乎相同的完整现场。

**致命结果**：结果对象越来越重，而且任何一个字段只要在两边更新不同步，就会产生双份真相。

#### 2、`reasoningSummary` 是自由文本总结，但它表达的内容在结构化字段里已经基本存在

```
private String reasoningSummary;
```

而构建时其实是从结构化计数拼出来的：

```
String reasoningSummary = "规划候选 " + executionTrace.getPlannedCandidateCount()
        + " 条，验证通过 " + verifiedCount
        + " 条，运行期补源 " + supplementedCount
        + " 条，最终选中 " + selectedTargets.size() + " 条"
```

**问题所在**：同一份事实，结构化字段里已有，结果对象里又多放一段自由文本。

**致命结果**：一旦字段变更、语言切换、文案调整，文本总结和结构化事实就有可能不一致。

#### 3、下游 `CollectorAgent` 会继续把这份重结果对象拆开再写进节点输出，重复存储进一步放大

```
output.put("sourceCandidates", searchExecutionResult.getSourceCandidates() == null ? List.of() : searchExecutionResult.getSourceCandidates());
output.put("searchExecutionPlan", searchExecutionResult.getExecutionPlan());
output.put("searchExecutionTrace", searchExecutionResult.getExecutionTrace());
output.put("searchProgress", progressSnapshots.isEmpty() ? searchExecutionResult.getProgressSnapshot() : progressSnapshots.get(progressSnapshots.size() - 1));
output.put("searchProgressSnapshots", progressSnapshots);
output.put("searchAudit", searchExecutionResult.getAuditSnapshot());
output.put("selectedTargets", buildSelectedTargetSummaries(targets));
```

**问题所在**：结果对象已经很重，下游还会继续把里面的各个大字段拆出来平铺到输出 JSON。

**致命结果**：节点输出数据会迅速膨胀，前端回放、数据库存储和恢复序列化都会承压。

### 优化方案：

#### 1、明确 `SearchExecutionResult` 和 `SearchAuditSnapshot` 的主从关系

- **功能描述**：要么让结果对象只保留结果入口和引用，要么让快照对象只承担恢复，不再双份持有同一现场。
- **业务成效**：减少重复事实源和对象膨胀。

#### 2、把 `reasoningSummary` 改成展示层派生字段

- **功能描述**：结构化字段保留，摘要文案按需现算。
- **业务成效**：避免自由文本和结构化事实漂移。


### SearchBrowserConfigurationGuard

#### 1、最危险的配置冲突场景只是打了一条 `error` 日志，并没有真正阻止系统启动

```
if (!searchBrowserProperties.isEnabled()) {
    if ("BROWSER_ONLY".equals(mode)) {
        log.error("检测到 search.browser.enabled=false 且 search.mode=BROWSER_ONLY。"
                + " 浏览器搜索链路将完全不可用，请在 application.yml 中显式设置 search.browser.enabled: true。");
        return;
    }
    log.warn("检测到 search.browser.enabled=false。当前 search.mode={}，系统会静默回退到 HTTP/规划候选。"
            + " 如果希望执行真实浏览器搜索并展示浏览器窗口，请在 application.yml 中设置 search.browser.enabled: true。", mode);
    return;
}
```

**问题所在**：这里遇到的是一个明确的自相矛盾配置：

- `search.mode = BROWSER_ONLY`
- `search.browser.enabled = false`

但守卫器只是打印日志然后 `return`，应用照样启动。

**致命结果**：系统表面上成功启动，实际核心搜索模式已经跑不通。问题不会在启动期暴露，而会被延迟到运行任务时才炸出来。

#### 2、它只关注浏览器开关，不校验引擎配置是否真实可用

```
log.info("浏览器搜索已启用: engine={}, fallbackEngines={}, mode={}, verifyResultPage={}, maxOpenResultPages={}",
        searchBrowserProperties.getEngine(),
        searchBrowserProperties.getFallbackEngines(),
        mode,
        searchBrowserProperties.isVerifyResultPage(),
        searchBrowserProperties.getMaxOpenResultPages());
```

**问题所在**：这里会把当前 engine 和 fallbackEngines 打出来，但并没有真正校验：

- 主引擎 key 是否存在
- fallback engine 是否存在
- 它们是否被 `SearchEngineProperties` 标记为 enabled

**致命结果**：日志看起来“浏览器搜索已启用”，实际上可能配置了一个不存在或不可用的引擎链。

### 优化方案：

#### 1、对不可执行的模式组合直接 fail fast

- **功能描述**：`BROWSER_ONLY + browser.enabled=false` 这类组合应在启动期直接抛异常。
- **业务成效**：错误前移，避免把配置事故拖到任务运行时。

#### 2、补齐引擎链有效性校验

- **功能描述**：启动时验证主引擎和回退引擎是否存在、是否启用。
- **业务成效**：防止“日志显示已启用，实际无引擎可跑”的假现场。


### SearchSecurityConfigurationGuard

#### 1、它把“安全配置校验”说得很完整，实际上只做了 HTTPS 协议检查

```
for (Map.Entry<String, SearchEngineProperties.EngineConfig> entry : searchEngineProperties.entrySet()) {
    SearchEngineProperties.EngineConfig config = entry.getValue();
    if (config == null || !StringUtils.hasText(config.getBaseUrl())) {
        continue;
    }
    if (!UrlSecurityUtils.isHttpsUrl(config.getBaseUrl())) {
        throw new BusinessException(ResultCode.PARAM_INVALID,
                "search.engines." + entry.getKey() + ".base-url 必须使用 https URL");
    }
}
if (StringUtils.hasText(serpApiProperties.getEndpoint())
        && !UrlSecurityUtils.isHttpsUrl(serpApiProperties.getEndpoint())) {
    throw new BusinessException(ResultCode.PARAM_INVALID, "serpapi.endpoint 必须使用 https URL");
}
if (StringUtils.hasText(qianfanSearchProperties.getEndpoint())
        && !UrlSecurityUtils.isHttpsUrl(qianfanSearchProperties.getEndpoint())) {
    throw new BusinessException(ResultCode.PARAM_INVALID, "qianfan-search.endpoint 必须使用 https URL");
}
```

**问题所在**：这套所谓“安全校验”目前只检查了一件事：是不是 HTTPS。

**致命结果**：大量真正影响搜索链路安全与可信度的配置问题都不会被发现，例如：

- endpoint 虽然是 HTTPS，但指向完全不受信任的宿主
- defaultEngine 指向不存在的引擎
- provider 虽启用但根本没有可用凭证

#### 2、启动日志“校验通过”容易给人过度安全感

```
log.info("搜索安全配置校验通过: engineCount={}, serpapiConfigured={}, qianfanConfigured={}",
        searchEngineProperties.size(),
        StringUtils.hasText(serpApiProperties.getApiKey()),
        StringUtils.hasText(qianfanSearchProperties.getApiKey()));
```

**问题所在**：日志文案叫“安全配置校验通过”，但真实校验范围非常有限。

**致命结果**：运维和研发会误以为搜索链路的关键安全约束已经被系统兜住，实际上还有大量错误配置会静悄悄流入运行期。

### 优化方案：

#### 1、把安全校验从“只看协议”扩展到“看宿主、看凭证、看引用一致性”

- **功能描述**：校验 endpoint host 白名单、默认引擎引用有效性、启用 provider 的凭证存在性。
- **业务成效**：安全守卫器真正具备防事故能力。

#### 2、日志文案与真实校验范围保持一致

- **功能描述**：如果当前只做了 HTTPS 检查，就明确写成“HTTPS 配置校验通过”，不要泛化成全部安全通过。
- **业务成效**：减少误导性安全感。


### QianfanSearchProperties

#### 1、专门写了 `resolveDefaultEngineKey` 归一化方法，但当前 Provider 根本没真正用它

属性类里是：

```
public String resolveDefaultEngineKey(SearchEngineProperties searchEngineProperties) {
        if (searchEngineProperties == null) {
            return StringUtils.hasText(defaultEngine) ? defaultEngine : "baidu";
        }
        return searchEngineProperties.resolveAvailableEngineKey(defaultEngine);
}
```

但实际 Provider 在写候选时用的是：

```
.searchEngine(defaultText(properties.getDefaultEngine(), "baidu"))
```

**问题所在**：这说明 `QianfanSearchProperties` 里最有价值的“归一化与可用性解析能力”只是写了，但没有真正贯穿到执行链路。

**致命结果**：审计字段 `searchEngine` 最终记录的仍然可能是未经标准化的原始字符串，而不是稳定引擎 key。

#### 2、默认引擎继续硬锁在 `baidu`，把低质量默认入口写死进了渠道配置

```
private String defaultEngine = "baidu";
```

**问题所在**：千帆 Provider 虽然面向中文场景，但把默认引擎写死为百度，继续把搜索质量问题前置到了配置层。

**致命结果**：即使上游 Query 模板改善了，渠道默认入口仍然会天然偏向广告和聚合噪声更重的结果源。

### 优化方案：

#### 1、强制 Provider 统一使用 `resolveDefaultEngineKey`

- **功能描述**：不要再直接读取原始 `defaultEngine` 字符串，所有落盘和审计字段统一走归一化解析。
- **业务成效**：引擎维度终于稳定，日志与报表可对齐。

#### 2、把默认引擎从硬编码改成显式配置决策

- **功能描述**：让默认引擎按任务语言、地区或场景显式指定，而不是写死在属性类里。
- **业务成效**：减少劣质默认搜索入口对整体质量的拖累。


### SerpApiProperties

#### 1、`defaultEngine` 直接原样写入请求和候选元数据，没有任何归一化或可用性检查

Provider 构造请求时：

```
String engine = encode(defaultText(properties.getDefaultEngine(), "google"));
return URI.create(endpointText + separator
        + "engine=" + engine
        + "&q=" + encodedQuery
        + "&api_key=" + apiKey);
```

写候选时又是：

```
.searchEngine(defaultText(properties.getDefaultEngine(), "google"))
```

**问题所在**：`SerpApiProperties.defaultEngine` 当前是一个完全裸奔的字符串，它既不走 `SearchEngineProperties` 的标准化，也不校验当前值是否真的可用。

**致命结果**：只要配置写得不规范或写成了一个非标准值，外部请求参数和内部审计字段都会一起失真。

#### 2、渠道配置过于单薄，根本无力支撑“全球搜索质量调优”

```
public class SerpApiProperties {

    private String apiKey;
    private String endpoint = "https://serpapi.com/search";
    private String defaultEngine = "google";
}
```

**问题所在**：这个属性类只暴露了 API Key、endpoint 和 engine，完全没有：

- 语言参数
- 地区参数
- 市场参数
- 单 query 结果预算

**致命结果**：对于海外 SaaS、跨地区产品、英文官网等场景，系统根本无法从配置层精准约束 SerpApi 的搜索上下文，搜索指令质量再好，也会被搜索环境的不确定性吞掉一截。

### 优化方案：

#### 1、把 `defaultEngine` 纳入统一引擎标准化治理

- **功能描述**：SerpApi 侧也应复用统一引擎 key 解析与校验机制。
- **业务成效**：请求参数和审计字段都能保持稳定维度。

#### 2、补齐国际搜索上下文配置

- **功能描述**：增加 locale、country、language、resultsPerQuery 等显式配置。
- **业务成效**：SerpApi 不再只是“能搜”，而是具备可调优的全球检索能力。


### SearchExecutionStep

#### 1、步骤身份、依赖和状态全部用裸字符串承载，计划协议缺乏强约束

```
public class SearchExecutionStep {

    private String stepCode;
    private String goal;
    private long expectedDurationMs;
    private String dependency;
    private StepStatus status;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
```

**问题所在**：这里虽然给 `status` 做了枚举，但更关键的：

- `stepCode`
- `dependency`

依然是裸字符串。系统里到处都在写：

- `LOAD_CANDIDATES`
- `VERIFY_TOP_CANDIDATES`
- `BROWSER_SUPPLEMENT_SEARCH`
- `collector`
- `ranker`

没有统一常量、没有依赖合法性校验。

**致命结果**：只要有人改错一个 `stepCode` 或 `dependency` 文本，前端进度映射、恢复建议、步骤更新逻辑都会静默失真。

#### 2、`expectedDurationMs` 目前只是静态估值，但会反向参与超时推导，误差会直接污染运行时

搜索协调器会用步骤时长总和推导超时：

```
long expectedNodeDuration = executionPlan == null || executionPlan.getSteps() == null
        ? 0L
        : executionPlan.getSteps().stream().mapToLong(SearchExecutionStep::getExpectedDurationMs).sum();
if (expectedNodeDuration <= 0L) {
    return 15000L;
}
return Math.max(1000L, Math.round(expectedNodeDuration * 0.6D));
```

**问题所在**：这意味着 `expectedDurationMs` 不再只是“给用户看的预估时长”，而是隐式参与了真实运行时限。

**致命结果**：一旦计划步骤估时写得偏小，系统就会更早判超时；写得偏大，又会延迟降级时机。计划展示字段和控制字段被绑死了。

### 优化方案：

#### 1、为步骤编码和依赖项建立强类型协议

- **功能描述**：至少统一为常量或枚举，而不是散落的字符串字面量。
- **业务成效**：计划、进度、恢复、前端展示能共享稳定身份。

#### 2、把“展示估时”和“运行时预算”拆开

- **功能描述**：`expectedDurationMs` 只保留展示意义；真实超时预算单独建模。
- **业务成效**：计划说明不会再偷偷影响运行时控制。


### SourceCandidate

#### 1、这个类已经过载成“全阶段万能包”，单对象承担了太多彼此冲突的语义

```
public class SourceCandidate {

    private String url;
    private String title;
    private String sourceType;
    private String discoveryMethod;
    private String reason;
    private String domain;
    private String publishedAt;
    private double relevanceScore;
    private double freshnessScore;
    private double qualityScore;
    private double totalScore;
    private SourceTrustTier trustTier;
    private String trustTierLabel;
    private List<String> rankingReasons;
    private String rankingSummary;
    private String searchQuery;
    private String searchEngine;
    private Integer resultRank;
    private String browserTraceId;
    private Boolean verified;
    private String verificationReason;
    private List<String> matchedSignals;
    private String selectionStage;
    private String selectionReason;
    private String selectionSummary;
}
```

**问题所在**：这个对象同时承载了：

- 规划期发现元数据
- 搜索结果元数据
- 排序元数据
- 验证元数据
- 选源决策元数据
- 浏览器执行链路元数据

它已经不是“候选来源”，而是“候选来源在整个生命周期里的所有状态叠加体”。

**致命结果**：任何一个阶段改动字段，都可能影响其他阶段的理解。对象越来越胖，语义越来越不稳定。

#### 2、很多字段会被下游重复平铺到输出和前端 DTO，`SourceCandidate` 的膨胀会成倍扩散

`CollectorAgent` 会把这些字段继续写进输出：

```
resultEntry.put("trustTier", matchedCandidate == null || matchedCandidate.getTrustTier() == null
        ? null : matchedCandidate.getTrustTier().name());
resultEntry.put("trustTierLabel", matchedCandidate == null ? null : matchedCandidate.getTrustTierLabel());
resultEntry.put("rankingReasons", matchedCandidate == null ? null : matchedCandidate.getRankingReasons());
resultEntry.put("rankingSummary", matchedCandidate == null ? null : matchedCandidate.getRankingSummary());
resultEntry.put("browserTraceId", matchedCandidate == null ? null : matchedCandidate.getBrowserTraceId());
resultEntry.put("selectionStage", matchedCandidate == null ? null : matchedCandidate.getSelectionStage());
resultEntry.put("selectionReason", matchedCandidate == null ? null : matchedCandidate.getSelectionReason());
resultEntry.put("selectionSummary", matchedCandidate == null ? null : matchedCandidate.getSelectionSummary());
```

**问题所在**：`SourceCandidate` 一旦继续加字段，不只是模型类变胖，而是整条输出链、报告链、详情页链都会一起膨胀。

**致命结果**：后续任何新增候选元数据，都不是局部变更，而是会扩大到一整条数据契约。

#### 3、`selectionStage/selectionReason/selectionSummary` 会被多个阶段反复覆写，候选身份不再稳定

你现在已经有多个地方在改它：

- 规划期 provider 写 `PLANNED`
- 浏览器运行期写 `BROWSER`
- 验证阶段写 `VERIFIED / DISCARDED`
- 目标选择阶段再写 `SELECTED`

**问题所在**：同一条候选在流转过程中被不断“改身份证”。

**致命结果**：如果前端、审计或恢复现场拿到的是某个中间时刻的候选快照，就很难判断这条候选在整条链路里到底经历过什么。

### 优化方案：

#### 1、把候选基础信息和阶段性状态拆开

- **功能描述**：`SourceCandidate` 只保留来源基础事实；排序、验证、选择等阶段状态独立建模。
- **业务成效**：候选身份更稳定，阶段演化更可追踪。

#### 2、收敛候选输出字段，不要把所有内部元数据默认外抛

- **功能描述**：为前端和报告链定义精简视图对象，而不是直接透传巨型候选对象。
- **业务成效**：降低输出膨胀和契约耦合。


### SourcePlan

#### 1、`urls` 和 `candidates` 并存，规划期从一开始就制造了两份来源事实

```
public class SourcePlan {

    private String sourceType;
    private List<String> urls;
    private String notes;
    private List<SourceCandidate> candidates;
}
```

**问题所在**：同一个 `SourcePlan` 里，一方面有裸 URL 列表，一方面又有候选对象列表。两者都在表达“这个来源范围下有哪些可采集入口”。

**致命结果**：只要去重、筛选、补源、排序中任一处只更新了其中一份，`urls` 和 `candidates` 就会发生漂移。后续 `WorkflowFactory`、`SearchExecutionCoordinator`、前端预览都可能看到不同现场。

#### 2、`notes` 是自由文本，把规划原因、去重说明、预览占位说明全混在一起

例如 `WorkflowFactory` 会继续拼接：

```
private String appendDedupeNote(String notes, int duplicateCount) {
    String dedupeNote = "已与前序范围去重 " + duplicateCount + " 条重复来源";
    // ...
    return notes + "；" + dedupeNote;
}
```

**问题所在**：`notes` 当前既可能是源发现说明，也可能是去重说明，还可能是“预览占位计划”的补充解释。

**致命结果**：规划原因没有结构化字段，只能持续往一段自由文本上叠加，后期可解释性和可解析性都会变差。

### 优化方案：

#### 1、在 `SourcePlan` 中只保留一份正式来源事实

- **功能描述**：要么只保留 `candidates`，URL 作为候选字段存在；要么把 URL 列表降级为派生字段。
- **业务成效**：减少双份真相。

#### 2、把 `notes` 拆成结构化说明字段

- **功能描述**：例如拆成 `discoveryNotes`、`dedupeNotes`、`previewNotes`。
- **业务成效**：规划说明更可追踪，也更适合前端展示。


### SearchSourceProvider

#### 1、接口默认 `descriptor()` 会把 `providerKey` 退化成类名，路由身份天然不稳定

```
default SearchSourceProviderDescriptor descriptor() {
        return SearchSourceProviderDescriptor.builder()
                .providerKey(getClass().getSimpleName())
                .displayName(getClass().getSimpleName())
                .capabilities(List.of("WEB_SEARCH"))
                .defaultEnabled(true)
                .defaultFailOpen(true)
                .build();
}
```

**问题所在**：如果某个 Provider 没有显式覆盖 `descriptor()`，它的稳定身份就会退化成 Java 类名。

**致命结果**：类重命名、代理类包裹、测试替身替换，都会直接影响 providerKey。配置、路由、审计身份会一起抖动。

#### 2、接口默认 `isAvailable()` 永远返回 `true`，为“伪可用 provider”留了大门

```
default boolean isAvailable() {
        return true;
}
```

**问题所在**：这意味着只要实现类忘了覆写，路由器就会默认认为它具备参与路由的能力。

**致命结果**：一个没有 API Key、没有 endpoint、没有浏览器条件的 provider，也可能被默认纳入路由，最终在运行期靠异常和 fail-open 收场。

### 优化方案：

#### 1、把 `descriptor()` 和 `isAvailable()` 改成强制实现

- **功能描述**：不要再给出过于乐观的默认实现，要求每个 Provider 显式声明身份和可用性。
- **业务成效**：减少“忘记实现导致系统默许”的风险。

#### 2、接口层就定义稳定 provider 身份协议

- **功能描述**：providerKey 应来自稳定常量，而不是反射类名。
- **业务成效**：配置与审计维度更稳。


### SourceDiscoveryService

#### 1、`discoverForPreview` 默认直接回退到正式 `discover`，预览/正式语义从接口层就已经不纯

```
default List<SourcePlan> discoverForPreview(String competitorName,
                                            List<String> providedUrls,
                                            List<String> requestedScopes) {
    return discover(competitorName, providedUrls, requestedScopes);
}
```

**问题所在**：接口注释明明说“预览阶段只生成轻量规划结果，不阻塞在实时搜索或浏览器补源上”，但默认实现却直接走正式发现流程。

**致命结果**：任何新的实现类只要忘了覆写 `discoverForPreview`，创建页预览就会无意中触发真实搜索甚至重型补源逻辑。

#### 2、接口返回 `List<SourcePlan>`，但没有表达发现阶段的预算、模式和失败原因

```
List<SourcePlan> discover(String competitorName, List<String> providedUrls, List<String> requestedScopes);
```

**问题所在**：返回值只保留了计划结果，没有结构化表达：

- 用了哪些 discovery method
- 哪些 provider 被跳过
- 是否预览降级
- 为什么没有结果

**致命结果**：源发现接口只能告诉你“结果是什么”，却很难告诉你“为什么会是这样”。这让上游计划解释能力先天不足。

### 优化方案：

#### 1、取消危险的预览默认回退

- **功能描述**：`discoverForPreview` 要么强制实现，要么返回显式 unsupported，而不是偷偷跑正式逻辑。
- **业务成效**：预览不会再意外触发重型发现流程。

#### 2、为信息源发现返回结构化诊断结果

- **功能描述**：增加 `discoveryTrace`、`providerDecisions`、`previewDegraded` 等结构化字段。
- **业务成效**：规划期结果更可解释。


### SourceCollector

#### 1、采集接口没有承接运行期策略，导致页面采集阶段天然拿不到节点级控制权

```
CollectedPage collect(String url, String competitorName, String sourceType);
List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType);
```

**问题所在**：`SourceCollector` 接口当前只接收 URL、竞品名、sourceType，没有 `SearchRuntimePolicy`、没有 timeout override、没有 blockedSignals、没有 page budget。

**致命结果**：搜索阶段精心构造出来的运行期策略，到了真正采集页面正文这一步，大量控制项根本传不下来。采集层和搜索层的策略链是断的。

#### 2、`CollectedPage.metadata` 继续用字符串塞 JSON，结构化契约退回到了文本时代

```
class CollectedPage {
    private String metadata;
}
```

而实现类会写：

```
String metadata = "{\"collector\":\"" + escapeJson(collector) + "\",\"collectedAt\":\""
        + LocalDateTime.now().format(DTF) + "\"}";
```

**问题所在**：元数据不是对象，而是手拼 JSON 字符串。

**致命结果**：后续只要元数据结构扩展一点点，就会越来越难维护、难校验、难兼容。

### 优化方案：

#### 1、把采集接口升级为策略感知接口

- **功能描述**：在采集接口中引入节点级运行策略或采集上下文对象。
- **业务成效**：搜索和采集终于共享同一套控制平面。

#### 2、把 `metadata` 改成强类型对象或 Map

- **功能描述**：不要再手拼 JSON 字符串。
- **业务成效**：元数据扩展和前后端消费都更稳定。


### PlaywrightPageCollector

#### 1、批量采集会静默把 URL 数量截断到 5 条，系统没有任何显式预算说明

```
private List<String> safelyLimitUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        return urls.size() > 5 ? urls.subList(0, 5) : urls;
}
```

**问题所在**：这是一个非常关键的采集预算限制，但它既不是配置项，也没有显式写入计划和进度协议。

**致命结果**：上游哪怕选出了更多正式目标，到了采集器这里也可能被静默砍成前 5 条。用户只会觉得“为什么明明选中了很多，最后采得这么少”。

#### 2、页面正文回收是否允许“超时后部分恢复”，这里没有读取节点级策略，直接写死为全局默认

```
if (!fallbackPolicy.shouldRecoverPartialContentOnTimeout(null)
        && "search_timeout".equals(fallbackPolicy.classifyRuntimeFailure(originalException))) {
    return null;
}
```

**问题所在**：这里直接给 `shouldRecoverPartialContentOnTimeout(null)` 传了 `null`，等于只看全局默认值，不看当前节点实际的 `SearchRuntimePolicy`。

**致命结果**：即便某个任务已经在节点级明确要求“超时后允许/禁止回收部分正文”，正式采集阶段也不会真正遵守。

#### 3、HTTP 正文有效性阈值是硬编码常量，短内容但高价值页面容易被误杀

```
private static final int MIN_HTTP_CONTENT_LENGTH = 280;
private static final int MIN_MEANINGFUL_TEXT_UNITS = 80;
```

以及：

```
if (content.length() < MIN_HTTP_CONTENT_LENGTH) {
    return false;
}
```

**问题所在**：很多价格页、产品公告页、本地化帮助页虽然正文不长，但信息密度很高。这里的硬阈值过于粗暴。

**致命结果**：高价值的“短页面”会被当成“正文过薄”直接判失败，进而触发更重的浏览器采集或被整体丢弃。

### 优化方案：

#### 1、把批量采集上限显式纳入节点配置或采集策略

- **功能描述**：不要再在采集器内部静默 hardcode `5`。
- **业务成效**：选源数量与实际采集数量的关系可解释。

#### 2、让采集器真正读取节点级恢复策略

- **功能描述**：部分正文回收策略应由运行时上下文传入，而不是偷看全局默认。
- **业务成效**：节点级策略终于能影响正式页面采集。

#### 3、把 HTTP 有效性阈值参数化，并结合页面类型判断

- **功能描述**：对 PRICING/NEWS/DOCS 等不同页面类型采用不同阈值。
- **业务成效**：减少对短而值钱页面的误杀。


### PageContentExtractionSupport

#### 1、正文块选择规则仍然是高长度偏好，容易把“大块导航/聚合容器”误判成正文

```
double score = text.length();
if (classifier.contains("article")) {
    score += 220;
}
if (classifier.contains("main")) {
    score += 180;
}
if (classifier.contains("content") || classifier.contains("prose") || classifier.contains("markdown")) {
    score += 140;
}
if (classifier.contains("docs") || classifier.contains("documentation")) {
    score += 120;
}
if (classifier.contains("body")) {
    score -= 260;
}
```

**问题所在**：本质仍然是“长度为主，语义标签加减分”。如果页面结构很复杂，一个包含大量导航、相关推荐、页脚内容的超大容器，依然可能凭长度和类名优势胜出。

**致命结果**：下游拿到的所谓“正文”可能掺杂大量外围噪声，直接污染候选原因、页面摘要和正式证据文本。

#### 2、去噪规则基本只覆盖英文网站习惯，中文站点和国内云厂商页面的噪声并没有系统处理

```
if (normalized.startsWith("skip to content")
        || normalized.startsWith("cookie")
        || normalized.contains("accept cookies")
        || normalized.contains("privacy preference")
        || normalized.contains("subscribe")
        || normalized.contains("breadcrumb")) {
    continue;
}
```

**问题所在**：这里的去噪规则几乎全是英文文案。

**致命结果**：中文站点常见的：

- 返回顶部
- 热门推荐
- 立即咨询
- 免费试用
- 相关推荐

这些噪声块会更容易混进正文结果里。

#### 3、`truncateForSummary` 直接硬截前缀，摘要很容易砍断关键信号

```
return normalized.length() <= safeMaxLength
        ? normalized
        : normalized.substring(0, safeMaxLength);
```

**问题所在**：这里没有句子边界、段落边界、信号词优先级的概念，纯粹按字符数截断。

**致命结果**：用于候选原因或预览摘要时，很可能把价格、发布日期、关键接口名这种高价值信息正好截断掉。

### 优化方案：

#### 1、正文抽取评分从“长度优先”升级为“语义密度优先”

- **功能描述**：对价格词、文档词、日期信号、代码块密度等内容信号加权，而不是主要看字符长度。
- **业务成效**：真正有信息密度的块更容易胜出。

#### 2、补齐中文站点噪声规则

- **功能描述**：增加中文导航、营销、相关推荐、咨询 CTA 等噪声标记。
- **业务成效**：中文竞品场景的正文纯度更高。

#### 3、摘要截断按句段和信号词做智能保留

- **功能描述**：优先保留完整句子、日期、价格和命中的关键业务词。
- **业务成效**：摘要更短但更有用。


### CollectorAgent

#### 1、运行中输出是“整包 JSON 全量重写”，页面越多写放大越严重

```
private void persistRunningOutput(AgentContext context,
                                  CollectorNodeConfig config,
                                  String sourceType,
                                  SearchExecutionPlan executionPlan,
                                  List<SearchProgressSnapshot> progressSnapshots,
                                  List<SourceCandidate> sourceCandidates,
                                  List<SearchCollectionTarget> targets,
                                  SearchExecutionTrace executionTrace,
                                  List<Map<String, Object>> results,
                                  int successCounter) {
        // ...
        String outputJson = buildCollectorOutput(
                config,
                sourceType,
                context.getTaskRagPromptContext(),
                SearchExecutionResult.builder()
                        .executionPlan(executionPlan)
                        .progressSnapshot(progressSnapshots == null || progressSnapshots.isEmpty()
                                ? null
                                : progressSnapshots.get(progressSnapshots.size() - 1))
                        .progressSnapshots(progressSnapshots)
                        .sourceCandidates(sourceCandidates)
                        .selectedTargets(targets)
                        .executionTrace(executionTrace)
                        .build(),
                progressSnapshots == null ? List.of() : progressSnapshots,
                results,
                successCounter,
                targets == null ? List.of() : targets
        );
        nodeRepository.findByTaskIdAndNodeName(context.getTaskId(), context.getCurrentNodeName())
                .ifPresent(node -> {
                    if (node.getStatus() != TaskNodeStatus.RUNNING) {
                        return;
                    }
                    node.setOutputData(outputJson);
                    nodeRepository.save(node);
                });
}
```

**问题所在**：每次进度推进、每抓完一页、每次补写结果，都会重新构造并落库整份 `outputJson`。这里面还包含：

- 全量 `sourceCandidates`
- 全量 `progressSnapshots`
- 全量 `selectedTargets`
- 全量 `results`

**致命结果**：候选越多、页面越多、进度越细，单节点运行期间的数据库写放大就越严重。后续只要采集页数稍微变多，节点输出会迅速膨胀。

#### 2、输出契约里写的仍是配置态 Query/Policy，不是实际执行态 Query/Policy

```
output.put("sourceCandidates", searchExecutionResult.getSourceCandidates() == null ? List.of() : searchExecutionResult.getSourceCandidates());
output.put("searchMode", config.getSearchMode());
output.put("searchQueries", config.getSearchQueries() == null ? List.of() : config.getSearchQueries());
output.put("searchRuntimePolicy", config.getSearchRuntimePolicy());
output.put("searchExecutionPlan", searchExecutionResult.getExecutionPlan());
output.put("searchExecutionTrace", searchExecutionResult.getExecutionTrace());
```

**问题所在**：这里同时把：

- 配置态 `searchQueries`
- 配置态 `searchRuntimePolicy`

和：

- 运行期 `searchExecutionPlan`
- 运行期 `searchExecutionTrace`

塞进了同一份输出里。

**致命结果**：只要运行时对 Query、fallback order、runtime policy 做了补全或修正，详情页和前端就可能同时看到“两套不一致的搜索现场”。

#### 3、没选出采集目标时，步骤状态是 `SKIPPED`，节点最终状态却是 `FAILED`

```
if (targets.isEmpty()) {
    markCollectStep(executionPlan, SearchExecutionStep.StepStatus.SKIPPED, "未选出可采集来源，跳过页面抓取");
    // ...
    return AgentResult.builder()
            .status(TaskNodeStatus.FAILED)
            .outputData(outputJson)
            .outputSummary(actionableError)
            .reasoningSummary(searchExecutionResult.getReasoningSummary())
            .errorMessage(actionableError)
            .build();
}
```

**问题所在**：页面抓取步骤被标记为 `SKIPPED`，但整节点却被标为 `FAILED`。

**致命结果**：前端和日志如果同时消费“步骤进度”和“节点状态”，就会看到一种很别扭的现场：

- 步骤被跳过
- 节点却失败了

这会直接放大状态解释成本。

### 优化方案：

#### 1、把运行中输出改成增量落盘或轻量快照落盘

- **功能描述**：不要每次都重写整份大 JSON，应区分进度增量和最终结果。
- **业务成效**：显著降低节点运行期写放大。

#### 2、输出契约统一使用“生效后的执行态数据”

- **功能描述**：前端和详情页优先消费 `executionPlan/executionTrace`，不要再混用 config 原值。
- **业务成效**：展示现场与真实执行一致。

#### 3、收口“跳过”和“失败”的状态语义

- **功能描述**：如果节点失败，就让步骤也有对应失败语义；如果只是无目标跳过，就不要再把整节点标成失败。
- **业务成效**：状态体系更一致。


### AnalysisTaskService

#### 1、节点洞察把 `verifyResultPage` 和 `verifyCandidates` 混成一回事

```
.verifyResultPage(config.path("verifyResultPage").asBoolean(
        config.path("verifyCandidates").asBoolean(false)))
```

以及配置摘要里也一样：

```
boolean verificationEnabled = config.path("verifyResultPage").asBoolean(
        config.path("verifyCandidates").asBoolean(false));
```

**问题所在**：`verifyResultPage` 是“是否校验结果页正文”，`verifyCandidates` 是“是否对候选做运行期验证”。这两个语义本来就不是一回事。

**致命结果**：任务详情页和节点配置摘要很可能把“开了候选验证”误显示成“开了结果页验证”，前端看到的搜索治理开关会失真。

#### 2、节点洞察里的 Query 仍然优先拿输出/配置中的计划值，不是实际执行值

```
String searchMode = defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID");
List<String> searchQueries = readStringList(output == null ? null : output.get("searchQueries"));
if (searchQueries.isEmpty()) {
    searchQueries = readStringList(config.get("searchQueries"));
}
```

**问题所在**：这里完全没有优先读取：

- `searchExecutionPlan.searchQueries`
- `searchExecutionTrace.browserExecutedQueries`

**致命结果**：详情页展示出来的 Query，很可能只是“计划时写进 config 的原始 Query”，而不是运行期真正执行过的 Query。

#### 3、配置摘要完全基于 `nodeConfig` 构建，运行期补源和计划演化现场都会被抹掉

```
int candidateCount = config.path("sourceCandidates").isArray() ? config.path("sourceCandidates").size() : 0;
int queryCount = config.path("searchQueries").isArray() ? config.path("searchQueries").size() : 0;
int stepCount = config.path("searchExecutionPlan").path("steps").isArray()
        ? config.path("searchExecutionPlan").path("steps").size()
        : 0;
```

**问题所在**：这里统计的是规划期 `nodeConfig` 里的候选数、Query 数和步骤数，而不是运行后真正产生的：

- supplemented candidates
- selected targets
- actual execution steps

**致命结果**：节点卡片摘要可能永远停留在“计划时的静态现场”，对用户理解真实执行帮助很有限。

### 优化方案：

#### 1、洞察层把验证开关拆开显示

- **功能描述**：候选验证和结果页验证必须分别展示，不能再互相兜底。
- **业务成效**：节点洞察更真实。

#### 2、Query 展示优先消费执行态字段

- **功能描述**：先看 `executionPlan` 和 `executionTrace`，配置值只做兜底。
- **业务成效**：详情页看到的是“实际怎么搜”，不是“原本打算怎么搜”。

#### 3、配置摘要和运行摘要分层

- **功能描述**：保留一份静态规划摘要，再增加一份运行后摘要，不要混成一条。
- **业务成效**：计划和现场不会再互相冒充。


### ReportService

#### 1、报告级搜索审计明确只吃 `searchExecutionTrace`，前面已经写出的 `searchAudit` richer 现场被整体丢掉

```
/**
 * 报告级搜索审计摘要来自采集节点输出中的 searchExecutionTrace。
 * 这样不引入新表，也能把恢复、降级和补源结果提升到正式交付视图。
 */
private SearchAuditOverview buildSearchAuditOverview(List<TaskNode> nodes) {
        // ...
        JsonNode output = readJson(node.getOutputData());
        JsonNode config = readJson(node.getNodeConfig());
        JsonNode trace = output == null ? null : output.path("searchExecutionTrace");
        boolean traceRecorded = trace != null && !trace.isMissingNode() && !trace.isNull();
        // ...
}
```

**问题所在**：`CollectorAgent` 明明已经把：

- `searchExecutionTrace`
- `searchAudit`

同时写进了节点输出，但 `ReportService` 这里只消费 `searchExecutionTrace`。

**致命结果**：报告页最终拿到的是一个裁剪过的搜索审计现场，像：

- progress history
- sourceCandidates snapshot
- selectedTargets snapshot
- audit checkpoint

这些 richer 现场全没了。

#### 2、报告审计对象完全建立在 `node.outputData` 之上，缺少独立稳定的数据事实源

```
JsonNode output = readJson(node.getOutputData());
JsonNode config = readJson(node.getNodeConfig());
JsonNode trace = output == null ? null : output.path("searchExecutionTrace");
```

**问题所在**：这里没有直接读取审计实体、也没有稳定快照表，完全依赖节点输出 JSON。

**致命结果**：只要节点输出被 rerun 覆盖、精简、截断或结构调整，报告页的搜索审计就会一起漂移。报告视图缺少稳定的数据底座。

### 优化方案：

#### 1、报告页优先消费正式 `searchAudit` 快照

- **功能描述**：`searchExecutionTrace` 只做摘要入口，完整审计信息应来自结构化快照。
- **业务成效**：报告交付视图更完整、可追溯。

#### 2、为报告审计建立稳定事实源

- **功能描述**：不要只依赖节点输出 JSON，可考虑独立持久化搜索审计快照或查询专用视图。
- **业务成效**：报告页不会因为节点输出形状变化而失真。


### EvidenceQueryService

#### 1、证据查询层仍然把 `pageMetadata` JSON 字符串当基础事实源，结构化字段只是后补

```
private Map<String, Object> mergeStructuredMetadata(EvidenceSource evidence) {
        Map<String, Object> metadata = parseJsonMap(evidence.getPageMetadata());
        putIfPresent(metadata, "sourceType", evidence.getSourceType());
        putIfPresent(metadata, "discoveryMethod", evidence.getDiscoveryMethod());
        putIfPresent(metadata, "domain", evidence.getSourceDomain());
        putIfPresent(metadata, "reason", evidence.getDiscoveryReason());
        putIfPresent(metadata, "publishedAt", evidence.getPublishedAt());
        putIfPresent(metadata, "totalScore", evidence.getSourceScore());
        return metadata;
}
```

**问题所在**：这里的主流程仍然是：

1. 先解析 `pageMetadata` JSON
2. 再把部分结构化列覆盖进去

**致命结果**：只要 `pageMetadata` 里有旧字段、脏字段或错字段，它们就会继续混进证据查询结果。结构化列并没有真正取代文本元数据。

#### 2、元数据一旦解析失败，就退化成 `{ raw: json }`，证据查询契约会被污染

```
private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return new java.util.LinkedHashMap<>(Map.of("raw", json));
        }
}
```

**问题所在**：这不是严格失败，也不是结构化降级，而是把一段原始字符串塞进 `raw` 字段继续往上游传。

**致命结果**：前端和报告页会收到形状不稳定的 metadata map。某些证据是结构化字段，某些证据却只剩一个 `raw` 文本，契约不一致非常明显。

#### 3、`EvidenceInfo` 只提升了一小部分搜索元数据，剩余重要搜索解释字段继续埋在通用 metadata 里

```
return new EvidenceInfo(
        evidence.getEvidenceId(),
        evidence.getTitle(),
        evidence.getUrl(),
        evidence.getContentSnippet(),
        evidence.getCompetitorName(),
        evidence.getCollectedAt(),
        evidence.getSourceType(),
        evidence.getDiscoveryMethod(),
        evidence.getSourceDomain(),
        evidence.getDiscoveryReason(),
        evidence.getPublishedAt(),
        evidence.getSourceScore(),
        readBoolean(metadata, "verified"),
        readString(metadata, "verificationReason"),
        readString(metadata, "searchQuery"),
        readString(metadata, "searchEngine"),
        readInteger(metadata, "resultRank"),
        readString(metadata, "browserTraceId"),
        readString(metadata, "selectionReason"),
        readString(metadata, "selectionStage"),
        readStringList(metadata, "matchedSignals"),
        metadata
);
```

**问题所在**：这里提升了 `verified/searchQuery/searchEngine/...` 这些字段，但像：

- `selectionSummary`
- `trustTier`
- `trustTierLabel`
- `rankingReasons`
- `rankingSummary`

仍然只留在通用 `metadata` map 里。

**致命结果**：证据查询接口已经部分结构化、部分半结构化，前端消费会持续分裂。

### 优化方案：

#### 1、结构化列优先，文本元数据降级为补充层

- **功能描述**：不要再让 `pageMetadata` 成为 merge 基底，应反过来由结构化列主导。
- **业务成效**：证据查询结果更稳定。

#### 2、解析失败时返回结构化错误，而不是 `raw` 混入主契约

- **功能描述**：解析失败应记录告警或单独字段，不要污染 metadata 主体。
- **业务成效**：接口形状保持稳定。

#### 3、把剩余高价值搜索解释字段也显式提升

- **功能描述**：对前端和报告页真正需要的字段补 typed projection，而不是全塞 metadata。
- **业务成效**：搜索可解释性在证据层真正落地。


### SourceSelectionReason

#### 1、这个枚举名叫“结构化决策原因”，但当前只收口了两种原因，远远覆盖不了真实现场

```
public enum SourceSelectionReason {

    LOW_SIGNAL_UTILITY_PAGE("DISCARDED", "识别为低价值工具页，已在排序阶段降权留档"),
    KEEP_FRESHER_SEARCH_RESULT("SELECTED", "优先保留更新且更可靠的搜索候选");
}
```

**问题所在**：整个搜索链路里实际已经出现了大量决策语义：

- `PLANNED`
- `BROWSER`
- `VERIFIED`
- `DISCARDED`
- `SELECTED`

但枚举里只正式建模了两条。

**致命结果**：这会形成一种“部分语义枚举化、其余语义仍散落字符串”的半结构化状态，后续治理难度更高。

#### 2、`KEEP_FRESHER_SEARCH_RESULT` 把 `selectionStage` 写成 `SELECTED`，阶段语义被偷换

```
KEEP_FRESHER_SEARCH_RESULT("SELECTED", "优先保留更新且更可靠的搜索候选");
```

**问题所在**：这个 reason 本质上只是“重复 URL 仲裁时，保留了较新的候选”；它并不等于“已经成为最终正式采集目标”。

**致命结果**：只要这个 reason 被写回候选对象，就会把“去重阶段的保留结果”和“正式选中目标”混成一个阶段语义。

### 优化方案：

#### 1、把决策原因和阶段状态彻底拆开

- **功能描述**：`SourceSelectionReason` 只表达 reason code，`selectionStage` 由独立状态体系承载。
- **业务成效**：原因和阶段不会再互相污染。

#### 2、把真实发生的决策原因完整枚举化

- **功能描述**：不要只收口两条最小实现原因，应把规划、验证、补源、去重、正式选中等主要原因纳入统一协议。
- **业务成效**：搜索决策解释终于可统一治理。


### SourceTrustTier

#### 1、枚举里定义了 `weight`，但当前系统根本没有真正使用它

```
public enum SourceTrustTier {

    HIGH(1.0D, "高可信"),
    MEDIUM(0.75D, "中可信"),
    LOW(0.5D, "低可信");

    private final double weight;
    private final String displayName;
}
```

**问题所在**：从当前工程引用关系看，`displayName` 会被展示层使用，但 `getWeight()` 没有进入实际打分链路。

**致命结果**：这个枚举表面上像是“可信度参与定量排序”，实际上更像“只给 UI 展示的标签”。模型语义会误导维护者。

#### 2、可信层级与真实排序总分没有强绑定，用户看到“高可信”不等于它真的排得更靠前

**问题所在**：`SourceCandidateRanker` 最终输出了：

- `trustTier`
- `trustTierLabel`

但总分计算并不是直接基于 `SourceTrustTier.weight`。

**致命结果**：前端可能看到一条来源被标成“高可信”，但它在排序中未必真正得到同等权重体现。展示语义和打分语义存在脱节。

### 优化方案：

#### 1、如果 `weight` 不参与排序，就不要假装它是评分权重

- **功能描述**：要么让 `weight` 真正进入排序公式，要么删除该字段，只保留标签语义。
- **业务成效**：模型表达更诚实。

#### 2、让可信度标签与排序逻辑保持一致

- **功能描述**：前端展示的“高/中/低可信”应能映射到真实排序影响。
- **业务成效**：用户更容易理解为什么某条来源排在前面。


### CollectorNodeInsightResponse

#### 1、这个洞察 DTO 把计划态、运行态、恢复态强行揉进一个对象里，页面语义天然混乱

```
@Schema(description = "Search queries")
private List<String> searchQueries;

@Schema(description = "Current search progress")
private SearchProgressSnapshot searchProgress;

@Schema(description = "Search execution plan")
private SearchExecutionPlan searchExecutionPlan;

@Schema(description = "Search execution trace")
private SearchExecutionTrace searchExecutionTrace;

@Schema(description = "Search progress snapshots")
private List<SearchProgressSnapshot> searchProgressSnapshots;

@Schema(description = "Source candidates")
private List<SourceCandidate> sourceCandidates;

@Schema(description = "Selected targets")
private List<CollectorSelectedTargetSummary> selectedTargets;
```

**问题所在**：这个对象里同时承载了：

1. 配置时的计划信息
2. 执行中的运行信息
3. 执行后的候选/选中结果

但 DTO 名字只是 `CollectorNodeInsightResponse`，没有显式区分这些字段的来源层级。

**致命结果**：详情页一旦同时展示 `searchExecutionPlan`、`searchProgress`、`selectedTargets`，前端很容易误把“计划中的 query / 候选数”和“实际执行过的 query / 真实选中结果”当成同一层事实。

#### 2、洞察页透出了 `searchExecutionTrace`，却没有正式透出 `searchAudit`，最完整的恢复现场被截断了

```
return CollectorNodeInsightResponse.builder()
        .searchProgress(convertValue(output == null ? null : output.get("searchProgress"), SearchProgressSnapshot.class))
        .searchExecutionPlan(convertValue(
                output != null && output.has("searchExecutionPlan") ? output.get("searchExecutionPlan") : config.get("searchExecutionPlan"),
                SearchExecutionPlan.class))
        .searchExecutionTrace(convertValue(
                output == null ? null : output.get("searchExecutionTrace"),
                SearchExecutionTrace.class))
        .searchProgressSnapshots(convertList(
                output == null ? null : output.get("searchProgressSnapshots"),
                new TypeReference<List<SearchProgressSnapshot>>() {
                }))
        .sourceCandidates(sourceCandidates)
        .selectedTargets(selectedTargets)
        .build();
```

**问题所在**：`CollectorAgent` 输出里已经有 `searchAudit`，但洞察响应只拿了 `searchExecutionTrace` 和 progress snapshots，没有把正式 checkpoint / recovery advice / richer selected target scene 一并抬上来。

**致命结果**：详情页看到的是“审计摘要”，不是“真实审计底稿”。一旦发生 rerun、resume、fallback，用户无法从洞察接口直接判断这次结果到底是不是复用了旧 checkpoint。

#### 3、`sourceCandidates` 和 `selectedTargets` 两套结果并排返回，但没有稳定关联键，前端只能靠 URL 自己拼

```
private List<SourceCandidate> sourceCandidates;
private List<CollectorSelectedTargetSummary> selectedTargets;
```

**问题所在**：候选集合和最终选中摘要是两套不同投影，但 `selectedTargets` 里没有 candidateId、candidateIndex 之类的稳定关联主键。

**致命结果**：前端如果想高亮“这个 selected target 对应的是哪个 source candidate”，就只能拿 URL 做弱关联。去重、规范化 URL 或补源改写后，很容易出现两边对不上的问题。

### 优化方案：

#### 1、把 Collector 洞察对象拆成计划态 / 运行态 / 审计态三个稳定分区

- **功能描述**：避免一个 DTO 同时承载多层语义，至少在字段分组上明确 `plan/runtime/audit`。
- **业务成效**：详情页不会再把计划值和实际值混着展示。

#### 2、洞察接口优先透出正式 `searchAudit`

- **功能描述**：`searchExecutionTrace` 继续做摘要，但完整恢复与复用事实应来自 `searchAudit`。
- **业务成效**：节点详情真正具备回放价值。

#### 3、为 selected target 增加稳定候选关联键

- **功能描述**：不要让前端继续靠 URL 猜测候选归属。
- **业务成效**：候选列表和正式目标列表可以稳定联动。


### CollectorSelectedTargetSummary

#### 1、`targetSelectionSummary` 和 `selectionSummary` 是同一语义的双字段输出，契约已经开始分叉

```
@Schema(description = "Human readable target selection summary")
private String targetSelectionSummary;

@Schema(description = "Normalized selection summary")
private String selectionSummary;
```

以及构造时：

```
item.put("targetSelectionSummary", target.getCandidate().getSelectionSummary());
item.put("selectionSummary", target.getCandidate().getSelectionSummary());
```

**问题所在**：这两个字段当前写入的是同一份值，只是名字不同。

**致命结果**：后续只要有一方先改文案、另一方没跟进，前端就会遇到“同一目标两份选中解释不一致”的脏状态。兼容字段长期保留会把治理成本持续抬高。

#### 2、最终选中目标摘要几乎丢掉了搜索来源证据链，只保留了选中结果解释

```
private String url;
private String title;
private Boolean verified;
private String browserTraceId;
private String selectionStage;
private String selectionReason;
private String selectionSummary;
private String trustTier;
private Double totalScore;
private java.util.List<String> rankingReasons;
private String rankingSummary;
private Boolean hasPrefetchedPage;
```

**问题所在**：这里没有：

- `searchQuery`
- `searchEngine`
- `resultRank`
- `provider`

这些字段。

**致命结果**：最终进入采集阶段的 target，到了详情页只知道“为什么被选中”，却不知道“它最初是怎么被搜出来的”。搜索差的时候，问题无法从最终目标摘要反推出根因。

#### 3、`selectionStage` / `selectionReason` 仍然是裸字符串，和前面的决策枚举体系没有真正闭合

```
@Schema(description = "Selection stage")
private String selectionStage;

@Schema(description = "Selection reason")
private String selectionReason;
```

**问题所在**：这个 DTO 继续消费字符串阶段和字符串原因，而不是一个真正稳定的决策协议。

**致命结果**：详情页、报告页、证据页对“选中原因”的解释会继续各说各话，后续很难做统计和聚合。

### 优化方案：

#### 1、兼容字段尽快退场，只保留一套正式摘要字段

- **功能描述**：`targetSelectionSummary` 明确标记废弃，最终只保留 `selectionSummary`。
- **业务成效**：选中解释字段不会继续双轨运行。

#### 2、把搜索来源证据链补回 selected target 摘要

- **功能描述**：至少补齐 query、engine、rank、provider 这些高价值追溯字段。
- **业务成效**：最终采集目标可以反查搜索质量问题。

#### 3、让 reason/stage 走统一协议

- **功能描述**：不要再让展示 DTO 继续透传自由字符串。
- **业务成效**：多页面统计和解释才能统一。


### TaskNodeConfigSummary

#### 1、这个“结构化配置摘要”只会总结 `nodeConfig`，但它被拿去展示运行中的搜索节点，天然会把计划值当成实际值

```
@Schema(description = "Planned source candidate count")
private Integer candidateCount;

@Schema(description = "Planned search query count")
private Integer queryCount;

@Schema(description = "Planned execution step count")
private Integer stepCount;
```

以及：

```
int candidateCount = config.path("sourceCandidates").isArray() ? config.path("sourceCandidates").size() : 0;
int queryCount = config.path("searchQueries").isArray() ? config.path("searchQueries").size() : 0;
int stepCount = config.path("searchExecutionPlan").path("steps").isArray()
        ? config.path("searchExecutionPlan").path("steps").size()
        : 0;
```

**问题所在**：字段名上已经写了 `Planned`，但这个 DTO 同时出现在任务详情页节点数据里，用户会天然把它理解成“当前节点实际配置/实际执行摘要”。

**致命结果**：搜索执行过程中即使 query 被重写、候选被补源追加、step 被 fallback 改写，这里展示的仍然只是原始计划值，页面会持续误导排障。

#### 2、它没有承载 checkpoint、fallback、降级这些搜索运行前提，导致“配置摘要”只能说计划，不能说上下文

```
return TaskNodeConfigSummary.builder()
        .summaryText(summary.toString())
        .competitorName(competitor)
        .sourceType(sourceType)
        .searchMode(searchMode)
        .candidateCount(candidateCount)
        .queryCount(queryCount)
        .stepCount(stepCount)
        .browserSearchEnabled(browserEnabled)
        .verificationEnabled(verificationEnabled)
        .preferredDomains(readStringList(config.get("preferredDomains")))
        .competitorUrls(readStringList(config.get("competitorUrls")))
        .discoveryNotes(textOrNull(config, "discoveryNotes"))
        .build();
```

**问题所在**：这里没有：

- `searchAuditCheckpoint`
- `reusedCheckpoint`
- `fallbackPolicy`
- `runtimeSupplementMethod`

等关键上下文字段。

**致命结果**：用户即便在“配置摘要”里看到了搜索模式和 query 数，也无法知道这个节点是不是复用了旧搜索现场，或者是不是在受限模式下退化执行。

### 优化方案：

#### 1、把计划摘要和运行摘要分开建模

- **功能描述**：`TaskNodeConfigSummary` 只承载静态计划配置，运行态摘要交给单独 DTO。
- **业务成效**：页面不会再把计划值当实际值。

#### 2、为搜索节点补齐 checkpoint / fallback / supplement 运行前提

- **功能描述**：节点摘要里至少要能看出本次执行是不是沿用了旧现场、有没有降级。
- **业务成效**：排障入口更直接。


### ReportResponse

#### 1、报告接口顶层已经高度结构化，但搜索审计仍被压扁成一个轻量 overview，无法承载真正的搜索解释链

```
@Schema(description = "Search audit overview aggregated from collector nodes")
private SearchAuditOverview searchAuditOverview;
```

其中 overview 本身只有：

```
public static class SearchAuditOverview {
    private Integer collectorNodeCount;
    private Integer traceRecordedCount;
    private Integer checkpointRecoveredCount;
    private Integer degradedCount;
    private Integer providerFallbackCount;
    private Integer browserBlockedCount;
    private Integer plannedCandidateCount;
    private Integer verifiedCandidateCount;
    private Integer supplementedCandidateCount;
    private Integer selectedCandidateCount;
    private List<CollectorSearchAudit> collectors;
}
```

**问题所在**：这里能表达的是聚合计数和 collector 级别摘要，不能表达：

- 真实执行 query
- 计划与运行差异
- 候选排序明细
- 完整 checkpoint/audit scene

**致命结果**：报告页虽然号称带了搜索审计，但用户看到的仍然只是压缩版 KPI，而不是“为什么搜差、差在哪一步”的真正解释链。

#### 2、`CollectorSearchAudit` 只有摘要字段，没有候选/目标级别的可解释事实，报告层继续丢细节

```
public static class CollectorSearchAudit {
    private String nodeName;
    private TaskNodeStatus nodeStatus;
    private String competitorName;
    private String sourceType;
    private Boolean traceRecorded;
    private String auditMessage;
    private String supplementMethod;
    private Boolean resumedFromCheckpoint;
    private String checkpointSource;
    private Boolean degraded;
    private String degradationReason;
    private Boolean providerFallbackUsed;
    private String fallbackDecision;
    private String browserTraceId;
    private String browserBlockedReason;
    private Integer browserBlockedCount;
    private String recoveryCheckpoint;
    private Integer plannedCandidateCount;
    private Integer verifiedCandidateCount;
    private Integer supplementedCandidateCount;
    private Integer selectedCandidateCount;
    private List<String> selectedUrls;
    private String errorMessage;
}
```

**问题所在**：这里没有 query 列表、provider 决策链、selected target 的排序解释，也没有验证阶段的 discard 原因分布。

**致命结果**：报告交付页只能告诉你“这个采集节点大概降级过”，却说不清“为什么这几个 URL 最后被留下”。报告层的搜索治理价值被大幅削弱。

#### 3、`EvidenceInfo` 仍然挂了一个 `pageMetadata` 泛型 map，报告接口在最关键的证据层继续保留半结构化负担

```
public static class EvidenceInfo {
    private String evidenceId;
    private String title;
    private String url;
    private String sourceType;
    private String discoveryMethod;
    private String sourceDomain;
    private String discoveryReason;
    private String publishedAt;
    private Double sourceScore;
    private Boolean verified;
    private String verificationReason;
    private String searchQuery;
    private String searchEngine;
    private Integer resultRank;
    private String browserTraceId;
    private String selectionReason;
    private String selectionStage;
    private List<String> matchedSignals;
    private Map<String, Object> pageMetadata;
}
```

**问题所在**：搜索接口已经开始 typed projection，但最后又把剩余字段塞回 `pageMetadata`。

**致命结果**：报告响应对搜索证据的契约是半结构化的。前端越想做精细展示，就越要回退去解析 metadata map。

### 优化方案：

#### 1、报告层引入正式搜索审计明细模型

- **功能描述**：overview 继续保留，但不应代替完整 collector-level audit detail。
- **业务成效**：报告能真正解释搜索质量，而不只是报数。

#### 2、把候选/目标级别的解释字段提升到报告契约

- **功能描述**：至少补 query、provider、ranking summary、verification discard reason 等核心字段。
- **业务成效**：报告搜索审计从“摘要”升级为“可复盘”。

#### 3、继续压缩 `pageMetadata` 的职责

- **功能描述**：让证据层把高频搜索解释字段全部显式 typed 化。
- **业务成效**：报告接口更稳定。


### TaskProgressSnapshot

#### 1、任务热快照只保留任务级节点计数，没有任何采集节点搜索现场，断线恢复时根本看不到“搜到哪一步了”

```
private Long taskId;
private String taskStatus;
private String currentStage;
private String errorMessage;
private String statusSummary;
private int totalNodes;
private int completedNodes;
private int waitingRetryNodeCount;
private int waitingInterventionNodeCount;
private int compensatedNodeCount;

@Builder.Default
private List<String> activeNodeNames = new ArrayList<>();
```

**问题所在**：这个快照完全是任务维度的轻量摘要，没有任何 collector 维度字段。

**致命结果**：即使搜索节点正在执行 verify、browser supplement、checkpoint recovery，这个热快照也只能告诉你“某个节点活跃中”，无法告诉你实际卡在哪个搜索子阶段。

#### 2、`currentStage` 只是第一个活跃节点名，不是搜索步骤名，Collector 节点的进度被再次压扁

```
for (TaskNode node : nodes) {
    if (isActiveStatus(node.getStatus())) {
        activeNodeNames.add(node.getNodeName());
        if (currentStage == null) {
            currentStage = readableStageName(node);
        }
    }
}
```

**问题所在**：这里选择的是“活跃节点显示名”，而不是 `searchProgress.status`、`SearchExecutionStep` 之类的真正搜索阶段。

**致命结果**：前端看到的“当前阶段”只到 DAG 节点层，不到搜索链路层。用户会觉得搜索一直卡在采集节点，但不知道是在搜、验、补还是选。

### 优化方案：

#### 1、任务热快照补充 Collector 级运行现场摘要

- **功能描述**：不要求把全量 searchAudit 塞进热快照，但至少要有当前 query、当前步骤、fallback 状态等轻量字段。
- **业务成效**：断线恢复和任务工作台能真正感知搜索进度。

#### 2、`currentStage` 对 Collector 节点下钻到搜索步骤

- **功能描述**：采集节点活跃时，优先展示搜索子阶段而不是只展示节点名。
- **业务成效**：用户能快速定位卡点。


### TaskSnapshotCacheService

#### 1、Redis 热快照只缓存 `TaskProgressSnapshot`，这让搜索运行现场在缓存层天然丢失

```
public void saveTaskSnapshot(TaskProgressSnapshot snapshot) {
    if (snapshot == null || snapshot.getTaskId() == null) {
        return;
    }
    try {
        stringRedisTemplate.opsForValue().set(
                buildSnapshotKey(snapshot.getTaskId()),
                objectMapper.writeValueAsString(snapshot),
                redisProperties.getSnapshotTtl());
    } catch (Exception e) {
        log.warn("save task snapshot to redis failed, taskId={}", snapshot.getTaskId(), e);
    }
}
```

**问题所在**：这里缓存的是轻量任务快照，而不是搜索节点的结构化搜索现场。

**致命结果**：一旦页面、恢复流程或事件流需要从 Redis 直接拿“当前搜索进展”，就会发现这里只剩任务级摘要，没有 query、candidate、checkpoint 这些真正有价值的信息。

#### 2、节点输出缓存是“整包 JSON 字符串”直塞 hash，搜索恢复继续依赖大对象反序列化

```
public void cacheNodeOutput(Long taskId, String nodeName, String outputData) {
    if (taskId == null || nodeName == null || nodeName.isBlank() || outputData == null) {
        return;
    }
    try {
        String runtimeKey = buildRuntimeKey(taskId);
        stringRedisTemplate.opsForHash().put(runtimeKey, nodeName, outputData);
        stringRedisTemplate.expire(runtimeKey, redisProperties.getRuntimeTtl());
    } catch (Exception e) {
        log.warn("cache node output to redis failed, taskId={}, nodeName={}", taskId, nodeName, e);
    }
}
```

**问题所在**：缓存层没有把 `searchProgress`、`searchExecutionTrace`、`searchAudit` 这些高频恢复字段拆出来。

**致命结果**：后续任何恢复/展示逻辑都必须重新解析整包 outputData。输出对象一旦继续膨胀，Redis 读写放大和反序列化成本都会越来越高。

#### 3、运行时清理是整任务双 key 删除，没有“保留搜索 checkpoint、清理热状态”的分层能力

```
public void evictTaskRuntime(Long taskId) {
    if (taskId == null) {
        return;
    }
    try {
        stringRedisTemplate.delete(buildSnapshotKey(taskId));
        stringRedisTemplate.delete(buildRuntimeKey(taskId));
    } catch (Exception e) {
        log.warn("evict task runtime from redis failed, taskId={}", taskId, e);
    }
}
```

**问题所在**：这里没有区分：

- 可丢弃的运行热状态
- 仍有复用价值的搜索 checkpoint

**致命结果**：只要执行清理，搜索链路的缓存复用能力就被一并抹掉，无法做更细颗粒度的 rerun / resume 策略。

### 优化方案：

#### 1、热快照与搜索现场缓存分层

- **功能描述**：任务工作台摘要和 collector 搜索现场应该分别缓存。
- **业务成效**：缓存既轻量，又不丢搜索关键事实。

#### 2、对节点输出做结构化索引

- **功能描述**：高频字段单独存，整包 output 作为兜底。
- **业务成效**：恢复和展示不必每次都反序列化整包 JSON。

#### 3、清理策略区分热状态和可复用 checkpoint

- **功能描述**：不是所有运行态缓存都应该在 rerun 时一起删光。
- **业务成效**：搜索链路可以支持更细的恢复策略。


### TaskRecoveryService

#### 1、恢复服务生成和重建的仍然是任务级轻量快照，搜索恢复现场没有被正式纳入恢复产物

```
taskSnapshotCacheService.saveTaskSnapshot(TaskProgressSnapshot.fromTask(
        task,
        resolution.getStatus(),
        task.getErrorMessage(),
        recoveryScopeNodes));
```

以及：

```
TaskProgressSnapshot rebuiltSnapshot = TaskProgressSnapshot.fromTask(
        task,
        resolution.getStatus(),
        resolution.getErrorMessage(),
        recoveryScopeNodes);
taskSnapshotCacheService.saveTaskSnapshot(rebuiltSnapshot);
```

**问题所在**：恢复服务每次更新的都是 `TaskProgressSnapshot`，没有生成任何 collector 级搜索恢复摘要。

**致命结果**：服务重启后任务虽然“恢复了”，但用户和上层服务仍然看不到这次恢复到底复用了哪些搜索节点现场，恢复质量不可解释。

#### 2、服务启动恢复先重置中断节点，再基于节点状态推导任务状态，中断中的部分搜索现场会直接消失

```
boolean recoverable = resetInterruptedNodes(task.getId());
if (!recoverable) {
    task.setStatus(AnalysisTaskStatus.FAILED);
    ...
    continue;
}

List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
List<TaskNode> recoveryScopeNodes = resolveRecoveryScopeNodes(task, nodes);
NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
        recoveryPolicy.resolveTaskExecution(task, recoveryScopeNodes);
```

**问题所在**：恢复流程先做状态回滚，再做恢复状态判定。对于运行中断掉的 Collector 节点，如果它的部分搜索进度还没来得及落成正式 checkpoint，这部分现场就不会进入恢复视图。

**致命结果**：用户只会看到“任务重新变成待执行/待恢复”，却不知道上一次搜索到底推进到哪里。搜索问题排查会反复失忆。

#### 3、恢复服务只在任务级别处理可恢复性，没有把搜索 checkpoint 复用能力纳入统一恢复决策

```
private List<TaskNode> resolveRecoveryScopeNodes(AnalysisTask task, List<TaskNode> nodes) {
    if (task == null || nodes == null || nodes.isEmpty() || task.getCurrentPlanVersionId() == null) {
        return nodes == null ? List.of() : nodes;
    }
    ...
    return activePlanNodes.isEmpty() ? nodes : activePlanNodes;
}
```

**问题所在**：这里的恢复范围是按计划版本和受影响节点算的，但没有显式判断哪些 Collector 节点具备可复用搜索 checkpoint、哪些只能全量重搜。

**致命结果**：恢复流程知道“哪些节点该重跑”，却不知道“这些节点能不能复用搜索现场”。搜索恢复策略仍然散落在其他服务里。

### 优化方案：

#### 1、恢复服务输出正式的 Collector 恢复摘要

- **功能描述**：把 checkpoint 复用、fallback、上次搜索阶段这些信息纳入恢复产物。
- **业务成效**：恢复过程真正可解释。

#### 2、在重置前先保护中断中的搜索现场

- **功能描述**：服务重启恢复前，应优先固化 collector 的可复用搜索痕迹。
- **业务成效**：中途中断不会让搜索排查彻底失忆。

#### 3、把“是否可复用 checkpoint”纳入统一恢复决策

- **功能描述**：恢复服务不应只判断节点状态，还要判断搜索现场质量。
- **业务成效**：rerun / resume 策略更细。


### NodeExecutionRecoveryPolicy

#### 1、中断重启时直接清空 `outputData`，对 Collector 来说等于把未固化的搜索现场整体抹掉

```
private void resetNodeForInterruptedRestart(TaskNode node) {
    node.setStatus(TaskNodeStatus.PENDING);
    node.setControlState(TaskNodeControlState.NONE);
    node.setInputData(null);
    node.setOutputData(null);
    node.setErrorMessage("Node interrupted by service restart");
    node.setInterventionReason(null);
    node.setFailureCategory(null);
    node.setStartedAt(null);
    node.setCompletedAt(null);
    node.setLastAttemptAt(null);
    node.setNextRetryAt(null);
    node.setRetryCount(0);
}
```

**问题所在**：这个策略对所有节点一视同仁，但 Collector 节点的 `outputData` 里可能已经有：

- `searchProgress`
- `searchExecutionTrace`
- `searchProgressSnapshots`
- 甚至部分 `searchAudit`

这些高价值现场。

**致命结果**：只要中断发生在正式 checkpoint 落盘之前，这些搜索现场会被恢复策略直接抹掉，搜索问题复盘几乎无从下手。

#### 2、恢复策略只按 DAG 节点状态推导任务结果，完全不理解搜索链路里的“降级成功”与“高质量成功”差别

```
if (finalReviewPassed || dynamicPatchReviewPassed || (!initialReviewPresent && allRequiredSucceeded) || initialReviewPassed) {
    return TaskExecutionResolution.builder()
            .status(AnalysisTaskStatus.SUCCESS)
            .errorMessage(null)
            .completedNodes(completedNodes)
            .totalNodes(totalNodes)
            .build();
}
```

**问题所在**：只要节点状态满足成功条件，任务就会被判成 `SUCCESS`。这里不看：

- `searchExecutionTrace.degraded`
- `browserBlockedCount`
- `providerFallbackUsed`
- `selectedCandidateCount` 是否异常偏低

**致命结果**：搜索链路即便是在严重退化状态下勉强跑完，任务恢复策略仍会把它视为标准成功，治理层拿不到风险信号。

#### 3、策略本身不知道 Collector 节点的 checkpoint 语义，必须依赖外围服务手工补洞

```
public void resetNodeForRerun(TaskNode node, boolean clearOutput) {
    if (node == null) {
        return;
    }
    node.setStatus(TaskNodeStatus.PENDING);
    node.setControlState(TaskNodeControlState.NONE);
    node.setInputData(null);
    if (clearOutput) {
        node.setOutputData(null);
    }
    ...
}
```

**问题所在**：这个类是通用恢复策略，但它没有内建“Collector 节点在清空 output 前应先抽取 searchAuditCheckpoint”这类规则。

**致命结果**：只要调用方忘了像 `AnalysisTaskService.reuseSearchCheckpointIfPresent(...)` 那样预先搬运 checkpoint，恢复策略就会把搜索复用能力直接清空。策略正确性依赖调用顺序，非常脆弱。

### 优化方案：

#### 1、恢复策略识别 Collector 节点的高价值搜索现场

- **功能描述**：不要再把所有节点的 `outputData` 一刀切清空。
- **业务成效**：服务中断后的搜索恢复能力更强。

#### 2、任务成功判定纳入搜索退化信号

- **功能描述**：至少把严重降级、浏览器受阻、候选异常稀少这些状态提升为风险语义。
- **业务成效**：任务状态更真实。

#### 3、把 checkpoint 抽取规则内聚到恢复策略

- **功能描述**：减少对调用方先后顺序的隐式依赖。
- **业务成效**：恢复链路更稳。


### TaskNodeResponse

#### 1、节点响应对象把“原始大 JSON”和“结构化洞察 DTO”同时暴露，搜索字段出现双出口

```
@Schema(description = "Node config JSON")
private String nodeConfig;

@Schema(description = "Structured node configuration summary")
private TaskNodeConfigSummary configSummaryData;

@Schema(description = "Structured collector node insight")
private CollectorNodeInsightResponse collectorInsight;

@Schema(description = "Raw output JSON")
private String outputData;
```

**问题所在**：同一个采集节点的搜索现场，现在至少有四个出口：

1. `nodeConfig`
2. `configSummaryData`
3. `collectorInsight`
4. `outputData`

**致命结果**：前端和调试人员会不断在“看原始 JSON 还是看结构化摘要”之间摇摆。一旦两边字段存在投影差异，排障结论就会不一致。

#### 2、预览态和运行态共用同一个响应模型，但 `collectorInsight` 的事实层级并不相同

```
@PostMapping("/preview")
public ApiResponse<List<TaskNodeResponse>> previewWorkflow(@Valid @RequestBody CreateTaskRequest request) {
    return ApiResponse.success(taskService.previewWorkflow(request));
}

@GetMapping("/{id}/nodes")
public ApiResponse<List<TaskNodeResponse>> getTaskNodes(@PathVariable Long id) {
    return ApiResponse.success(taskService.getTaskNodes(id));
}
```

**问题所在**：预览接口返回的 `collectorInsight` 只来自配置；运行态节点返回的 `collectorInsight` 则混入了 output 里的实际执行结果。

**致命结果**：前端如果只按字段是否存在来渲染，而不区分“这是 preview 还是 runtime”，就会把计划值和真实值混成一套视图。

### 优化方案：

#### 1、原始 JSON 与结构化洞察明确主次关系

- **功能描述**：`TaskNodeResponse` 应明确哪个字段是正式消费契约，哪个只是调试兜底。
- **业务成效**：前端不会再重复消费两套搜索现场。

#### 2、预览态和运行态拆分视图协议

- **功能描述**：不要再让 preview 和 runtime 共用完全相同的 collector 洞察结构。
- **业务成效**：页面语义更稳定。


### DagExecutor

#### 1、续跑时会把 Redis 缓存和数据库成功节点的整包输出重新灌入共享上下文，旧搜索现场可能被无差别继承

```
private void seedSharedOutputs(AgentContext context, List<TaskNode> nodes) {
    taskSnapshotCacheService.getCachedNodeOutputs(context.getTaskId())
            .forEach(context::putSharedOutput);
    for (TaskNode node : nodes) {
        if (node.getStatus() == TaskNodeStatus.SUCCESS
                && node.getOutputData() != null
                && !node.getOutputData().isBlank()) {
            context.putSharedOutput(node.getNodeName(), node.getOutputData());
        }
    }
}
```

**问题所在**：这里塞回上下文的是“整包节点输出字符串”，而不是筛选后的稳定共享事实。

**致命结果**：只要旧 Collector 输出里带着过时的 `searchExecutionTrace`、`selectedTargets` 或低质量搜索结果，下游节点就会把它当作正常上游事实继续消费。

#### 2、节点成功后缓存的也是整包 output，搜索输出膨胀会直接放大缓存层和续跑链路压力

```
node.setOutputData(result.getOutputData());
...
sharedContext.putSharedOutput(savedNode.getNodeName(), result.getOutputData());
taskSnapshotCacheService.cacheNodeOutput(taskId, node.getNodeName(), result.getOutputData());
```

**问题所在**：执行器没有把“下游共享必要字段”和“调试可选字段”分开存。

**致命结果**：搜索输出对象越丰富，Redis 缓存、上下文传递、续跑回灌的成本就越高，而且污染面越来越大。

#### 3、SSE 搜索事件只透出 `searchProgress/searchExecutionTrace/searchProgressSnapshots`，正式 `searchAudit` 被遗漏

```
JsonNode searchProgress = output.get("searchProgress");
JsonNode executionTrace = output.get("searchExecutionTrace");
JsonNode progressSnapshots = output.get("searchProgressSnapshots");
if (searchProgress != null && !searchProgress.isNull()) {
    payload.put("searchProgress", objectMapper.convertValue(searchProgress, new TypeReference<Map<String, Object>>() {
    }));
}
if (executionTrace != null && !executionTrace.isNull()) {
    payload.put("searchExecutionTrace", objectMapper.convertValue(executionTrace, new TypeReference<Map<String, Object>>() {
    }));
}
if (progressSnapshots != null && progressSnapshots.isArray()) {
    payload.put("searchProgressSnapshots", objectMapper.convertValue(progressSnapshots, new TypeReference<List<Map<String, Object>>>() {
    }));
}
```

**问题所在**：实时事件流里没有 `searchAudit`，也没有 `selectedTargets` 的正式审计投影。

**致命结果**：前端实时观察到的只是进度摘要，不是完整搜索现场。节点完成后想回放“为什么选中这些来源”，仍然得再去查节点详情 JSON。

#### 4、兜底搜索事件把复杂搜索阶段压缩成 `SUCCESS/FAILED + 完成补源/补源失败`，搜索过程细节被彻底抹平

```
payload.put("searchProgress", Map.of(
        "status", node.getStatus() == TaskNodeStatus.SUCCESS ? "SUCCESS" : "FAILED",
        "currentStep", node.getStatus() == TaskNodeStatus.SUCCESS ? "完成补源" : "补源失败",
        "message", defaultIfBlank(node.getErrorMessage(),
                node.getStatus() == TaskNodeStatus.SUCCESS ? "采集节点已完成，使用最小事件留痕兜底。" : "采集节点执行失败，请查看节点详情。"),
        "updatedAt", node.getCompletedAt() == null ? LocalDateTime.now() : node.getCompletedAt()
));
```

**问题所在**：兜底事件只保留一个极粗粒度状态，没有 query、验证、fallback、选源这些关键信息。

**致命结果**：一旦正式搜索事件缺失，前端看到的就是一条“补源完成/失败”。搜索指令差在哪一步，事件层完全解释不了。

### 优化方案：

#### 1、共享上下文只注入稳定业务事实

- **功能描述**：不要把 Collector 的整包输出直接回灌给下游。
- **业务成效**：旧搜索脏现场不会被续跑链路继续放大。

#### 2、缓存与共享字段做裁剪分层

- **功能描述**：真正给下游复用的字段、给恢复复用的字段、给调试用的全量输出应分开存放。
- **业务成效**：执行链路更轻，也更可控。

#### 3、实时事件补齐正式审计字段

- **功能描述**：事件层至少应能回放 `searchAudit` 的关键结构。
- **业务成效**：实时排障不必反复下钻大 JSON。


### TaskEventPublisher

#### 1、任务快照事件只发任务级摘要，不发 Collector 搜索子阶段，顶部状态条天然看不到“搜到哪一步”

```
payload.put("status", snapshot.getTaskStatus());
payload.put("currentStage", snapshot.getCurrentStage());
payload.put("statusSummary", snapshot.getStatusSummary());
payload.put("completedNodes", snapshot.getCompletedNodes());
payload.put("totalNodes", snapshot.getTotalNodes());
payload.put("waitingRetryNodeCount", snapshot.getWaitingRetryNodeCount());
payload.put("waitingInterventionNodeCount", snapshot.getWaitingInterventionNodeCount());
payload.put("compensatedNodeCount", snapshot.getCompensatedNodeCount());
payload.put("activeNodeNames", snapshot.getActiveNodeNames());
payload.put("errorMessage", snapshot.getErrorMessage());
```

**问题所在**：任务级事件里没有任何 Collector 搜索进度摘要字段。

**致命结果**：用户从任务总览层只能看到“当前节点在采集”，却不知道采集节点内部是卡在 Query、验证、浏览器补源还是正式选源。

#### 2、搜索事件直接吃 `Map<String, Object>`，契约没有被正式类型收口

```
public TaskStreamEvent publishSearchProgressEvent(Long taskId, String nodeName, Map<String, Object> progressPayload) {
    return publish(TaskEventType.SEARCH_PROGRESS, taskId, nodeName, progressPayload);
}
```

**问题所在**：发布器对搜索事件 payload 不设 DTO 边界，谁来发、发什么字段、字段名是否稳定，完全靠上游自觉。

**致命结果**：搜索事件会持续漂移。前端一旦基于某个字段名实现，后续极容易因为 payload 形状变化而悄悄失效。

### 优化方案：

#### 1、任务级事件补充 Collector 子阶段摘要

- **功能描述**：`TASK_SNAPSHOT` 应能最少量反映当前搜索子阶段。
- **业务成效**：顶部工作台不再只停留在 DAG 粒度。

#### 2、为搜索事件建立正式 DTO

- **功能描述**：不要再让搜索事件长期停留在 `Map<String, Object>`。
- **业务成效**：前端消费更稳定。


### TaskEventReplayService

#### 1、回放快照仍然只来自 `TaskProgressSnapshot`，搜索事件恢复时缺少 Collector 级重建能力

```
public TaskReplayFrame planReplay(Long taskId, String lastCursor) {
    Optional<TaskProgressSnapshot> snapshotOptional = taskRecoveryService.getTaskSnapshotOrRebuild(taskId);
    TaskStreamEvent snapshotEvent = snapshotOptional.map(this::toSnapshotEvent).orElse(null);
    List<TaskStreamEvent> replayEvents = resolveReplayEvents(taskId, lastCursor);
    ...
}
```

以及：

```
private TaskStreamEvent toSnapshotEvent(TaskProgressSnapshot snapshot) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", snapshot.getTaskStatus());
    payload.put("currentStage", snapshot.getCurrentStage());
    payload.put("completedNodes", snapshot.getCompletedNodes());
    payload.put("totalNodes", snapshot.getTotalNodes());
    payload.put("activeNodeNames", snapshot.getActiveNodeNames());
    payload.put("errorMessage", snapshot.getErrorMessage());
    payload.put("updatedAt", snapshot.getUpdatedAt());
    ...
}
```

**问题所在**：断线恢复时先补的是任务级快照，不是 Collector 搜索快照。

**致命结果**：前端重连后能知道任务还活着，但不知道刚才那个搜索节点已经推进到了哪个搜索步骤，恢复观察价值有限。

#### 2、回放事件完全依赖 `TaskSseHub` 最近缓存，超过窗口的搜索历史无法重建

```
private List<TaskStreamEvent> resolveReplayEvents(Long taskId, String lastCursor) {
    List<TaskStreamEvent> recentEvents = taskSseHub.getRecentEvents(taskId);
    Optional<TaskEventCursor> parsedCursor = TaskEventCursor.parse(lastCursor)
            .filter(cursor -> cursor.taskId().equals(taskId));
    if (parsedCursor.isEmpty()) {
        return recentEvents;
    }
    ...
}
```

**问题所在**：如果断线时间稍长，或者搜索事件很多，回放就只能给“最近还活着的一小段事件”。

**致命结果**：长搜索任务或多节点任务的中前段搜索过程会直接丢失，前端重连后看到的是截断现场。

### 优化方案：

#### 1、回放链路补充 Collector 搜索快照

- **功能描述**：任务快照之外，还应有节点级搜索恢复帧。
- **业务成效**：断线恢复后仍能定位搜索卡点。

#### 2、最近事件缓存之外增加持久化回放底座

- **功能描述**：不要让搜索历史只活在内存最近队列里。
- **业务成效**：长任务回放更可靠。


### TaskEventStreamController

#### 1、控制器只提供“直接订阅 SSE”，没有提供显式的搜索回放/搜索快照接口

```
@GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
        @PathVariable Long taskId,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
        @org.springframework.web.bind.annotation.RequestParam(name = "cursor", required = false) String cursor) {
    String resumeCursor = (lastEventId != null && !lastEventId.isBlank()) ? lastEventId : cursor;
    return taskEventReplayService.subscribe(taskId, resumeCursor);
}
```

**问题所在**：控制器层只暴露了一个 SSE 订阅入口，没有给前端一个“先取节点级搜索回放帧，再接实时流”的正式 HTTP 接口。

**致命结果**：前端如果想更稳定地恢复搜索现场，只能自己拼“任务详情 + 节点详情 + SSE”三段式流程，接口职责不清晰。

#### 2、恢复游标只到任务流级别，没有搜索节点级别的更细 replay 边界

**问题所在**：`Last-Event-ID/cursor` 是全任务共享游标，而不是 Collector 节点级游标。

**致命结果**：如果一个任务并发产生很多节点事件，搜索节点的事件恢复会被其他节点的事件流量稀释，回放精度不够。

### 优化方案：

#### 1、补充显式 replay 接口

- **功能描述**：让前端可以正式请求“当前搜索快照 + 增量事件计划”。
- **业务成效**：搜索观察流程更稳。

#### 2、细化搜索节点 replay 边界

- **功能描述**：不要只提供任务级游标。
- **业务成效**：搜索事件恢复更精确。


### TaskSseHub

#### 1、最近事件缓冲区固定只有 `200` 条，长任务下搜索细粒度进度很容易被冲掉

```
private static final int MAX_RECENT_EVENTS = 200;
```

以及：

```
private void appendRecentEvent(TaskStreamEvent event) {
    Deque<TaskStreamEvent> queue = recentEventsByTask.computeIfAbsent(event.getTaskId(), ignored -> new ArrayDeque<>());
    synchronized (queue) {
        queue.addLast(event);
        while (queue.size() > MAX_RECENT_EVENTS) {
            queue.removeFirst();
        }
    }
}
```

**问题所在**：搜索链路一旦开始频繁发 `SEARCH_PROGRESS`，很快就会把早期事件挤掉。

**致命结果**：断线重连时，前端拿到的只是尾部几十步事件，中间完整搜索过程无法恢复。

#### 2、最近事件只在内存里，服务重启后搜索事件历史整体蒸发

```
private final Map<Long, Deque<TaskStreamEvent>> recentEventsByTask = new ConcurrentHashMap<>();
```

**问题所在**：这个 replay 缓冲区没有落 Redis、数据库或 outbox。

**致命结果**：服务重启后，即使搜索节点之前已经产出过很丰富的事件，回放链路也只能退回任务级快照，搜索过程细节全丢。

### 优化方案：

#### 1、提高搜索事件的独立缓冲能力

- **功能描述**：不要让所有任务事件共用一个过小的最近队列语义。
- **业务成效**：搜索过程更容易回放完整。

#### 2、为关键搜索事件提供持久化底座

- **功能描述**：至少要让重要搜索阶段和审计事件可跨重启回放。
- **业务成效**：搜索观察不会因进程重启失忆。


### AgentContext

#### 1、共享上下文用 `Map<String, String>` 保存节点输出，Collector 的结构化搜索现场被再次降回原始字符串

```
@Builder.Default
private Map<String, String> sharedState = new ConcurrentHashMap<>();

public String getSharedOutput(String nodeName) {
    return sharedState.get(nodeName);
}

public void putSharedOutput(String nodeName, String output) {
    sharedState.put(nodeName, output);
}
```

**问题所在**：从执行器灌入上下文开始，所有上游节点输出都被统一压成字符串。

**致命结果**：下游如果要读取 Collector 的搜索结果，只能重新解析 JSON。结构化搜索现场没有在上下文层得到正式建模。

#### 2、共享状态没有 plan/version/source 维度的约束，旧搜索输出被放回上下文后很难辨别来源

**问题所在**：`sharedState` 的 key 只有 `nodeName`，没有明确标记：

- 来自数据库还是 Redis
- 来自哪次 rerun
- 对应哪个搜索现场版本

**致命结果**：同一个节点被多次重跑后，排查人员很难从上下文层判断当前下游消费的是哪一轮搜索结果。搜索差的问题会被旧结果覆盖或掺杂。

### 优化方案：

#### 1、共享上下文引入结构化上游投影

- **功能描述**：Collector 输出应至少以 typed search projection 的形式进入上下文。
- **业务成效**：下游消费更稳定。

#### 2、共享状态增加来源与版本标识

- **功能描述**：不要只靠 `nodeName` 做弱键。
- **业务成效**：续跑与多轮重跑时更容易辨别搜索现场来源。
