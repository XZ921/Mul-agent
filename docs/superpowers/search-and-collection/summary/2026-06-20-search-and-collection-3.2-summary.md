# 3.2 搜索与采集阶段总结

## 1. 总结结论

3.2 搜索与采集已经从早期“给一个 URL 后尽力抓一页”的单路径能力，演进为家族驱动、分层发现、正式选源、统一采集执行、可审计、可回放、可恢复、可向下游传递质量信号的证据生产链路。

当前阶段可以支撑 3.3 提取结构化任务继续推进。3.3 不需要等待搜索与采集把所有真实站点都采到完美状态；它需要的是稳定输入契约、来源追溯、质量信号、结构块、失败诊断和证据覆盖缺口。上述契约已经具备。

仍然存在的缺点主要集中在真实站点覆盖率、强交互页面、Playwright 资源模型、跨任务缓存、持续订阅和最终业务质量门禁。这些问题应登记为后续专项或 P0/P1 缺陷修复，不应继续阻塞 3.3 的主线启动。

3.2 的最终交付重点不是“保证所有任务最终报告都通过质量门禁”，而是把“为什么证据不足、缺在哪个环节、还能不能追溯来源”变成系统可以解释和恢复的事实链路。

## 2. 文档来源与总结边界

本总结依据仓库内 `docs/superpowers` 及 `docs` 目录下的搜索与采集相关文档整理。仓库根目录未发现独立 `superpowers` 目录，相关资料集中在 `docs/superpowers`。

主要依据文档包括：

- `docs/superpowers/specs/2026-06-17-search-and-collection-architecture-design.md`
- `docs/superpowers/search-and-collection/plan/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/search-and-collection/plan/2026-06-18-search-and-collection-site-discovery-deep-collection-master-plan.md`
- `docs/superpowers/search-and-collection/task/*.md`
- `docs/superpowers/search-and-collection/progress/2026-06-16-search-and-collection-fourth-iteration-progress.md`
- `docs/superpowers/search-and-collection/progress/2026-06-16-search-and-collection-fifth-iteration-progress.md`
- `docs/superpowers/search-and-collection/progress/2026-06-17-search-and-collection-family-discovery-convergence-progress.md`
- `docs/superpowers/search-and-collection/progress/2026-06-18-search-and-collection-site-discovery-deep-collection-progress.md`
- `docs/superpowers/search-and-collection/problem/2026-06-17-search-and-collection-config-readiness-remediation-plan.md`
- `docs/superpowers/search-and-collection/problem/CollectionExecution.md`
- `docs/superpowers/search-and-collection/future/2026-06-18-search-and-collection-discovery-gap-analysis.md`

本文只总结 3.2 搜索与采集阶段，不展开 3.3 提取结构化的实现设计。3.3 的重点应围绕 `ExtractResult vs CompetitorKnowledge`、`TASK / DOMAIN` 语义分层、非空业务字段提取、证据片段消费和结构化失败诊断继续推进。

## 3. 阶段演进总览

