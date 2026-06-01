# 基于当前工程现状的浏览器搜索升级方案（面向企业级演进）

## 1. 文档定位

这份方案不是从零重写搜索体系，而是基于你当前工程的真实状态做升级设计。

需要同时满足 3 个前提：

1. `task.md` 里的最终目标不变：多 Agent 协作、DAG 编排、结果可溯源、过程可观测。
2. `planV2.md` 里的 V2 路线不回退：截至 2026-05-30，你说明 **V2 第 2 阶段已经完成**，也就是“启发式 + 搜索式补源、去重、排序、元数据展示”已经是当前基线能力。
3. `project_rules.md` 里的工程约束必须落地：单一职责、中文注释、外部依赖容错重试、无幻觉、结构化计划与进度持久化。

所以，这次升级的目标不是“把现有搜索删掉重做”，而是：

**在保留 V2 第二阶段已有补源规划能力的前提下，引入浏览器驱动的搜索执行、结果验证、过程留痕和恢复能力，让搜索链路更接近企业级研究系统。**

## 2. 先纠正上一版方案里的一个点

上一版方案里，我把重点放在“把搜索下沉到 `CollectorAgent` 运行期”。

这个方向本身没错，但如果完全把搜索从规划期挪走，会和你现在工程的 3 个现状冲突：

1. `planV2.md` 要求任务启动前可预览补源计划、节点依赖和节点配置摘要。
2. 你当前已经完成了 V2 第 2 阶段，`WorkflowFactory -> SourceDiscoveryService -> sourceCandidates` 这条规划链已经落地。
3. `project_rules.md` 要求核心任务执行前必须先生成结构化执行计划，并且进度可追踪、可恢复。

所以，更适合你当前工程的，不是“只保留运行期搜索”，而是升级成：

## 3. 推荐总方案

### 双阶段搜索架构：规划期补源 + 运行期浏览器验证/增补

这是最贴合你当前代码和最终企业级目标的方案。

### 第一阶段：规划期补源

继续保留你现在的：

- `WorkflowFactory`
- `SourceDiscoveryService`
- `HeuristicSourceDiscoveryService`
- `SearchSourceProvider`
- `SourceCandidateRanker`

职责不变：

- 根据竞品名、用户提供 URL、采集范围，提前生成 `sourceCandidates`
- 提前生成 `discoveryNotes`
- 提前把候选来源、排序分数、来源说明写进节点配置
- 保证任务启动前就能预览“系统准备从哪里采”

这一步是你当前 V2 第二阶段成果，不能回退。

### 第二阶段：运行期浏览器验证/增补

在 `CollectorAgent` 执行阶段增加浏览器能力，但不是替代规划期，而是增强它：

1. 对规划期候选来源做浏览器验证
2. 对低质量或失效来源做运行期增补搜索
3. 对搜索结果做二次筛选和页面级验证
4. 把浏览器执行轨迹、验证结论、最终采用来源持久化

这样做的结果是：

- 启动前有计划
- 执行时有验证
- 失败时可恢复
- 事后可回放

这才更接近企业级工程。

## 4. 这次升级的企业级目标

结合 `task.md`、`planV2.md`、`project_rules.md`，这次搜索升级应该明确承担以下目标：

1. **从“搜索能用”升级到“搜索可解释”**  
   不只是拿到 URL，而是要知道为什么选它、为什么丢弃它。

2. **从“补源结果可看”升级到“补源执行可回放”**  
   不只是节点配置里有 `sourceCandidates`，还要记录运行时 query、验证动作、筛选结果。

3. **从“静态候选来源”升级到“动态验证 + 增补”**  
   规划期猜到的来源不一定可用，执行期必须能校验和补救。

4. **从“单点搜索实现”升级到“可替换搜索策略”**  
   企业级工程不能把搜索写死在一个类里，要能切换 `HEURISTIC / HTTP_API / BROWSER / HYBRID`。

5. **从“抓到页面就算成功”升级到“来源质量受控”**  
   要增加域名偏好、结果页验证、内容质量阈值、失败重试、降级策略。

