# 3.3 提取结构化继承前提基线

## 1. 文档定位

本文档只回答一件事：

`3.3 提取结构化` 在启动时，应该把 `3.2 搜索与采集` 的哪些结论直接视为既成前提。

它不是：

- `ExtractionStructured.md` 的替代品；
- 3.3 架构设计文档；
- 3.3 实施计划文档；
- 3.2 搜索与采集的完整阶段总结副本。

本文档存在的目的，是把 3.2 对 3.3 有约束力的输入契约、冻结边界和 live 结论收口到 `docs/superpowers/ExtractionStructured/problem/` 目录，避免后续讨论 3.3 时继续跨模块来回跳读。

---

## 2. 3.2 对 3.3 的总前提

### 2.1 阶段结论

3.2 当前已经足以支撑 3.3 启动。

这句话的精确定义不是“搜索与采集已经完美”，而是：

1. 3.3 所需的正式输入契约已经具备；
2. 证据来源已经可追溯；
3. 采集质量信号已经可以沿下游传递；
4. 缺口已经能被系统解释，而不是只能靠人工猜。

因此，3.3 当前不应继续等待“所有站点都采得更全”“所有页面都带结构块”“所有任务都直接通过最终质量门禁”之后再开始。

### 2.2 3.2 不再作为当前主停点

真实任务 `50` 的阶段性结论已经说明：

1. 当前问题不再首先表现为搜索与采集链路崩溃；
2. 当前问题已经进入 3.3 范围，即结构化提取、提取重试、非空业务字段 acceptance gate 和下游消费边界；
3. 不能因为任务最终质量门禁失败，就反向推翻“3.2 已可为 3.3 提供输入”的结论。

---

## 3. 3.3 直接继承的输入契约

下列对象和字段，应被视为 3.3 的既有输入基线，而不是待搜索与采集继续发明的内容。

| 输入对象 / 字段 | 3.3 继承方式 | 对 3.3 的约束 |
| --- | --- | --- |
| `sourceUrls` | 正式继承 | 所有结构化字段、证据片段、诊断对象都必须能回指来源；缺失时返回空列表并显式标缺口 |
| `searchAudit` | 可选继承 | 用于解释“证据从哪里被发现”，不是 extractor 主输入本体 |
| `collectionAudit` | 正式继承 | 用于解释“证据如何被采集、失败发生在哪一层” |
| `qualitySignals` | 正式继承 | 3.3 不能吞掉质量信号，必须参与提取策略与失败诊断 |
| `structuredBlocks` | 正式继承 | 可作为优先结构化证据，但不能被误当成唯一证据来源 |
| `qualityScore` | 条件继承 | 可参与排序、提示和诊断，但不能单独替代字段级证据 |
| `failureKind` | 条件继承 | 用于识别采集失败类型，避免把采集失败静默降级成“空证据” |
| `DownstreamEvidenceView` | 正式继承 | 当前 3.3 最重要的统一运行期证据输入边界 |
| `EvidenceFragment` | 正式继承 | 已是跨链路共享追溯契约，不属于 extractor 私有对象 |
| `SectionEvidenceBundle` | 正式继承 | 已承接章节级覆盖、缺口和来源追溯语义 |
| `evidenceCoverage` | 正式继承 | 3.3 必须继续输出和消费字段/章节/结论维度的覆盖状态 |
| `TASK` 记忆边界 | 正式继承 | extractor 当前默认产物是任务现场快照，不得再默认沉入 `DOMAIN` |

### 3.1 3.3 对这些契约的最小消费要求

3.3 至少要能基于上述契约继续完成：

1. 从可读正文和结构块中抽取非空业务字段；
2. 在 `structuredBlocks` 不足但正文可读时执行 fallback 提取；
3. 对模型返回空业务字段的场景执行 retry、失败阻断或人工介入；
4. 把 `sourceUrls / qualitySignals / structuredBlocks / evidenceCoverage` 原样传递到结构化结果与诊断；
5. 保持 `TASK / DOMAIN` 语义分层，不把 extractor 产物伪装成长期记忆。

---

## 4. 3.2 已冻结、3.3 不再回头展开的事项

以下事项仍然真实存在，但当前应被视为 3.2 冻结后的后续专项或缺陷队列，不再作为 3.3 前置阻塞项：

