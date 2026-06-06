# API 主搜与 Playwright 降级为最终采集器设计说明

## 1. 目标

本设计用于重构当前竞品采集链路中的“搜索、候选验证、正文采集”职责边界，把搜索主路径从 `Playwright` 驱动的公网 SERP 抓取，调整为 `API / 结构化搜索结果` 驱动的候选发现模式。

本次设计的核心目标不是“优化浏览器搜索”，而是：

1. 让 `Playwright` 永远不再承担搜索引擎职责。
2. 让搜索阶段在 `Qianfan / SerpApi / 通用 HTTP Search API / 已有候选 / 用户提供 URL` 之间完成闭环。
3. 让 `Playwright` 只在“最终已确认 URL 的正文采集”阶段作为渲染兜底工具存在。
4. 让 API 不可用时，系统仍能基于“已有候选 + 用户提供 URL”继续降级执行，而不是重新唤醒浏览器搜索。

## 2. 当前问题

当前工程的搜索质量差、稳定性差，不是单点问题，而是以下几个问题叠加放大：

1. `Playwright` 被同时用于搜索、候选验证和正文采集，职责过宽。
2. 搜索与采集共用同一个 `Playwright Browser` 单例，在并发节点执行时存在互相误伤。
3. 候选验证通过 `SourceCollector.collect()` 间接触发浏览器采集，导致“看似只是验证，实际又在跑浏览器”。
4. 搜索结果页解析依赖 DOM 结构，天然脆弱，且极易遭遇验证码、拦截页和误提取。
5. 搜索主链路超时后只能依赖质量不稳定的浏览器补源，无法稳定降级到结构化搜索能力。

这些问题使得系统在运行时经常表现为：

1. 搜索阶段大量失败。
2. 候选结果质量低，噪音链接多。
3. 正文采集阶段被搜索线程的浏览器重启误伤。
4. 最终产物虽然带有 `sourceUrls`，但来源本身并不可靠。

## 3. 方案对比

### 3.1 方案 A：纯配置止血

只关闭 `browserSearchEnabled` 和 `browserPreview`，调整搜索 fallback 顺序。

优点：

1. 改动小。
2. 可以快速止住最明显的浏览器搜索问题。

问题：

1. `CandidateVerifier` 仍会通过 `SourceCollector.collect()` 间接触发 `Playwright`。
2. 搜索与采集的职责边界仍然不清晰。
3. 无法从架构层保证“浏览器绝不再承担搜索职责”。

### 3.2 方案 B：搜索与采集彻底拆层

把“找路”和“破门”彻底拆开：

1. 搜索只负责结构化候选 URL。
2. 候选验证只负责轻量验证。
3. 正文采集只负责最终 URL 的内容获取。
4. `Playwright` 只存在于最终正文采集阶段。

优点：

1. 能从架构层彻底移除 `Playwright` 作为搜索引擎的角色。
2. 降级路径更清晰，更符合“API 不可用时继续执行”的产品要求。
3. 后续更容易替换搜索 Provider，而不影响采集层。

问题：

1. 改动范围大于纯配置调整。
2. 需要同时修改搜索编排、验证逻辑和默认配置语义。

### 3.3 方案 C：Playwright 独立 worker 化

把浏览器采集彻底移到独立进程或 worker，仅通过队列访问。

优点：

1. 长期架构最干净。
2. 可以进一步隔离浏览器崩溃对主进程的影响。

问题：

1. 对当前项目来说过重。
2. 超出本轮“先把搜索主路径救回来”的目标范围。

## 4. 采用方案

采用 `方案 B：搜索与采集彻底拆层`。

原因如下：

1. 它是唯一能严格满足“浏览器绝不再充当搜索引擎”的方案。
2. 它允许在 API 不可用时基于已有候选与用户 URL 继续执行，符合当前业务要求。
3. 它能在不新增独立基础设施的前提下，显著提升稳定性和结果质量。

## 5. 核心设计

### 5.1 运行时边界

重构后的职责边界如下：

1. `SourceDiscovery / SearchSourceProvider`
   只负责产出结构化候选 URL，不做正文采集，不做浏览器渲染。
2. `SearchExecutionCoordinator`
   只负责候选编排、补源、轻量验证和最终目标选择。
3. `CandidateVerifier`
   只负责轻量验证，不允许调用 `SourceCollector`，不允许触发 `Playwright`。
4. `SourceCollector`
   只负责最终目标页正文采集。
5. `PlaywrightPageCollector`
   只在最终目标页 HTTP 采集失败或正文明显不足时，作为渲染采集兜底器存在。