6. **从“日志可查”升级到“计划、进度、动作、结果四层可观测”**  
   这点是和 `project_rules.md` 最相关的。

## 5. 当前工程现状与可复用资产

你现在已经具备的能力，不应该推倒重来：

- `WorkflowFactory` 已支持按竞品和 `sourcePlan` 动态展开采集节点
- `SourceDiscoveryService` 已形成统一候选来源模型
- `SearchSourceProvider` 已抽象出搜索式补源适配层
- `SourceCandidateRanker` 已具备去重与排序能力
- `CollectorAgent` 已具备证据持久化链路
- `PlaywrightPageCollector` 已具备真实浏览器采集能力
- `AgentExecutionLog` 已具备 Agent 级可观测性基础

所以，这次升级最合理的方式是：

**补齐“浏览器搜索执行层”和“搜索运行时可观测层”，而不是重做发现层。**

### 5.1 需要先承认的几个工程边界

结合当前代码，有 4 个前置问题必须在方案层先定下来，否则后续实现会越来越拧巴：

1. `SourceDiscoveryService` 当前把 `SourcePlan` 和 `SourceCandidate` 定义成接口内部类，这对 V2 第二阶段够用，但对后续浏览器搜索扩展已经开始形成结构性阻力。
2. `PlaywrightConfig` 需要作为系统级浏览器能力常驻装配，而不是依赖采集器切换条件。
3. `CollectorAgent` 当前已经承担了配置解析、采集、候选匹配、元数据合并、持久化和结果序列化，已经接近单类职责边界。
4. 前端 `EvidenceInfo` 仍主要依赖 `pageMetadata` 吞并结构化字段，不能很好支撑企业级的筛选、分组和质量展示。

因此，本次升级不只是加几个搜索类，而是要先做一轮“结构解耦”。

## 6. 升级后的总体架构

### 6.1 总体分层

建议把搜索相关能力拆成 4 层：

1. **规划层**
   - 负责任务启动前的候选来源发现与节点配置生成
2. **执行层**
   - 负责采集节点运行时的浏览器搜索、验证、补源和最终选源
3. **采集层**
   - 负责打开选中的页面并抓正文
4. **观测层**
   - 负责记录计划、进度、动作、结果

### 6.2 推荐架构图

```text
Task Create
  -> WorkflowFactory.buildPlan
  -> SourceDiscoveryService.discover
  -> SearchSourceProvider(Heuristic / HTTP / BrowserPreview / Hybrid)
  -> sourceCandidates 写入节点配置

Task Execute
  -> CollectorAgent
  -> SearchExecutionCoordinator
  -> BrowserSearchRuntimeService
  -> CandidateVerifier
  -> SourceCandidateRanker
  -> SourceCollector / PlaywrightPageCollector
  -> EvidenceSource + AgentExecutionLog + SearchTrace
```

## 7. 核心设计原则

### 7.1 Agent 职责不扩散，但内部组件要拆分

`task.md` 里采集 Agent 的职责本来就包含“自动搜索并抓取目标竞品公开信息源”，所以让采集 Agent 负责编排“搜 + 验 + 抓”是合理的。

但为了符合 `project_rules.md` 的单一职责原则，不应该把所有逻辑都塞进 `CollectorAgent`。

建议做法是：

- `CollectorAgent`
  - 只做节点级编排和结果汇总
- `SearchExecutionCoordinator`
  - 负责运行期搜索流程编排
- `BrowserSearchRuntimeService`
  - 负责真实浏览器检索
- `CandidateVerifier`
  - 负责结果页验证
- `CollectionTargetSelector`
  - 负责最终选源

这样既不增加新的 Agent 类型，又不会把 `CollectorAgent` 写成巨石类。

### 7.2 规划期和运行期都必须可追溯

企业级系统不能只有“最后采了哪些 URL”，还必须知道：

- 规划期候选是怎么来的
- 运行期做了哪些 query
- 哪些结果被过滤
- 哪些结果被验证通过
- 最终为什么选择这些来源

### 7.3 搜索必须有降级链路

所有外部依赖都必须有容错，这和 `project_rules.md` 一致。

推荐降级顺序：