| 阶段 | 核心目标 | 主要产出 | 当前状态 |
| --- | --- | --- | --- |
| 第一轮 | 执行真相、质量止血、连续性事实源 | `searchAudit`、`sourceUrls`、基础 replay / rerun / resume 验收 | 已完成 |
| 第二轮 | 恢复现场、完整 replay、preview/runtime 家族同构、排序硬化 | `attemptedTargets`、`discardedCandidates`、`replayTimeline`、source family 字段 | 已完成 |
| 第三轮 | 对象瘦身与共享投影分层 | `SearchSharedProjection`、`SharedNodeOutputEnvelope`、下游统一搜索投影 | 已完成 |
| 第四轮 | 垂直来源与采集执行接缝 | `CollectionTaskPackage`、`CollectionExecutionCoordinator`、`CollectionExecutorRegistry`、`GithubApiCollectionExecutor` | 已完成 |
| 第五轮 | 网页采集加固 | `JinaReader` 主路径、`Playwright FULL_RENDER` 兜底、`structuredBlocks`、`qualitySignals`、`qualityScore` | 已完成 |
| 第六轮 | 采集审计、回放、恢复 | `collectionAudit`、`collectionReplayTimeline`、`collectionStatus`、`recoveryCheckpoint`、包级 checkpoint reuse | 已完成 |
| 第七轮 | News / RSS 采集收敛 | `RssFeedCollectionExecutor`、RSS feed evidence、采集审计复用 | 已完成 |
| Family discovery convergence | 家族发现收敛 | `SourceFamilyDirectDiscoveryPlanner`、direct discovery 与 public search supplement 边界 | 已完成 |
| 第八轮 | 下游证据闭环 | `DownstreamEvidenceView`、`evidenceCoverage`、report diagnosis、TASK 记忆边界 | 已完成主干，后续停点转入 3.3 |
| 站点发现与深采集 | 只给竞品名也能找入口，并对文档站有限递归 | 域名发现、子域模板、sitemap/robots、内部链接递归 | 已完成主干 |
| 效率专项 | 不减少信息量前提下降低耗时 | Direct-first 验证、候选验证并发、预抓复用、采集统计 | 已规划并部分落地 |
| Jina-first / Playwright minimization | 降低浏览器依赖 | `DirectHtmlReader -> JinaReader -> Playwright` 路由收尾 | 已完成并冻结为 3.2 当前基线 |

## 4. 当前架构快照

### 4.1 数据源家族层

系统已经不再把搜索 provider 直接等同于业务来源。`official`、`github`、`news`、`rss` 等 source family 承担业务来源语义，provider 只承担发现或抓取工具职责。

这条边界很重要：GitHub API 是采集 owner，公网搜索可以作为发现辅助；RSS feed 是结构化增量来源，网页采集只是 fallback；官网、文档、开放平台等属于 official family，不能只按 SEO 搜索结果决定最终采集目标。

### 4.2 发现层

发现层已经形成多入口组合：

- direct path templates：基于 source family 的稳定入口模板。
- subdomain templates：扩展 `open.`、`docs.`、`developer.` 等候选入口。
- public search supplement：当 direct discovery 不足时补充公网搜索。
- LLM / 规则域名发现：只给竞品名时生成官网、开放平台、文档站候选。
- sitemap / robots discovery：从站点结构中补充高价值入口。
- search result root feedback：搜索命中根域后回灌 direct family 候选。

所有新候选都必须保留 `sourceUrls`，用于后续解释“这个候选从哪里来”。

### 4.3 候选验证与选源层

`SearchExecutionCoordinator` 已经负责候选验证、去重、排序、补源和最终选源。正式进入采集前，候选需要经过验证与解释，系统会保留：

- `attemptedTargets`
- `discardedCandidates`
- `selectedTargets`
- `searchAudit`
- `replayTimeline`
- `recoveryCheckpoint`

这使系统能解释“为什么选这个 URL，不选那个 URL”，并能在 rerun / resume 时复用已经验证过的现场。

### 4.4 采集执行层

采集层已经从 `CollectorAgent` 直接调用网页 collector，演进为正式执行模型：

- `CollectionTaskPackage` 表达采集任务包。
- `CollectionExecutionCoordinator` 负责执行编排、执行器选择、递归队列和结果汇总。
- `CollectionExecutorRegistry` 负责执行器注册与路由。
- `WebPageCollectionExecutor` 负责网页采集，当前以 Direct / Jina 轻量路径优先，Playwright 作为重型兜底。
- `GithubApiCollectionExecutor` 负责 GitHub 结构化采集。
- `RssFeedCollectionExecutor` 负责 RSS feed 采集。

网页采集已经支持结构块、质量信号、质量评分、失败分类、内部链接发现和有限深度递归。

### 4.5 审计、回放与恢复层

搜索和采集都已经有正式事实源：

- 搜索段：`searchAudit`、`searchExecutionTrace`、`searchReplayTimeline`。
- 采集段：`collectionAudit`、`collectionReplayTimeline`、`collectionExecutionReport`。
- 恢复段：`searchAuditCheckpoint`、`collectionAuditCheckpoint`、包级 checkpoint reuse。

这使问题定位从“结果不好”细化为：