### 5.2 规划期候选生成

`HeuristicSourceDiscoveryService` 继续负责生成 `SourcePlan`，但候选来源收口为三类：

1. 用户明确提供的 URL。
2. API 搜索结果：
   `QianfanSearchSourceProvider`、`SerpApiSearchSourceProvider`、`HttpSearchSourceProvider`。
3. 启发式候选：
   基于用户提供 URL 的根域名推导出的 `docs`、`pricing`、`blog`、`news` 等入口。

`BrowserPreviewSearchSourceProvider` 不再参与默认规划期候选生成。

### 5.3 运行期补源

`SearchExecutionCoordinator` 的运行期补源逻辑调整为：

1. 优先消费规划期已写入的 `sourceCandidates`。
2. 候选不足时，调用 `searchSourceProvider.search(...)` 进行 API 补源。
3. API 不可用时，只允许继续使用“已有候选 + 用户提供 URL + 启发式候选”。
4. 运行期补源路径中不再出现 `BROWSER`。

默认 fallback 顺序改为：

1. `PLANNED`
2. `HTTP/API`
3. `HEURISTIC`

不再保留 `BROWSER` 作为默认补源步骤。

### 5.4 轻量验证

`CandidateVerifier` 改造成轻量验证器，不再打开正文页，不再依赖 `SourceCollector.collect()`。

验证依据收口为：

1. URL 协议是否合法。
2. domain 是否命中 `preferredDomains`，是否落入 `blockedDomains`。
3. title、snippet、URL path 是否命中 `sourceType` 对应的关键词。
4. 可选一次轻量 HTTP 读取少量正文或页面标题。

关键词匹配策略不能继续沿用旧的“简单包含”语义，而应升级为：

1. 大小写不敏感。
2. 中英文双语同义词匹配。
3. 支持正则模式或等价规则模板。
4. 对 `DOCS / PRICING / NEWS / REVIEW / OFFICIAL` 分别维护稳定的关键词组。

例如：

1. `PRICING` 至少应覆盖 `pricing|price|plan|plans|价格|定价|计费|套餐`
2. `DOCS` 至少应覆盖 `docs|documentation|api|guide|help|文档|开发指南|帮助中心`
3. `NEWS` 至少应覆盖 `blog|news|release|changelog|更新|发布日志|公告`
4. `REVIEW` 至少应覆盖 `review|reviews|rating|compare|评测|评价|对比`

轻量验证必须采用“保守丢弃”原则：

1. 只有危险协议、黑名单域名、明显无效 URL、明确错误站点等硬失败场景，才允许进入 `DISCARDED`。
2. 只因信息过少、title/snippet 不足、关键词不全命中，不允许直接判成 `DISCARDED`，而应进入 `UNVERIFIED`，避免误杀高价值 URL。

轻量验证只允许三种结果：

1. `VERIFIED`
   命中明显业务信号。
2. `UNVERIFIED`
   没有足够信号，但候选仍可用作降级备选。
3. `DISCARDED`
   明显错误域名、危险 URL、黑名单域名或无效链接。

`UNVERIFIED` 不能被视为失败，它必须保留为降级候选。

### 5.5 最终目标选择

`CollectionTargetSelector` 的优先级调整为：

1. 已验证成功的候选。
2. 用户明确提供的 URL。
3. 高质量 API 候选。
4. 启发式未验证候选。

针对 `UNVERIFIED` 候选，必须增加一层“防反客为主”硬拦截：

1. 如果当前候选池里只有启发式推导出的 `UNVERIFIED` 链接，且没有用户明确提供的 URL，不允许无限制地补满 `targetCount`。
2. 在该场景下，系统必须先执行一次廉价可达性预检，例如 `HTTP status / redirect / title` 级别检查，禁止直接进入 `Playwright`。
3. 只有通过廉价预检的 `UNVERIFIED` 启发式候选，才允许进入最终采集队列。
4. 即使允许进入，也必须受 `maxUnverifiedTargets` 限制，避免系统拿一批猜测型 `/pricing`、`/docs`、`/blog` 404 页面去撞墙。

本设计固定采用以下默认规则：

1. `maxUnverifiedTargets = 1`
2. 若没有 `VERIFIED` 候选、没有用户 URL、且启发式 `UNVERIFIED` 候选未通过廉价预检，则返回“无安全采集目标”，而不是继续尝试正文采集。

目标选择必须满足以下约束：

