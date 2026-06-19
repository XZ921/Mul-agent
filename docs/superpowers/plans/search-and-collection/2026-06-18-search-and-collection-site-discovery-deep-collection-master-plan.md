# 搜索发现与站点深采集总纲计划

> 2026-06-18 评估结论：本次不是小修小补，而是一次中大型链路补齐。建议按多任务分阶段实施，避免把 URL 发现、候选扩展、站内链接递归、审计进度和配置治理混在一个提交里。

## 1. 背景问题

当前工程已经具备搜索、候选验证、采集、sourceUrls 追溯、进度快照和 checkpoint 复用能力，但链路仍偏向“给定 URL 后执行采集”。当用户只输入竞品名称时，例如“哔哩哔哩”，系统存在以下缺口：

- 规划期缺少可靠的官网/子站发现能力，无法稳定产出 `https://open.bilibili.com/doc/` 这类开放平台入口。
- 运行期即使通过搜索找到根域或入口页，也没有把搜索结果回灌给 direct discovery，再扩展 `/docs`、`/pricing`、`/help` 或 `open.`、`developer.`、`docs.` 子域。
- 采集层采完一个入口页就结束，不会提取页面内的 same-domain 文档链接，也不会进入“账号授权、用户管理、视频管理、SDK”等子页面继续采集。
- Playwright 已经不是采集主路径，但发现链路仍缺少 LLM、域名验证、sitemap、内部链接提取等低成本补强能力。

## 2. 工程量判断

结论：中大型，建议拆成 5 个主任务、1 个验收任务。

原因：

- 涉及搜索运行期 `SearchExecutionCoordinator`、规划期 `HeuristicSourceDiscoveryService`、直达发现 `SourceFamilyDirectDiscoveryPlanner`、采集执行 `WebPageCollectionExecutor`、采集协调 `CollectionExecutionCoordinator`、任务输出 `CollectorAgent` 等多个核心类。
- 需要新增至少 3 个独立能力类：域名发现、sitemap 发现、站内链接发现。
- 需要新增配置项和默认策略，控制最大深度、最大链接数、子域模板、sitemap 超时、LLM 是否启用。
- 需要保证 sourceUrls、进度快照、replayTimeline、collectionAudit 和 checkpoint 不被新递归链路破坏。
- 需要较完整测试覆盖，尤其是“只给竞品名”“搜索结果回灌”“入口页发现子链接”“深度限制”“重复链接去重”这些场景。

## 3. 总体目标

把当前链路从：

```text
竞品名/URL -> 搜索候选 -> 选择目标 -> 采集单页 -> 结束
```

升级为：

```text
竞品名
  -> 官网/子域/文档入口发现
  -> 搜索结果根域回灌与模板扩展
  -> sitemap 与子域模板补全候选池
  -> 多入口采集
  -> 页面内 same-domain 文档链接发现
  -> 限深递归采集
  -> sourceUrls / progress / audit 全链路可追溯
```

## 4. 设计边界

本轮只解决“发现入口并深入公开页面”的能力，不做以下事情：

- 不做登录态、OAuth、Cookie 注入。
- 不做无限爬虫，只做有限深度、有限数量的文档型链接发现。
- 不做全站内容镜像，只优先采集与 sourceType 匹配的高价值页面。
- 不改变已有 Agent 职责边界，采集 Agent 仍只负责采集；分析、撰写、质检链路不混入发现逻辑。

## 5. 多任务拆分

### 任务一：搜索结果回灌 direct discovery

目标：运行期 Browser/HTTP 搜索拿到候选 URL 后，提取根域并交给 `SourceFamilyDirectDiscoveryPlanner` 做路径模板扩展。

主要文件：

- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 修改 `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

关键能力：

- 从搜索候选提取 canonical root URL。
- 基于 root URL 生成 family template 候选。
- 与原搜索候选合并、去重、排序。
- 保留 discoveryMethod，例如 `SEARCH_ROOT_TEMPLATE`。
- 保留 sourceUrls，指向触发扩展的搜索结果 URL。

验收标准：

- 只给搜索结果 `https://www.bilibili.com` 时，可以扩展出 `/docs`、`/help` 等路径候选。
- 搜索结果为 `https://open.bilibili.com/doc/` 时，原入口候选保留，并能补充同 family 候选。
- 已有候选不会被重复加入。