- 没找到入口。
- 找到了但验证失败。
- 选源正确但采集失败。
- 采集成功但结构块不足。
- 证据有来源但业务字段提取为空。
- 下游报告存在 unsupported claim 或 evidence coverage 缺口。

### 4.6 下游证据交付层

第八轮已经把采集结果转换成下游统一证据视图：

- `DownstreamEvidenceView`
- `EvidenceFragment`
- `SectionEvidenceBundle`
- `qualitySignals`
- `structuredBlocks`
- `evidenceCoverage`
- `sourceUrls`

`SchemaExtractorAgent`、`CompetitorAnalysisAgent`、`ReportWriterAgent`、`QualityReviewAgent`、`ReportService`、`EvidenceQueryService`、`ExportPackageService` 已经可以沿同一组证据语义继续工作。extractor 默认 `DOMAIN` 污染路径已被切换为显式 `TASK` 快照边界。

## 5. 已完成能力清单

### 5.1 可追溯性

所有核心候选、采集结果和下游证据都必须围绕 `sourceUrls` 建立追溯链路。新增对象和投影缺少来源时，应返回空列表并显式打缺口标记，而不是伪造来源。

### 5.2 可恢复性

系统已经支持搜索与采集两个阶段的 checkpoint reuse。真实任务中已经验证过 collection audit checkpoint 回填和包级复用，rerun / resume 不再只能从头重新搜索和采集。

### 5.3 采集多样性

当前采集不再只有网页：

- 网页：Direct / Jina / Playwright 分层。
- GitHub：API 型结构化采集。
- RSS：feed 型结构化采集。
- 文档站：内部链接有限深度递归。

### 5.4 网页采集质量

网页采集已经从“拿到一段长文本”升级为“正文、结构块、质量信号、失败分类、质量评分”共同交付。Playwright 不再是默认主力，而是针对强交互页面和 JS 渲染页面的兜底能力。

### 5.5 下游交付

采集层已能把 `collectionAudit / qualitySignals / structuredBlocks / sourceUrls` 正式传给 `extract_schema -> analyze_competitors -> write_report -> quality_check -> report / export`。报告诊断已经能把交付阻塞细分到 `sourceUrls`、`structuredBlocks`、`qualitySignals`、`evidenceCoverage` 四类缺口。

## 6. 验证与验收证据

自动化侧，各轮文档记录了定向测试集和 backend 全量测试。关键验证包括：

- 第一轮目标测试集通过，backend 全量通过，dev live 任务 `33` 完成搜索与采集段真实补源、正式采集、回放、rerun / resume 验收。
- 第二轮定向测试通过，backend 全量通过，dev live 任务 `39` 验证 preview / create / execute / replay / rerun / resume 六个端点。
- 第四轮、第五轮、第六轮、第七轮分别完成采集执行接缝、网页采集加固、采集审计回放恢复、RSS 采集收敛的定向验证与文档回链。
- 第八轮 Slice A / Slice B 聚合测试在 2026-06-20 fresh 通过，backend 全量测试也在同日通过。
- 第八轮 dev live 任务 `50` 验证 `/api/report/50`、`/api/report/50/evidences`、`/api/task/50/replay` 已能暴露统一证据视图、解释型诊断和来源追溯。

需要特别说明：任务 `50` 最终暴露出的后续问题已经从“搜索采集链路崩溃”收敛为 3.3 相关问题，即 extractor 曾把 `0` 个业务字段的模型输出误判为成功。后续已增加 extractor 零业务字段保护，使任务停在正确节点，避免空结构化知识继续污染 analyzer / writer / reviewer。

因此，任务总状态或最终质量门禁失败不能反向推翻 3.2 的阶段结论。它说明 3.3 需要继续处理结构化提取、prompt、retry 和 acceptance gate，而不是说明搜索与采集还不能交付输入。

## 7. 当前仍存在的缺点

3.2 已经足以支撑 3.3，但并不代表搜索与采集完美完成。当前仍存在以下缺点：