1. 先使用规划期已有高分候选
2. 不足时执行浏览器验证
3. 验证后仍不足时执行浏览器增补搜索
4. 浏览器搜索失败时回退到启发式入口
5. 必要时允许回退到已有 HTTP 搜索适配层

### 7.4 计划与进度必须结构化输出

这一点上一版方案写得还不够。

按照 `project_rules.md`，采集节点在执行前必须产出结构化执行计划，至少包括：

- 任务拆解步骤
- 各步骤核心目标
- 预期耗时
- 依赖前置条件

执行中还要持续输出结构化进度，例如：

- 当前执行步骤
- 已完成比例
- 剩余步骤
- 步骤状态

这意味着搜索升级不只是功能升级，也是可观测性升级。

## 8. 推荐的目标实现

### 8.0 前置重构：先把模型和边界拆开

这一步是后续所有搜索升级的前置条件，优先级高于浏览器搜索本身。

#### 8.0.1 提取 `SourceCandidate` 和 `SourcePlan`

当前 `SourceCandidate` 和 `SourcePlan` 是 `SourceDiscoveryService` 的内部类，这会带来两个问题：

- 扩展字段时接口迅速膨胀
- 其他运行期组件复用时语义别扭

建议先做：

- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java`

然后把：

- `SourceDiscoveryService.discover(...)`
- `CollectorAgent`
- `SearchSourceProvider`
- `SourceCandidateRanker`

全部切到独立类。

这是浏览器搜索升级的第一优先级前置重构项。

#### 8.0.2 提取 `CollectorNodeConfig`

当前 `CollectorNodeConfig` 是 `CollectorAgent` 内部类。随着搜索策略字段增加，它不应该继续停留在私有内部类里。

建议提取为：

- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`

并增加字段：

- `searchMode`
- `searchQueries`
- `searchFallbackOrder`
- `verifyCandidates`
- `preferredDomains`
- `blockedDomains`
- `browserSearchEnabled`
- `maxSearchResults`
- `searchTimeoutMillis`
- `searchExecutionPlan`

这样节点配置才会真正成为“可演进的契约”，而不是 `CollectorAgent` 的内部解析细节。

### 8.1 规划期保留 `SourceDiscoveryService`，但增加浏览器预览能力

当前 `SearchSourceProvider` 只有：

- 历史上的 `MockSearchSourceProvider` 仅用于早期占位，现在应以真实 HTTP 搜索/浏览器预览为主
- `HttpSearchSourceProvider`

建议新增：

- `BrowserPreviewSearchSourceProvider`

职责：

- 在建图阶段用浏览器做轻量搜索
- 只拿搜索结果页，不做深度正文抓取
- 生成可预览的候选来源与补源说明

这样你就能兼顾：

- 任务启动前能预览补源计划
- 搜索方式不再依赖纯 HTTP API

### 8.2 运行期增加 `SearchExecutionCoordinator`

建议新增：

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`

职责：

1. 读取节点配置中的 `sourceCandidates`
2. 生成本节点搜索执行计划
3. 优先验证已有候选
4. 不足时调用浏览器增补搜索
5. 汇总最终来源列表
6. 输出结构化执行进度和执行摘要
7. 控制搜索阶段总超时与熔断降级

这里需要明确加入“时间熔断”策略，而不只是失败重试。

建议规则：

- 搜索阶段总超时默认不超过采集节点预期总耗时的 60%
- 一旦超时，立即停止浏览器增补搜索
- 直接回退到“已有候选 + 启发式兜底”
- 超时事件写入 `AgentExecutionLog.reasoningSummary`

企业级场景里，搜索阶段不能拖垮整个采集节点。

### 8.3 运行期增加 `BrowserSearchRuntimeService`

建议新增：

- `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java`

职责：

1. 打开搜索引擎
2. 执行 query
3. 解析结果页
4. 记录排名、标题、摘要、结果 URL
5. 返回原始搜索命中结果

注意：

- 这是“搜索执行器”
- 不是“最终选源器”

同时要把搜索引擎反爬对抗策略写进设计，而不是实现时临时补：

- 内置 User-Agent 池，至少准备 5 个常见桌面浏览器 UA
- 每次搜索请求间隔默认不低于 3 秒
- 单次任务浏览器搜索次数设置上限，默认 10 次
- 结果页解析采用多选择器回退
- 出现验证码、空结果页、结构异常页时触发快速降级

建议选择器策略至少覆盖：

- Bing 主选择器：`#b_results .b_algo h2 a`
- Bing 备选选择器：`ol#b_results li h2 a`
- 通用备选选择器：`main a h2`, `article a`, `a[href]`