### 任务二：子域模板扩展

目标：让 direct discovery 不只支持 `root + path`，还支持 `docs.root`、`developer.root`、`open.root` 等子域模板。

主要文件：

- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java`
- 修改 `backend/src/main/resources/application.yml`
- 新增或修改 `backend/src/test/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlannerTest.java`

关键能力：

- 在 source catalog 中增加 `directSubdomainTemplates`。
- 默认 official family 支持 `docs.{domain}`、`developer.{domain}`、`open.{domain}`、`help.{domain}`。
- 对中文互联网常见开放平台优先支持 `open.`。
- 每个子域候选要带 sourceFamilyKey、sourceFamilyRole、sourceUrls。

验收标准：

- 输入 `https://bilibili.com` 能生成 `https://open.bilibili.com` 候选。
- 输入深路径 `https://www.bilibili.com/some/page` 时，仍能基于根域生成子域候选。
- 非 official family 不受影响。

### 任务三：LLM/规则域名发现

目标：只给竞品名时，新增低成本域名发现入口，优先尝试 LLM JSON 返回和规则域名验证，再进入搜索 provider。

主要文件：

- 新增 `backend/src/main/java/cn/bugstack/competitoragent/search/CompetitorDomainDiscoveryService.java`
- 新增 `backend/src/main/java/cn/bugstack/competitoragent/search/DomainVerificationClient.java`
- 新增 `backend/src/main/java/cn/bugstack/competitoragent/search/DomainDiscoveryProperties.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- 修改 `backend/src/main/resources/application.yml`
- 新增 `backend/src/test/java/cn/bugstack/competitoragent/search/CompetitorDomainDiscoveryServiceTest.java`
- 修改 `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`
- 修改 `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`

关键能力：

- LLM 业务入口固定使用 `LlmClient.chatForJson(...)`，底层仍由现有 `ModelGateway -> OpenAiCompatibleClient -> DeepSeek/OpenAI-compatible provider` 承接，避免直接依赖 LangChain4j 客户端而绕开统一路由、重试和审计。
- `CompetitorDomainDiscoveryService` 内固定 prompt 模板，不能散落到调用方。
- system prompt 固定为：

```text
你是一个竞品分析助手。给定公司名称，返回其官方网站、文档站、开放平台、GitHub 仓库的 URL。只返回 JSON，不要解释。
```

- user prompt 模板固定为：

```text
公司: {competitorName}
```

- `responseSchema` 固定为：

```json
{
  "type": "object",
  "required": ["urls", "sourceUrls"],
  "properties": {
    "urls": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["url", "category", "confidence"],
        "properties": {
          "url": { "type": "string" },
          "category": { "type": "string", "enum": ["official", "docs", "open", "github", "pricing", "news", "other"] },
          "confidence": { "type": "number" },
          "reason": { "type": "string" },
          "sourceUrls": {
            "type": "array",
            "items": { "type": "string" }
          }
        }
      }
    },
    "sourceUrls": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}
```

- LLM 期望输出示例：

```json
{
  "urls": [
    {
      "url": "https://www.bilibili.com",
      "category": "official",
      "confidence": 0.92,
      "reason": "哔哩哔哩主站官网",
      "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
    },
    {
      "url": "https://open.bilibili.com/doc/",
      "category": "open",
      "confidence": 0.88,
      "reason": "哔哩哔哩开放平台文档入口",
      "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
    }
  ],
  "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
}
```

- LLM 输出必须解析为结构化对象，字段包含 `url`、`category`、`confidence`、`reason`、`sourceUrls`；缺少 `sourceUrls` 的 URL 项必须补 `llm://domain-discovery/{competitorName}`，但要追加 `SOURCE_URLS_SYNTHESIZED` 质量信号。
- 规则域名验证使用 HTTP HEAD/GET，带超时、重试和最大候选数。
- LLM 不可用时，不阻塞主链路，降级到搜索 provider。
- 新增配置段建议为 `search.discovery.domain`，至少包含 `llm-enabled`、`llm-timeout-millis`、`max-llm-candidates`、`verification-timeout-millis`、`max-retries`。
- `SearchCapabilityReadinessGuard` 必须输出 domain discovery readiness：是否启用 LLM 域名发现、当前 `LlmClient` 是否存在、LLM 发现超时是否大于 0、验证超时和重试次数是否合理。LLM readiness 只能作为辅助能力摘要，不作为启动期 hard fail。