- 真实站点覆盖率仍受反爬、登录、强交互页面、浏览器兼容页、站点动态加载影响。
- Playwright 已退为兜底路径，但对于强交互页面仍不可完全删除。
- Playwright 仍缺少正式并发上下文池；当前更适合作为受控兜底能力，而不是高并发主力采集通道。
- 跨任务缓存尚未设计，当前优化仍以单任务内复用、候选验证并发、预抓页面复用和 audit 保真为主。
- RSS 持续订阅、cursor、dedupe、长期增量监控仍可后续增强。
- 跨重启 replay 持久化底座仍不是当前 3.2 已完成项。
- 部分 provider 依赖真实凭证和 readiness 配置，缺配置时必须清晰降级或 fast-fail，不能静默假装可用。
- 最终报告质量门禁仍可能失败，原因通常是证据充分性、结构化提取、coverage 或 unsupported claim，而不是单纯采集执行异常。

这些缺点应进入后续专项或缺陷队列，不应继续扩大 3.2 主线范围。

## 8. 为什么不阻塞 3.3 提取结构化

3.3 需要的不是“完美采集世界”，而是稳定、可诊断、可追溯的输入。当前 3.2 已经提供：

- `sourceUrls`
- `selectedTargets`
- `searchAudit`
- `collectionAudit`
- `structuredBlocks`
- `qualitySignals`
- `qualityScore`
- `failureKind`
- `DownstreamEvidenceView`
- `EvidenceFragment`
- `SectionEvidenceBundle`
- `evidenceCoverage`

3.3 可以基于这些输入继续完成：

- 从可读正文和结构块中提取非空业务字段。
- 对 `structuredBlocks` 不足但正文可读的页面做 fallback 提取。
- 对模型返回空业务字段的场景进行 retry 或 WAITING_INTERVENTION。
- 保持 `TASK / DOMAIN` 记忆边界。
- 输出可反向定位来源页面、证据片段和缺口类型的结构化结果。

3.3 不能假设：

- 每个竞品都有完美证据。
- 每个 URL 都采集成功。
- 每个页面都有结构化块。
- 质量门禁失败一定是搜索采集失败。
- `sourceUrls` 回填等于业务字段提取成功。

跨任务缓存和 Playwright 并发上下文池属于效率和稳定性增强，不改变 3.3 的输入契约，因此不阻塞 3.3。

## 9. 进入 3.3 前的契约提醒

3.3 提取结构化任务应守住以下边界：

- 不绕过 `sourceUrls`，所有结构化字段必须能回指来源。
- 不吞掉 `qualitySignals`，质量信号应参与提取策略和失败诊断。
- 不把 collection failure 当成空证据静默处理。
- 不把 `sourceUrls` 回填当成抽取成功。
- 不把 `TASK` 快照默认写成 `DOMAIN` 长期记忆。
- 不让 `0` 个业务字段的模型输出继续流向 analyzer / writer / reviewer。
- 对证据不足、结构块不足、coverage 不足、unsupported claim 输出可诊断缺口，而不是编造结论。
- 对正文可读但结构块为空的场景，应设计 fallback 提取和最小字段提取策略。

## 10. 后续专项登记

### 10.1 专项 A：跨任务缓存与隐私 / TTL / 失效设计

定位：该专项不属于 3.2 已完成范围，也不阻塞 3.3。它是后续降低重复搜索、重复验证、重复采集成本的独立能力。

为什么要独立成专项：

- 不能只做简单 URL 缓存，否则会破坏 `sourceUrls / searchAudit / collectionAudit / replay` 的可解释性。
- 需要定义缓存粒度：搜索候选、验证快照、网页正文、结构块、API 响应、RSS 响应是否分开缓存。
- 需要定义隐私边界：不同任务、不同用户、不同竞品、不同来源类型是否允许复用。
- 需要定义 TTL 与失效策略：官网、新闻、RSS、GitHub、定价页、文档页更新频率不同。
- 需要定义手动刷新、强制绕过缓存、缓存命中审计字段。

后续设计必须回答：

- 缓存 key 如何设计。
- 缓存命中是否进入 `searchAudit / collectionAudit`。
- 过期、失效、强制刷新如何记录。
- 缓存内容保留原始 HTML、正文、结构块，还是只保留摘要。
- 如何避免旧证据污染新报告。
- 如何让缓存复用仍然满足 `sourceUrls` 可追溯红线。

### 10.2 专项 B：Playwright 并发上下文池设计