这里不要假设搜索引擎 DOM 长期稳定。

### 8.4 增加 `CandidateVerifier`

建议新增：

- `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`

职责：

1. 点开候选页面
2. 根据 `sourceType` 做页面类型识别
3. 提取首页标题、关键正文、命中信号
4. 判断是否通过验证
5. 输出验证原因

例如：

- `DOCS` 需要命中 `docs/help/guide/api/reference`
- `PRICING` 需要命中 `pricing/plan/billing/subscription`
- `NEWS` 需要命中 `blog/news/changelog/update`
- `REVIEW` 需要命中 `review/rating/customer/compare`

### 8.5 增加 `CollectionTargetSelector`

建议新增：

- `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`

职责：

1. 合并规划期候选与运行期增补候选
2. 复用 `SourceCandidateRanker`
3. 根据验证结果重新排序
4. 裁剪到当前节点允许的最大采集数

同时建议把“筛选阶段”显式建模，例如：

- `PLANNED`
- `VERIFIED`
- `SUPPLEMENTED`
- `SELECTED`
- `DISCARDED`

这样后续日志、任务详情页、证据页才能把来源状态解释清楚。

## 9. 为什么这比“完全改成运行期搜索”更接近企业级

因为企业级系统更关注：

1. **计划可审查**  
   任务执行前就能看到系统要做什么。

2. **执行可控**  
   执行中可以看到当前在哪一步、为什么失败。

3. **失败可恢复**  
   搜索中断后能知道中断前做到了哪。

4. **策略可替换**  
   后续可以自由切换 HTTP 搜索、浏览器搜索、混合搜索。

5. **产物可复盘**  
   一次搜索为什么得到这些来源，可以复盘。

而这些能力，单纯“把搜索全部塞进 `CollectorAgent` 里临时跑一下”是做不到的。

## 10. 需要改造的现有类

### 10.1 `WorkflowFactory`

当前不建议移除 `sourceDiscoveryService.discover(...)`，而是建议增强节点配置。

建议新增配置字段：

- `searchMode`
- `searchFallbackOrder`
- `searchQueries`
- `preferredDomains`
- `blockedDomains`
- `verifyCandidates`
- `minVerifiedCandidates`
- `maxCollectedPages`
- `searchExecutionPlan`

这样规划期节点配置就不只是“候选 URL 列表”，而是“可执行搜索策略”。

### 10.2 `CollectorAgent`

当前 `CollectorAgent` 的职责应该升级为：

1. 解析节点配置
2. 输出结构化执行计划
3. 驱动 `SearchExecutionCoordinator`
4. 获取最终采集目标
5. 调用现有 `SourceCollector.collect(...)`
6. 持久化证据、搜索轨迹、执行结果
7. 输出结构化进度与完成摘要

但是 `CollectorAgent` 本身不要承担：

- query 生成细节
- 浏览器搜索细节
- 页面验证细节
- 选源排序细节

这些要拆到独立组件里。

这里建议比上一版再收紧一点：

- `CollectorAgent` 只保留“节点级编排 + 证据持久化汇总”
- `CollectorNodeConfig` 迁出为独立类
- `SearchExecutionCoordinator` 负责“验证候选 + 增补搜索 + 选源”

否则继续往 `CollectorAgent` 塞逻辑，会直接违反 `project_rules.md` 的单一职责原则。

### 10.3 `PlaywrightConfig`

当前 `PlaywrightConfig` 需要独立于采集策略存在，否则浏览器搜索与真实采集都会受装配条件影响。

建议改成：

- 浏览器 Bean 由独立配置控制
- 采集和搜索共享同一个浏览器基础设施
- 采集与搜索分别有自己的功能开关，但底层浏览器生命周期统一