验收标准：

- 只输入“哔哩哔哩”时，可以通过 LLM mock 返回 `https://www.bilibili.com`、`https://open.bilibili.com/doc/` 并进入候选池。
- LLM 失败时，服务返回空列表并记录降级原因，不抛出中断主流程的异常。
- 每个候选都包含非空 sourceUrls。
- readiness 摘要中可以看到 `domainDiscoveryLlmEnabled`、`domainDiscoveryLlmAvailable`、`domainDiscoveryVerificationTimeoutMillis` 等字段；当 LLM 未配置但功能开启时，启动日志给出明确 warn。

### 任务四：Sitemap 与 robots 发现

目标：对已发现根域或子域，尝试读取 `robots.txt` 和 `sitemap.xml`，补充文档、定价、帮助、开放平台入口。

主要文件：

- 新增 `backend/src/main/java/cn/bugstack/competitoragent/search/SitemapDiscoveryService.java`
- 新增 `backend/src/main/java/cn/bugstack/competitoragent/search/SitemapDiscoveryProperties.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- 修改 `backend/src/main/resources/application.yml`
- 新增 `backend/src/test/java/cn/bugstack/competitoragent/search/SitemapDiscoveryServiceTest.java`
- 修改 `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`

关键能力：

- 支持 `robots.txt` 中 sitemap 地址解析。
- 支持默认尝试 `/sitemap.xml`。
- 只保留与 sourceType 相关的高价值 URL。
- 设置最大 sitemap 数、最大 URL 数、单请求超时。
- 所有发现结果保留 sourceUrls 指向 sitemap 或 robots URL。
- 新增配置段建议为 `search.discovery.sitemap`，至少包含 `enabled`、`timeout-millis`、`max-sitemaps-per-domain`、`max-urls-per-sitemap`、`max-retries`。
- `SearchCapabilityReadinessGuard` 必须输出 sitemap readiness：是否启用、timeout 是否大于 0、最大 sitemap 数和最大 URL 数是否大于 0、重试次数是否非负。配置不合理时输出 warn，并在执行期自动降级为空结果，避免“能力加了但没配好，静默装死”。

验收标准：

- 给定 sitemap XML 后，可以提取 `/doc/`、`/docs/`、`/pricing` 等候选。
- sitemap 请求失败不影响搜索和采集主流程。
- 超过最大 URL 数时截断并记录质量信号。
- readiness 摘要中可以看到 `sitemapDiscoveryEnabled`、`sitemapTimeoutMillis`、`sitemapMaxUrlsPerSitemap`；当 timeout 或数量上限配置非法时，启动日志明确提示。

### 任务五：采集后内部链接发现与限深递归

目标：采集入口页后，提取 same-domain 高价值链接，生成二级采集目标，支持文档站深入但防止无限爬取。

主要文件：

- 新增 `backend/src/main/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryService.java`
- 新增 `backend/src/main/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryProperties.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- 修改 `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 新增 `backend/src/test/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryServiceTest.java`
- 修改 `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`
- 修改 `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

关键能力：

- 从 JinaReader 内容、Playwright metadata 或 HTML/Markdown 链接中提取 URL。
- 仅允许 same-domain 或同一注册域下的子域链接。
- 优先保留包含 `/doc`、`/docs`、`/api`、`/sdk`、`/reference`、`/guide`、`/help` 的链接。
- 默认最大深度 2，默认每个入口最多追加 10 个子链接，整个节点最多追加 30 个。
- 递归采集结果进入 collectionAudit、replayTimeline、sourceUrls。
- 进度信息明确显示当前是入口采集还是内部链接追加采集。