1. 不因为补齐数量而重新进入浏览器搜索。
2. API 全部不可用时，仍能保住用户明确提供的 URL。
3. 若只有 `UNVERIFIED` 候选，也只允许在“已通过廉价预检且未超过 `maxUnverifiedTargets`”的前提下，以结构化降级理由继续执行。

### 5.6 最终正文采集

只有在 `CollectorAgent -> SourceCollector.collect(...)` 阶段，系统才允许进入正文采集。

最终采集链路保持双层结构，但职责被严格收窄：

1. 先执行轻量 HTTP 采集。
2. 只有最终已选 URL 且 HTTP 采集失败或正文明显不足时，才允许进入 `PlaywrightPageCollector`。

这意味着：

1. `Playwright` 是最终采集兜底器。
2. `Playwright` 不是搜索器。
3. `Playwright` 不是候选验证器。
4. `Playwright` 不是规划期预览器。

## 6. 降级语义

搜索主链路的降级语义不再围绕“浏览器失败”，而是围绕“候选能力不足”定义。

建议新增或替换为以下结构化降级原因：

1. `SEARCH_API_UNAVAILABLE_KEEP_PLANNED`
2. `SEARCH_API_EMPTY_KEEP_USER_URLS`
3. `VERIFY_SKIPPED_HTTP_ONLY`
4. `NO_VERIFIED_TARGET_USE_FALLBACK_CANDIDATES`
5. `NO_CANDIDATES_AVAILABLE`
6. `NO_SAFE_UNVERIFIED_TARGETS`

对应行为如下：

1. API 不可用且已有候选存在：
   继续执行，记录明确降级原因。
2. API 不可用且没有任何候选：
   结构化失败。
3. 验证无足够信号但候选可用：
   保留为降级目标，而不是直接失败。
4. 只有启发式 `UNVERIFIED` 候选且没有用户 URL，且廉价预检未通过：
   返回 `NO_SAFE_UNVERIFIED_TARGETS`，不再继续推进正文采集。

## 7. 配置默认值调整

本次重构要求同时调整默认配置语义，而不是只改代码分支。

### 7.1 Collector 默认配置

`WorkflowFactory` 的默认注入语义改为：

1. `browserSearchEnabled = false`
2. `verifyCandidates = true`，但语义改为“轻量验证”
3. `verifyResultPage` 改为“最终目标页质量校验”
4. `searchFallbackOrder` 不再包含 `BROWSER`
5. `maxUnverifiedTargets = 1`

### 7.2 搜索 Provider 默认顺序

`source-discovery.search.provider-order` 默认顺序调整为：

1. `qianfan`
2. `serpapi`
3. `http`

移除：

1. `browserPreview`

### 7.3 Playwright 配置语义

`SearchBrowserProperties` 和 `browserSearchEnabled` 字段先保留，服务兼容和调试场景，但不再代表可进入业务主搜索路径。

也就是说：

1. 字段保留。
2. 主语义变化。
3. 默认不启用浏览器搜索。

### 7.4 历史任务配置清洗

由于历史任务配置已经以 JSON 形式落盘在数据库中，不能只修改“新任务默认值”，还必须在运行时对旧配置执行强制清洗。

运行时清洗必须满足以下要求：

1. 即使历史任务 JSON 中写着 `browserSearchEnabled = true`，内存中的有效配置也必须被强制覆写为 `false`。
2. 即使历史任务的 `searchFallbackOrder` 中仍包含 `BROWSER`、`browserPreview` 或其他浏览器相关步骤，运行时也必须在内存中剔除这些步骤。
3. 即使历史任务的 `searchMode` 仍带有旧的浏览器语义，运行时也不得因此重新进入浏览器搜索分支。
4. 即使历史任务配置中不存在 `maxUnverifiedTargets`，运行时也必须补齐为新的安全默认值 `1`，避免旧配置绕过新拦截规则。
5. 该清洗逻辑不能只放在新建任务工厂里，必须同时覆盖“历史任务反序列化 -> CollectorNodeConfig 入内存 -> SearchExecutionCoordinator.execute() 真正执行”这条链路。

也就是说，本次重构必须同时做到：

1. 新任务默认不再生成浏览器搜索配置。
2. 旧任务重跑时，历史浏览器搜索配置在内存中被强制失效。
3. 旧任务缺失的新安全字段在运行时被补齐默认值。

## 8. 现有模块映射

### 8.1 保留并重定义职责

1. `SearchExecutionCoordinator`
   保留，职责调整为“API/结构化候选编排器”。
2. `CandidateVerifier`
   保留，职责调整为“轻量验证器”。
3. `PlaywrightPageCollector`
   保留，职责收窄为“最终页面渲染采集器”。