推荐改为：

- Browser Bean 应作为真实采集与浏览器搜索的基础设施统一创建
- `search.browser.enabled` 控制运行期浏览器搜索是否启用

推荐配置：

```yaml
browser:
  enabled: true
  provider: playwright
  browser: chromium
  headless: true
  timeout-millis: 30000

search:
  mode: hybrid
  browser:
    enabled: true
    engine: bing
    max-results-per-query: 5
    max-open-result-pages: 3
    verify-result-page: true
    max-retries: 2
    min-interval-millis: 3000
    max-searches-per-task: 10
```

也就是说，浏览器现在要从“采集器附属资源”升级为“平台级共享资源”。

### 10.4 `HeuristicSourceDiscoveryService`

不要删除，而是调整职责边界：

- 保留 scope 归一化
- 保留根域名推导
- 保留启发式入口生成
- 保留来源说明构造

但要避免它继续承担全部真实搜索职责。

它应该变成：

- “规划期候选来源组织器”

而不是：

- “唯一真实搜索实现”

## 11. 需要新增的数据结构

### 11.1 扩展 `SourceCandidate`

建议在 `SourceDiscoveryService.SourceCandidate` 中新增：

- `searchQuery`
- `searchEngine`
- `resultRank`
- `verified`
- `verificationReason`
- `matchedSignals`
- `selectionStage`
- `selectionReason`

这样一条来源从“被发现”到“被选中”就完整了。

但这里要强调：这些字段不应该继续加在接口内部类上，而应该加到已经独立提取后的 `source/SourceCandidate.java` 上。

### 11.2 新增搜索执行轨迹模型

企业级一点的做法，建议新增一类运行时轨迹对象：

- `SearchExecutionTrace`
- `SearchActionRecord`
- `SearchExecutionPlan`
- `SearchExecutionStep`

第一版不一定要立刻单独建表，但模型上要先设计出来。

至少要能记录：

- 本次节点搜索计划
- 实际执行 query
- 每个 query 的结果数
- 被验证的候选
- 被过滤的候选
- 最终采用的候选
- 失败原因与重试记录

### 11.3 强类型执行计划模型

上一版方案中的执行计划 JSON 示例保留，但实现上不建议直接手写自由 JSON。

建议新增：

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionPlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionStep.java`

推荐结构：

```java
class SearchExecutionPlan {
    private String stage;
    private List<SearchExecutionStep> steps;
}