验收标准：

- 采集 `https://open.bilibili.com/doc/` mock 页面时，可以发现“账号授权、用户管理、视频管理、Android SDK”等链接并追加采集。
- 外域链接和重复链接被过滤。
- 达到最大深度或最大数量时停止，且不标记为失败。
- checkpoint resume 时，已成功采集的内部链接可以复用。

### 任务六：端到端验收与文档更新

目标：用可控 mock 场景证明“只给竞品名 -> 找到开放平台入口 -> 深入文档子页 -> 输出 sourceUrls 证据链”完整可用。

主要文件：

- 新增或修改 `backend/src/test/java/cn/bugstack/competitoragent/integration/SearchAndCollectionDeepDiscoveryIntegrationTest.java`
- 修改 `docs/future/2026-06-18-search-and-collection-discovery-gap-analysis.md`
- 新增 `docs/progress/2026-06-18-search-and-collection-site-discovery-deep-collection-progress.md`

关键能力：

- 构造“哔哩哔哩”竞品名 mock。
- mock LLM 返回 `open.bilibili.com/doc/`。
- mock 入口页包含多个文档卡片链接。
- 验证最终结果包含入口页和至少 2 个子页面 evidence。
- 验证每个 evidence 都有 sourceUrls。
- 验证 searchProgressSnapshots 和 collectionAudit 可回放。

验收标准：

- 单测和集成测试通过。
- 输出 JSON 中能看到根入口、内部子页、sourceUrls、replayTimeline。
- 失败降级场景不会破坏已有搜索采集链路。

## 6. 推荐实施顺序

推荐顺序：

1. 任务一：搜索结果回灌 direct discovery。
2. 任务二：子域模板扩展。
3. 任务三：LLM/规则域名发现。
4. 任务四：Sitemap 与 robots 发现。
5. 任务五：采集后内部链接发现与限深递归。
6. 任务六：端到端验收与文档更新。

其中任务一和任务二可以作为第一阶段小闭环，能先解决“搜到了根域但不会扩展”的问题；任务五是最大改动，应放到已有候选发现能力稳定后再做。

## 7. 风险与控制

- 风险一：内部链接递归导致采集量失控。控制方式：默认深度 2、每入口最多 10 条、每节点最多 30 条。
- 风险二：LLM 域名发现产生幻觉。控制方式：必须 HTTP 验证，未验证候选降低分数，不得作为唯一高置信来源。
- 风险三：搜索和采集进度语义被破坏。控制方式：新增进度阶段或扩展 message，不删除已有 `LOAD_CANDIDATES`、`VERIFY_TOP_CANDIDATES`、`BROWSER_SUPPLEMENT_SEARCH`、`SELECT_TARGETS`、`COLLECT_PAGES`。
- 风险四：sourceUrls 丢失。控制方式：新增候选、sitemap 候选、内部链接候选都必须补齐 sourceUrls，并增加契约测试。
- 风险五：改动过大导致现有 checkpoint 复用不稳定。控制方式：递归链接使用 canonical URL 作为稳定身份，复用现有 `CollectionExecutionCoordinator` 的 identity 匹配策略。

## 8. 预计工程量

- 任务一：0.5 到 1 天。
- 任务二：0.5 到 1 天。
- 任务三：1 到 1.5 天。
- 任务四：1 天。
- 任务五：2 到 3 天。
- 任务六：1 天。

总计约 6 到 8.5 个开发日。若只做第一阶段“能发现 open/doc 类入口”，可先完成任务一到任务三，约 2 到 3.5 个开发日。

## 9. 第一阶段最小可交付

第一阶段建议只交付：

- 搜索结果回灌 direct discovery。
- 子域模板扩展。
- LLM/规则域名发现。

第一阶段完成后，系统应能在只输入“哔哩哔哩”时更稳定地拿到 `https://open.bilibili.com` 或 `https://open.bilibili.com/doc/` 这类入口，但仍不承诺深入所有文档子页。深入子页能力由第二阶段任务五交付。