4. `RoutingSearchSourceProvider`
   保留，职责收口为“结构化搜索 Provider 路由器”。

### 8.2 退出主路径但暂不删除

1. `BrowserSearchRuntimeService`
   退出主搜索路径，保留为显式调试能力或未来实验能力。
2. `BrowserPreviewSearchSourceProvider`
   退出默认候选发现路径，保留代码兼容性。

### 8.3 禁止继续承担旧职责

1. `CandidateVerifier`
   不得再调用 `SourceCollector.collect()`。
2. `SearchExecutionCoordinator`
   不得再在运行期补源中调起浏览器搜索。
3. `RoutingSearchSourceProvider`
   不得再默认隐式走浏览器预览。

## 9. 错误分类

建议把错误分类重构为以下几类：

1. `SEARCH_PROVIDER_UNAVAILABLE`
2. `SEARCH_PROVIDER_EMPTY`
3. `CANDIDATE_VERIFICATION_INCONCLUSIVE`
4. `CANDIDATE_INVALID`
5. `FINAL_PAGE_HTTP_FAILED`
6. `FINAL_PAGE_RENDER_FAILED`

错误边界要求如下：

1. 搜索阶段不再抛出浏览器不可用类主错误。
2. 浏览器相关错误只允许出现在最终页面采集阶段。
3. 搜索失败必须优先返回结构化降级结果，而不是浏览器异常堆栈。

## 10. 测试与验收边界

### 10.1 必测单元测试

1. `SearchExecutionCoordinatorTest`
   - API 不可用时，若 `sourceCandidates` 或 `competitorUrls` 存在，任务继续执行。
   - API 不可用且没有候选时，返回结构化失败。
   - 默认运行期补源不再调用 `BrowserSearchRuntimeService`。
   - 历史任务配置中即使包含 `browserSearchEnabled = true` 和 `BROWSER` fallback，运行时也不会触发浏览器搜索。
   - 历史任务配置缺失 `maxUnverifiedTargets` 时，运行时自动补齐为 `1`。
   - 只有启发式 `UNVERIFIED` 候选且无用户 URL 时，命中 `maxUnverifiedTargets` 和廉价预检拦截规则。
2. `CandidateVerifierTest`
   - 验证不再依赖 `SourceCollector`。
   - 能区分 `VERIFIED / UNVERIFIED / DISCARDED`。
   - 中英文双语关键词、大小写不敏感匹配和正则规则生效。
   - 信息不足时进入 `UNVERIFIED`，而不是被误杀成 `DISCARDED`。
3. `RoutingSearchSourceProviderTest`
   - 默认 provider 顺序不再包含 `browserPreview`。
   - API 不可用时返回空候选，而不隐式转浏览器。
4. `PlaywrightPageCollectorTest`
   - 只验证最终 URL 采集场景下的 HTTP -> Playwright 回退。

### 10.2 必测集成测试

1. 有用户 URL、无 API Key 时，任务可基于用户 URL 降级完成。
2. 有 API Key 时，任务优先使用 API 候选完成搜索。
3. 搜索阶段全程不触发 `Playwright`。
4. 最终目标页在 HTTP 不足时才触发 `Playwright`。
5. 历史数据库中的旧任务配置在重跑时，仍不会重新进入浏览器搜索。

### 10.3 验收标准

本次重构完成后，必须满足以下标准：

1. 普通采集任务的搜索阶段 `0 次` 调用 `Playwright`。
2. API 不可用时，系统只会降级到“已有候选 + 用户提供 URL”，不会回退到浏览器搜索。
3. `Playwright` 只会出现在最终目标页正文采集阶段。
4. 搜索审计能够明确区分“API 不可用”“候选不足”“轻量验证不确定”“最终页面采集失败”。
5. 最终输出继续满足 `sourceUrls` 可追溯要求。

## 11. 非目标

本次设计不做以下事情：

1. 不引入独立浏览器 worker 或独立采集进程。
2. 不在本轮删除全部浏览器相关代码。
3. 不在本轮重构整个报告、RAG 或前端观察面板。
4. 不把候选验证升级成大模型语义判定链路。

## 12. 预期结果

设计落地后，系统应从“浏览器驱动搜索 + 浏览器驱动验证 + 浏览器驱动采集”的混合链路，升级为“API 主搜 + 轻量验证 + 最终目标页采集”的清晰分层结构。

这会直接带来三项收益：

1. 搜索稳定性提升。
2. 搜索结果质量提升。
3. 运行时并发误伤与浏览器级崩溃大幅下降。