定位：该专项不属于 3.2 主链路收口，也不阻塞 3.3。它是后续提升真实站点采集吞吐与稳定性的基础设施专项。

为什么要独立成专项：

- Playwright 已经退为兜底路径，但强交互、反爬、JS 文档站仍需要它。
- 不能简单把 Playwright 调用并发化，否则会带来浏览器进程泄漏、上下文污染、资源耗尽、反爬特征放大。
- 需要区分 browser、context、page 三层生命周期。
- 需要定义池大小、租约超时、健康检查、失败隔离、回收策略。
- 需要把池状态纳入采集审计和运行期诊断。

后续设计必须回答：

- 是池化 browser、context，还是 page。
- 每个采集任务是否需要隔离 cookie、localStorage、session。
- context 崩溃、页面卡死、`__adopt__` 类异常如何回收。
- 并发上限如何与 `CollectionExecutionCoordinator` 队列并发配合。
- 池化后如何保证 `collectionAudit` 仍能解释每次采集走了哪个执行路径。
- 如何避免 Playwright 池化把反爬风险从单页失败放大为批量失败。

## 11. 后续建议

### 11.1 不阻塞 3.3 的优化

- 跨任务缓存与隐私 / TTL / 失效专项。
- Playwright 并发上下文池专项。
- RSS 订阅、cursor、dedupe 和长期增量监控。
- 跨重启 replay 持久化底座。
- 更多 API 型 source family 的正式 owner 接入。
- 真实 smoke 指标基线，用于比较并发、复用和 Direct/Jina 优先策略的收益。

### 11.2 建议在 3.3 中同步守住的能力

- 以 `DownstreamEvidenceView` 作为 extractor 的正式输入边界。
- 建立非空业务字段 acceptance gate。
- 对结构块不足但正文可读的证据做 fallback 提取。
- 把 `sourceUrls / qualitySignals / structuredBlocks / evidenceCoverage` 原样传递到结构化结果与诊断。
- 继续保持 `TASK / DOMAIN` 语义分层。

## 12. 附录

### 12.1 关键类与职责索引

| 类 | 职责 |
| --- | --- |
| `SearchExecutionCoordinator` | 搜索执行编排、候选验证、补源、选源、搜索审计 |
| `SourceFamilyDirectDiscoveryPlanner` | source family direct discovery 规划 |
| `CompetitorDomainDiscoveryService` | 竞品域名发现 |
| `SitemapDiscoveryService` | sitemap / robots 入口发现 |
| `CandidateVerifier` | 候选验证、Direct-first 正向捷径、验证统计 |
| `CollectionExecutionCoordinator` | 采集执行编排、包级递归、采集审计和恢复 |
| `CollectionExecutorRegistry` | 采集执行器路由 |
| `WebPageCollectionExecutor` | 网页采集，Direct / Jina / Playwright 分层 |
| `GithubApiCollectionExecutor` | GitHub API 结构化采集 |
| `RssFeedCollectionExecutor` | RSS feed 采集 |
| `InternalLinkDiscoveryService` | 页面内部链接发现 |
| `DownstreamEvidenceViewAssembler` | 下游统一证据视图装配 |
| `SchemaExtractorAgent` | 结构化提取入口，3.3 后续主线 |

### 12.2 术语表

| 术语 | 含义 |
| --- | --- |
| `sourceUrls` | 来源追溯红线，所有证据和投影必须保留或显式标记缺失 |
| source family | 业务来源家族，如 official、github、news、rss |
| provider | 搜索或采集工具，不等同于业务来源 |
| `selectedTargets` | 经过验证和排序后正式进入采集的目标 |
| `structuredBlocks` | 页面中可直接用于下游提取的结构化证据块 |
| `qualitySignals` | 采集和证据质量信号 |
| `collectionAudit` | 采集阶段正式事实源 |
| `DownstreamEvidenceView` | 下游统一证据视图 |
| `evidenceCoverage` | 字段、章节或结论维度的证据覆盖情况 |
| `TASK` 记忆 | 当前任务现场快照 |
| `DOMAIN` 记忆 | 经过治理后可长期沉淀的领域知识 |