class SearchExecutionStep {
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

这样做的好处是：

- 字段名可编译期校验
- 前后端契约更稳定
- 日后更容易扩展步骤级恢复
- 比字符串拼 JSON 更适合企业级维护

### 11.4 扩展 `EvidenceSource.pageMetadata`

建议补充：

- `searchEngine`
- `searchQuery`
- `resultRank`
- `verified`
- `verificationReason`
- `selectionReason`
- `browserTraceId`

这样后续报告页、证据页、质检页都能复用。

### 11.5 前后端 Evidence DTO 对齐

当前后端 `EvidenceSource` 已经有这些结构化列：

- `sourceType`
- `discoveryMethod`
- `sourceDomain`
- `discoveryReason`
- `publishedAt`
- `sourceScore`

但当前：

- 后端 `ReportResponse.EvidenceInfo` 仍主要输出 `pageMetadata`
- 前端 `frontend/src/types/index.ts` 中的 `EvidenceInfo` 也只有 `pageMetadata`

这会导致前端无法稳定做：

- 按 `sourceType` 分组
- 按 `discoveryMethod` 筛选
- 按 `sourceScore` 排序
- 单独展示来源说明和发布时间

所以方案中应补充一条明确实施项：

1. 扩展后端 `ReportResponse.EvidenceInfo`
2. 更新 `EvidenceQueryService.toEvidenceInfo(...)` 映射
3. 扩展前端 `EvidenceInfo` 类型
4. 保留 `pageMetadata` 作为兼容扩展字段，而不是唯一来源

## 12. 结构化计划与进度设计

这是本次方案最需要向 `project_rules.md` 对齐的部分。

### 12.1 采集节点执行计划

建议 `CollectorAgent` 在执行开始时产出结构化计划，且内部实现使用 `SearchExecutionPlan` / `SearchExecutionStep` 强类型模型，再序列化输出。示例：

```json
{
  "stage": "COLLECTOR_SEARCH_AND_COLLECT",
  "steps": [
    {
      "stepCode": "LOAD_CANDIDATES",
      "goal": "读取规划期候选来源",
      "expectedDurationMs": 500,
      "dependency": "nodeConfig"
    },
    {
      "stepCode": "VERIFY_TOP_CANDIDATES",
      "goal": "验证高优先级候选来源是否可用",
      "expectedDurationMs": 5000,
      "dependency": "browser"
    },
    {
      "stepCode": "BROWSER_SUPPLEMENT_SEARCH",
      "goal": "候选不足时执行浏览器增补搜索",
      "expectedDurationMs": 8000,
      "dependency": "searchEngine"
    },
    {
      "stepCode": "SELECT_TARGETS",
      "goal": "合并候选并选出最终采集目标",
      "expectedDurationMs": 1000,
      "dependency": "ranker"
    },
    {
      "stepCode": "COLLECT_PAGES",
      "goal": "抓取页面正文并持久化证据",
      "expectedDurationMs": 12000,
      "dependency": "collector"
    }
  ]
}
```

### 12.2 进度持久化

建议每个关键步骤完成时都输出进度快照，至少包含：

- `currentStep`
- `completedSteps`
- `progressPercent`
- `status`
- `message`

第一版可以先写入：

- `AgentExecutionLog.outputData`
- 或节点运行结果 JSON

后续再独立成更细的进度表。

另外建议把“熔断/降级/超时”作为明确进度事件类型记录下来，避免后续排查时只能看到“结果不足”，看不到“为什么没有继续搜索”。

## 13. 搜索策略建议

### 13.1 推荐搜索模式枚举

建议定义：

- `HEURISTIC_ONLY`
- `HTTP_ONLY`
- `BROWSER_ONLY`
- `HYBRID`

默认建议使用：

- `HYBRID`

含义：

1. 规划期优先启发式 + 搜索适配层生成候选
2. 运行期优先验证已有候选
3. 不足时再启用浏览器增补

这个模式最符合你现在工程的过渡阶段。

### 13.2 Query 生成策略

第一版仍然建议使用规则模板，而不是完全交给 LLM。

原因：

- 更稳定
- 更容易复现问题
- 更适合做回归测试
- 更符合企业工程的可控要求

这里我接受你的建议，不再新增 `SearchQueryTemplateService`。

更合理的做法是复用现有：

- `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`

方式上可以做两种之一：

1. 在 `PromptTemplateService` 中增加搜索查询构建方法
2. 或扩展其模板加载能力，新增搜索模板资源文件

例如新增：

- `backend/src/main/resources/prompts/search-queries.yml`

好处是：

- 避免再造一个模板组件
- 搜索 query 与现有模板机制统一
- 更方便后续版本管理和配置化调整

## 14. 可观测性升级建议

结合 `task.md` 的“系统高度可观测”和 `planV2.md` 的“完整可回放”，这次升级建议把可观测性拆成 4 层：

1. **计划层**
   - 节点准备怎么搜
2. **动作层**
   - 实际执行了哪些 query、开了哪些页面
3. **判断层**
   - 为什么通过、为什么丢弃
4. **结果层**
   - 最终采了哪些来源、写入了哪些证据

当前第一版最少要落地：

- `AgentExecutionLog.promptUsed` 不适用搜索时，可为空
- `AgentExecutionLog.inputData` 记录节点配置摘要
- `AgentExecutionLog.reasoningSummary` 记录筛选和验证摘要
- `AgentExecutionLog.outputData` 记录搜索轨迹和最终来源

再补一个关键点：

- `AgentExecutionLog.reasoningSummary` 还应记录是否触发了超时熔断、是否遇到验证码、是否触发降级链路

否则运行期搜索出了问题，日志里还是会缺少解释性。

## 15. 与后续 V2 第 3/4/5 阶段的衔接

这次搜索升级不能只盯着“搜到了什么”，还要考虑对后续阶段的帮助。

### 15.1 对 V2 第 3 阶段的帮助

第 3 阶段强调章节级证据强校验。

浏览器搜索升级后，证据元数据会更完整：

- 来源类型更明确
- 页面验证结果更明确
- 搜索理由更明确

这会直接增强 Reviewer 判断“证据不足”的能力。

### 15.2 对 V2 第 4 阶段的帮助

第 4 阶段强调人工干预和节点重跑。

如果搜索执行计划和进度快照都结构化了，后面就更容易支持：

- 从“验证候选”步骤继续执行
- 只重跑浏览器增补搜索
- 人工修改 `preferredDomains` 后继续运行

### 15.3 对 V2 第 5 阶段的帮助

第 5 阶段强调正式导出与演示。

这次升级后，报告和导出页可以展示：

- 来源发现说明
- 来源验证摘要
- 最终采用来源类型分布
- 搜索与采集链路摘要

这会让系统更像正式研究交付，而不只是“AI 输出了一篇 Markdown”。

## 16. 建议的实施顺序

### 第一步：保守增强，不破坏当前 V2 第二阶段

目标：

- 保留现有 `SourceDiscoveryService` 与 `sourceCandidates`
- 增加浏览器预览搜索实现
- 增加运行期候选验证

建议改动：

- 新增 `BrowserPreviewSearchSourceProvider`
- 新增 `CandidateVerifier`
- 扩展 `SourceCandidate`
- 扩展节点配置

### 第二步：补齐运行期搜索编排

目标：

- 把浏览器搜索变成采集节点的标准流程之一

建议改动：

- 新增 `SearchExecutionCoordinator`
- 新增 `BrowserSearchRuntimeService`
- 扩展 `CollectorAgent`
- 增加结构化执行计划和进度输出

### 第三步：补齐搜索轨迹持久化与前端展示

目标：

- 让搜索从“后台做了”变成“用户看得见、能回放”

建议改动：

- 扩展 `AgentExecutionLog.outputData`
- 任务详情页显示搜索执行摘要
- 证据列表展示搜索来源与验证结果

### 第四步：为企业级恢复和审计做准备

目标：

- 搜索失败可恢复
- 搜索策略可配置
- 搜索结果可审计

建议改动：

- 设计 `SearchExecutionTrace`
- 设计搜索失败重试与降级策略
- 增加 `blockedDomains / preferredDomains / retryPolicy`
- 增加浏览器反爬与熔断策略配置

## 17. 本次最值得直接修改的文件

优先级建议如下：

1. `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowFactory.java`
2. `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
3. `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProvider.java`
4. `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
5. `backend/src/main/java/cn/bugstack/competitoragent/config/PlaywrightConfig.java`
6. `backend/src/main/resources/application.yml`

同时建议新增一个独立包：

- `backend/src/main/java/cn/bugstack/competitoragent/search/`

用于承接搜索执行相关类，避免继续堆在 `source` 或 `collector` 下。

还建议把以下改动明确纳入第一批实施：

7. `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`（新增）
8. `backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java`（新增）
9. `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`（新增）
10. `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionPlan.java`（新增）
11. `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionStep.java`（新增）
12. `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
13. `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
14. `frontend/src/types/index.ts`

## 18. 最终结论

如果只考虑“能不能让 Agent 自己调浏览器去搜”，答案当然是能。

但结合你当前工程的真实进度，尤其是：

- `task.md` 的多 Agent、可溯源、可观测目标
- `planV2.md` 已完成的 V2 第 2 阶段补源能力
- `project_rules.md` 对单一职责、容错、结构化计划与进度的要求

更合理的升级方向不是“推翻现有补源规划，全部挪到运行期”，而是：

**保留规划期补源与计划预览，把浏览器搜索升级为运行期验证与增补能力，并为搜索执行计划、进度快照、动作留痕、失败恢复建立企业级骨架。**

一句话概括：

**这次升级最正确的方向，不是让系统“会搜索”，而是让系统“既会搜索，又能规划、能验证、能恢复、能审计”。**