1. 真实站点覆盖率仍受反爬、登录、强交互页面、动态加载影响；
2. `DirectHtmlReader -> JinaReader -> Playwright` 路由虽然已冻结为 3.2 基线，但不代表所有页面都天然高质量；
3. Playwright 并发上下文池属于后续稳定性和吞吐优化专项；
4. 跨任务缓存、隐私边界、TTL、失效设计属于后续效率专项；
5. RSS 持续订阅、cursor、长期增量监控不属于当前 3.3 前置条件；
6. 跨重启 replay 持久化底座仍是后续平台任务。

3.3 当前不应借题发挥，把这些问题重新带回搜索与采集主线。

---

## 5. task `50` 带来的继承结论

### 5.1 已经确认的事实

基于 task `50` 的最新 live 验收，可以继承以下事实：

1. `/api/report/50`、`/api/report/50/evidences`、`/api/task/50/replay` 已能暴露统一证据视图、解释型诊断和来源追溯；
2. extractor 先前曾把 `0` 个业务字段的模型输出误判为成功；
3. 当前已经增加“零业务字段保护”，使任务停在正确节点，避免空知识继续污染 analyzer / writer / reviewer。

### 5.2 对 3.3 的直接含义

这说明 3.3 当前首先要继续拆的不是“搜索与采集有没有彻底做好”，而是：

1. extractor 在已有证据下，为什么仍可能抽不出非空业务字段；
2. 这是 prompt、retry、正文 fallback、正式输入边界，还是下游消费边界的问题；
3. 当 extractor 已经通过时，后续不过关是否已经转移到 writer / reviewer / delivery。

---

## 6. 3.3 明确不能再假设的事情

进入 3.3 后，不应再默认以下假设成立：

1. 每个竞品都有完美证据；
2. 每个 URL 都采集成功；
3. 每个页面都有高质量 `structuredBlocks`；
4. 质量门禁失败一定等于搜索与采集失败；
5. `sourceUrls` 已回填就等于业务字段提取成功；
6. 采集层给出了结构块，extractor 就一定能抽出非空业务字段；
7. extractor 成功以后，writer / reviewer / delivery 就一定会通过。

---

## 7. 3.3 架构讨论前必须守住的边界

在开始写 3.3 架构前，先把以下边界当成已知约束：

1. 不绕过 `sourceUrls` 红线；
2. 不吞掉 `qualitySignals`；
3. 不把采集失败静默当成空证据；
4. 不把 `sourceUrls` 回填误判为抽取成功；
5. 不让 `TASK` 快照默认写成 `DOMAIN` 记忆；
6. 不让 `0` 个业务字段的模型输出继续流向 analyzer / writer / reviewer；
7. 不把 `DownstreamEvidenceView`、`EvidenceFragment`、`SectionEvidenceBundle` 重新拆回各节点私有语义；
8. 不把“搜索与采集仍有缺点”误写成“3.3 还不能启动”。

---

## 8. 对后续架构文档的直接输入

后续 `ExtractionStructured` 架构文档，至少要基于本文回答以下问题：

1. 3.3 的正式输入边界到底是什么，`DownstreamEvidenceView`、`ExtractResult`、`CompetitorKnowledge` 各自扮演什么角色；
2. extractor 应如何同时消费 `structuredBlocks` 与 readable content；
3. analyzer 应优先消费 extractor 哪一层输出，repository task snapshot 又应退回到什么位置；
4. 结构化失败应该如何分层为：
   - 提取输入不足
   - 模型输出为空
   - 字段覆盖不足
   - 更下游 `unsupported_claim / actionability` 不通过
5. 哪些问题仍属于 3.2 后续专项，哪些问题已经属于 3.3 主停点。

---

## 9. 与其他文档的关系

1. [ExtractionStructured.md](/E:/java_study/Mul-agnet/docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md)
   - 负责 3.3 自身链路诊断；
   - 重点在 `ExtractResult vs CompetitorKnowledge`、`TASK / DOMAIN`、共享追溯契约和当前 blocking。
2. [2026-06-20-search-and-collection-3.2-summary.md](/E:/java_study/Mul-agnet/docs/superpowers/search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md)
   - 仍然是 3.2 的完整阶段总结；
   - 本文只抽取其中对 3.3 有约束力的继承前提，不替代原文。

当前建议的阅读顺序是：

1. 本文；
2. `ExtractionStructured.md`；
3. 再决定是否需要进入 3.3 架构设计文档。
