# AI 驱动的竞品分析 Agent 协作系统项目计划

## 1. 项目核心目标

本项目旨在构建一个面向企业产品研发和市场洞察场景的“AI 驱动的竞品分析 Agent 协作系统”。系统通过多个专职 Agent 的协同工作，模拟真实数字调研小组，自动完成从公开信息采集、结构化整理、商业分析、报告撰写到质检审查的完整竞品分析流程。

核心目标包括：

1. 自动化竞品调研流程  
   将传统依赖人工搜索、复制、整理和撰写的竞品分析流程，转化为可配置、可追踪、可复用的自动化任务流。

2. 多 Agent 分工协作  
   构建采集 Agent、分析 Agent、撰写 Agent、质检 Agent 等独立角色，每个 Agent 具备清晰职责、输入输出边界和可观测执行记录。

3. 基于竞品知识 Schema 的结构化沉淀  
   通过自定义竞品知识 Schema 统一描述竞品基础信息、产品能力、价格策略、目标用户、市场定位、优劣势、证据来源等内容，避免分析结果只停留在自然语言文本中。

4. DAG 式任务流转  
   通过有向无环图组织任务执行顺序，使采集、抽取、分析、撰写、质检等步骤形成明确依赖关系，并支持失败重试、阶段回看和中间产物复用。

5. 结论可溯源  
   每一条关键分析结论都应关联对应证据，包括来源 URL、网页标题、发布时间、采集时间、原文摘要和引用片段，降低“AI 幻觉”带来的业务风险。

6. 系统可观测  
   记录每个 Agent 的执行过程、输入、输出、耗时、状态、调用模型、推理摘要、失败原因和人工干预记录，让用户能够看到报告是如何一步步生成的。

7. 输出高质量竞品报告  
   最终生成结构清晰、证据充分、适合企业产品团队阅读和复用的竞品分析报告，支持在线预览、结构化查看和导出。

## 2. 用户使用流程

第一版用户流程以“创建分析任务 -> Agent 自动执行 -> 查看过程与报告 -> 人工确认导出”为主。

### 2.1 创建竞品分析任务

用户进入系统后，创建一个新的竞品分析任务，需要填写：

1. 分析主题，例如“AI 知识库产品竞品分析”。
2. 本方产品或关注对象，例如“企业级 RAG 知识库平台”。
3. 竞品列表，例如 Notion AI、Glean、Dify、Coze、FastGPT。
4. 分析维度，例如产品功能、目标用户、价格策略、技术能力、差异化优势、市场定位、商业模式。
5. 信息源范围，例如官网、产品文档、博客、新闻、公开测评文章、定价页。
6. 报告语言和篇幅，例如中文、标准版。

### 2.2 系统生成任务 DAG

系统根据用户输入自动生成任务流，包括：

1. 信息采集任务。
2. 页面内容清洗任务。
3. 竞品知识 Schema 抽取任务。
4. 竞品横向对比任务。
5. SWOT 或优劣势分析任务。
6. 报告撰写任务。
7. 质检审查任务。

用户可以在任务启动前预览本次分析的执行步骤。

### 2.3 多 Agent 自动执行

任务启动后，各 Agent 按 DAG 依赖顺序协作：

1. 采集 Agent 根据竞品和信息源抓取公开资料。
2. 抽取 Agent 将非结构化网页内容转为竞品知识 Schema。
3. 分析 Agent 基于结构化数据进行横向对比和洞察生成。
4. 撰写 Agent 将分析结果组织成正式竞品报告。
5. 质检 Agent 仅检查报告结构完整性、字段非空率、证据覆盖率和引用有效性。
6. 第一版不自动回写修订，由用户查看质检结果后决定是否重新执行任务。

### 2.4 查看执行过程

用户可以在任务详情页查看：

1. 当前任务整体状态。
2. DAG 节点执行进度。
3. 每个 Agent 的输入、输出和执行日志。
4. 每个竞品已采集的信息源。
5. 中间结构化数据。
6. 质检问题列表和对应章节。

### 2.5 查看和导出报告

任务完成后，用户可以查看最终报告：

1. 摘要结论。
2. 竞品概览。
3. 功能对比表。
4. 定价和商业模式对比。
5. 用户群体和场景分析。
6. 差异化能力分析。
7. 机会点和风险点。
8. 产品建议。
9. 证据引用列表。

用户可以将报告导出为 Markdown 或 PDF。第一版优先支持 Markdown。

## 3. 核心页面结构

第一版前端明确定位为“演示验证工具”，不是完整后台产品。页面结构以“创建任务 -> 查看进度 -> 查看报告与溯源”为主，尽量减少产品化后台能力的过度设计。

### 3.1 首页 / 任务列表页

主要功能：

1. 展示历史竞品分析任务。
2. 显示最近 10 条任务的名称、创建时间、状态、竞品数量、完成进度。
3. 支持新建分析任务。
4. 支持进入任务详情。
5. 第一版仅保留轻量状态筛选，不做复杂分页、搜索和批量操作。

### 3.2 新建任务页

主要功能：

1. 填写分析主题。
2. 填写本方产品描述。
3. 输入竞品名称和官网 URL。
4. 选择或填写分析维度。
5. 配置采集范围。
6. 选择报告语言和报告模板。
7. 预览即将生成的任务 DAG。
8. 提交并启动任务。

### 3.3 任务详情 / 执行监控页

主要功能：

1. 展示任务基础信息。
2. 展示步骤条或 DAG 节点列表。
3. 展示每个 Agent 的执行状态。
4. 展示执行耗时、开始时间、结束时间。
5. 展示 Agent 日志和中间产物。
6. 支持失败节点重试。
7. 支持查看质检反馈。

第一版优先采用步骤条或节点列表，不引入复杂 DAG 可视化组件。

### 3.4 竞品知识查看区

主要功能：

1. 按竞品展示结构化 Schema 数据。
2. 查看竞品基础信息、核心功能、价格、目标用户、优势、劣势。
3. 查看字段对应证据来源和原文引用片段。
4. 支持人工修正部分字段。

第一版内嵌在任务详情页中，不单独做复杂知识库管理页面。

### 3.5 报告预览页

主要功能：

1. 展示最终竞品分析报告。
2. 展示报告目录。
3. 支持按章节查看。
4. 支持展开每条结论的引用来源和字段级原文片段。
5. 支持导出 Markdown。
6. 展示质检评分和问题列表。

### 3.6 Agent 运行日志面板

主要功能：

1. 展示所有 Agent 调用记录。
2. 支持按 Agent 类型和状态查看。
3. 查看输入、输出、模型调用信息、耗时、错误原因。
4. 支持排查失败任务。

第一版作为任务详情页中的日志面板实现，不单独拆出复杂日志系统。

## 4. 第一版功能

第一版目标是在 3-4 天内完成一个可演示、可跑通主流程的 MVP。重点不是覆盖所有边界场景，而是完整证明“多 Agent 协作生成可溯源竞品报告”这条主链路。

### 4.1 任务管理

1. 创建竞品分析任务。
2. 查看任务列表。
3. 查看任务详情。
4. 记录任务状态：待执行、运行中、成功、失败。
5. 保存任务输入参数。

### 4.2 Agent 角色

第一版至少实现以下 Agent：

1. 采集 Agent  
   根据竞品名称和 URL 获取公开页面内容。第一版采用“双模式抓取”：优先轻量 HTTP 抓取，无法满足时再回退到浏览器渲染抓取。

2. 抽取 Agent  
   从采集内容中提取竞品 Schema，包括产品简介、核心功能、目标用户、价格信息、优势、劣势和证据来源。每个关键字段必须尽量附带原文引用片段。

3. 分析 Agent  
   对多个竞品进行横向比较，输出结构化分析结果，包括功能差异、定位差异、价格差异、机会点和风险点。

4. 撰写 Agent  
   基于结构化分析结果生成 Markdown 格式竞品分析报告。

5. 质检 Agent  
   只检查形式质量，包括章节完整性、字段非空率、证据覆盖率、引用有效性和明显空泛结论，不承担事实正确性判定。

### 4.3 竞品知识 Schema

第一版 Schema 建议包括：

```json
{
  "competitorName": "竞品名称",
  "officialUrl": "官网 URL",
  "summary": "产品简介",
  "positioning": "市场定位",
  "targetUsers": ["目标用户"],
  "coreFeatures": [
    {
      "name": "功能名称",
      "description": "功能描述",
      "evidenceIds": ["证据 ID"]
    }
  ],
  "pricing": {
    "model": "收费模式",
    "plans": ["套餐信息"],
    "evidenceIds": ["证据 ID"]
  },
  "strengths": [
    {
      "point": "优势",
      "evidenceIds": ["证据 ID"]
    }
  ],
  "weaknesses": [
    {
      "point": "劣势",
      "evidenceIds": ["证据 ID"]
    }
  ],
  "sources": [
    {
      "id": "证据 ID",
      "title": "页面标题",
      "url": "来源 URL",
      "contentSnippet": "引用摘要",
      "contextSnippet": "字段直接引用的原文片段",
      "charStart": 0,
      "charEnd": 120,
      "collectedAt": "采集时间"
    }
  ]
}
```

补充约束：

1. 每个关键字段尽量关联至少一个 `contextSnippet`。
2. 如果无法提供直接引用片段，该字段应标记为低置信度或暂不输出。
3. 第一版不存储模型完整推理过程，只存储字段级引用证据，兼顾可解释性与实现成本。

### 4.4 Agent 间数据契约

第一版不直接在 Agent 之间传递松散 JSON 字符串，而是定义强类型契约对象，降低联调返工风险。

建议契约对象包括：

1. `CollectResult`
2. `CollectedDocument`
3. `ExtractResult`
4. `CompetitorKnowledgeDraft`
5. `AnalysisResult`
6. `ReportDraft`
7. `QualityCheckResult`

推荐传递方式：

1. `Collector -> Extractor`：传清洗后的正文文本、标题、URL、采集时间、原始内容引用，不直接把整段 HTML 塞给模型。
2. `Extractor -> Analyst`：传多个竞品的结构化 Schema 列表，而不是单个字符串。
3. `Analyst -> Writer`：传结构化分析结果，不传自由文本。
4. `QualityCheck -> 用户`：传质检问题列表，不自动触发 LLM 二次改写。

契约设计原则：

1. 每个契约对象带 `contractVersion` 字段。
2. 每个节点声明输入类型和输出类型。
3. 执行器在运行前校验节点依赖和数据类型。

### 4.5 DAG 任务流

第一版 DAG 节点可以固定为：

1. `collect_sources`：采集公开信息。
2. `extract_schema`：抽取竞品知识 Schema。
3. `analyze_competitors`：生成横向分析。
4. `write_report`：撰写报告。
5. `quality_check`：质检报告。

每个节点需要记录：

1. 节点 ID。
2. 节点名称。
3. 负责 Agent。
4. 输入数据。
5. 输出数据。
6. 状态。
7. 开始时间。
8. 结束时间。
9. 错误信息。

第一版执行边界：

1. DAG 执行状态持久化到 PostgreSQL，不采用纯内存任务状态。
2. 服务重启后按节点级恢复，不要求节点内部断点续跑。
3. `TaskNode` 需要定义 `dependsOn`、`required`、`retryable`、`maxRetries` 等属性。
4. 第一版执行器默认串行执行，但底层结构按未来可扩展并行的方式设计。

### 4.6 可溯源报告

第一版报告需满足：

1. 报告中的关键结论带有证据编号。
2. 报告末尾包含证据列表。
3. 每个证据包含来源标题、URL、摘要、原文引用片段和采集时间。
4. 结构化字段可回看对应的原文引用片段。
5. 质检 Agent 能检查结论是否缺少证据引用。

### 4.7 可观测性

第一版需要记录：

1. Agent 执行日志。
2. Agent 输入和输出。
3. DAG 节点状态。
4. 模型调用耗时。
5. 错误堆栈或错误摘要。
6. 质检结果。

### 4.8 API 调试与联调

第一版明确采用前后端分离开发方式，调试流程固定如下：

1. 后端启动后自动暴露 OpenAPI 文档。
2. 通过 Knife4j UI 调试任务创建、任务查询、任务执行、报告查看等接口。
3. 前端通过独立终端启动 `frontend` 开发服务。
4. 前端以 REST API 方式调用后端接口完成联调。
5. 接口问题优先在 Knife4j 中定位，再回到前端页面验证整体流程。

### 4.9 报告导出

第一版优先支持：

1. 页面预览 Markdown 报告。
2. 下载 Markdown 文件。

PDF 导出可放入后续版本。

## 5. 后续优化方向

### 5.1 信息采集增强

1. 接入搜索引擎 API，自动发现竞品官网、文档、定价页、新闻和测评文章。
2. 支持多来源采集策略，例如官网优先、近期新闻优先、定价页优先。
3. 支持网页去重、正文抽取、反爬失败提示和重试。
4. 支持上传本地资料，例如 PDF、Word、Excel、调研纪要。

### 5.2 Agent 协作增强

1. 支持动态 DAG，根据任务类型自动增减节点。
2. 增加事实核查 Agent，专门验证关键结论。
3. 增加行业专家 Agent，提供行业语境下的判断。
4. 增加用户画像 Agent，分析竞品目标用户。
5. 增加产品策略 Agent，输出路线图建议。
6. 支持 Agent 间多轮辩论和交叉审查。

### 5.3 Schema 和知识库增强

1. 支持自定义 Schema 字段。
2. 支持不同分析模板，例如 SaaS 产品、AI 工具、消费 App、开发者工具。
3. 沉淀长期竞品知识库，支持历史版本对比。
4. 支持字段置信度评分。
5. 支持证据过期提醒。

### 5.4 报告能力增强

1. 支持多种报告模板，例如简版、高管版、产品经理版、投资分析版。
2. 支持图表化输出，包括功能矩阵、价格对比图、定位象限图。
3. 支持 PDF、Word、PPT 导出。
4. 支持报告在线编辑和人工批注。
5. 支持一键生成汇报材料。

### 5.5 可观测性和评估增强

1. 增加 Agent 执行链路追踪。
2. 增加 Token 消耗统计和成本分析。
3. 增加报告质量评分体系。
4. 增加事实一致性评估。
5. 增加失败任务复盘面板。

### 5.6 企业级能力

1. 用户登录和权限管理。
2. 团队空间和任务共享。
3. 私有数据源接入。
4. 企业知识库集成。
5. 审计日志。
6. 异步任务队列和分布式执行。

## 6. 技术栈要求

当前工程将调整为 `frontend` 与 `backend` 分离结构。第一版技术栈在立项阶段直接固定，包括前端、后端、抓取、AI 编排、数据库、ORM、数据库迁移和接口调试方案，避免后续再做技术迁移和数据迁移，确保后面主要升级业务能力而不是重构基础设施。

### 6.1 后端

1. Java 17 或 Java 21。
2. Spring Boot 3.x。
3. Spring Web：提供 REST API。
4. Spring Validation：参数校验。
5. LangChain4j：作为默认的 AI 编排接入层。
6. Anthropic Claude API：作为第一版目标模型能力来源，关键结构化抽取场景允许直连官方 API。
7. Playwright for Java：作为第一版统一抓取技术栈，内部同时支持轻量 HTTP 请求与浏览器渲染抓取两种模式。
8. Jackson：JSON 序列化和反序列化。
9. Lombok：减少样板代码，可选。
10. Spring Data JPA：第一版唯一 ORM 方案。
11. Flyway：第一版唯一数据库版本管理方案。
12. springdoc-openapi + Knife4j：生成和展示接口调试 UI。

技术定型说明：

1. 抓取层第一版固定采用 Playwright 技术栈，但在 `SourceCollector` 下区分轻量 HTTP 抓取和浏览器渲染抓取两种实现。
2. AI 编排层第一版默认采用 LangChain4j，但通过 `LlmClient` 抽象隔离上层 Agent。
3. 如果 Claude 的结构化输出场景在 LangChain4j 适配层表现不稳定，允许在 `LlmClient` 的某个实现中直接调用 Anthropic API，而不影响 Agent 层代码。
4. 前端第一版固定为 React 18 + TypeScript + Vite，不再采用服务端模板渲染。
5. 数据库第一版直接采用 PostgreSQL，不再使用 H2 作为过渡方案。
6. ORM 第一版固定采用 Spring Data JPA，不再在 JPA 和 MyBatis-Plus 之间切换。
7. 数据库结构变更第一版固定采用 Flyway 管理，不再手工维护 SQL 演进。
8. 接口调试方式第一版固定为 Knife4j UI。

### 6.2 数据存储

第一版固定：

1. PostgreSQL：作为第一版直接使用的正式数据存储。
2. Spring Data JPA：持久化任务、Agent 日志、证据、报告和配置数据。
3. Flyway：管理建表、字段演进、索引和初始化脚本。

这样设计的目的：

1. 第一版开发的数据结构可以直接延续到后续版本，不需要再迁移演示库数据。
2. 数据表结构从一开始就进入可版本化管理，后续迭代只做增量变更。
3. 数据存储方案尽早定型，后续可以专心优化业务流程和分析质量。

后续扩展可考虑：

1. Redis：任务状态缓存和队列辅助。
2. Elasticsearch / OpenSearch：全文检索。
3. 向量数据库：用于语义检索和资料召回。

### 6.3 前端

第一版固定方案：

1. React 18。
2. TypeScript。
3. Vite。
4. React Router：页面路由。
5. Axios：接口请求。
6. Ant Design：快速搭建后台式页面。
7. Markdown 渲染组件：报告预览。
8. 后端通过 REST API 提供全部数据接口。

选型原因：

1. 前后端职责边界清晰，便于你以后主要聚焦后端演进。
2. React 18 + Vite 启动和联调效率高，适合 MVP。
3. Ant Design 适合任务列表、详情、日志、表格类后台界面。

### 6.4 Agent 编排

第一版固定采用自研轻量 DAG Executor，并通过 `LlmClient` 抽象接入模型能力：

1. 定义 TaskNode。
2. 定义 Agent 接口。
3. 根据依赖顺序执行节点。
4. 记录每个节点状态和输入输出。
5. 支持失败中断和节点重试。

第一版补充要求：

1. 执行状态持久化到 PostgreSQL。
2. 任务恢复粒度为节点级，而非节点内部断点续跑。
3. 节点模型需显式表达 `dependsOn`、`required`、`retryable` 和 `maxRetries`。
4. 第一版先串行执行，但执行器设计上保留未来并行扩展空间。

长期演进可考虑：

1. LangGraph 思路的状态图编排。
2. Temporal。
3. Flowable。
4. Camunda。
5. Spring Batch。

### 6.5 可观测性

第一版：

1. 应用日志：Logback。
2. 执行日志入库。
3. 前端展示 Agent 日志。
4. Knife4j 用于接口联调和请求验证。

后续：

1. Micrometer。
2. Prometheus。
3. Grafana。
4. OpenTelemetry。
5. Langfuse 或类似 LLM Observability 平台。

### 6.6 开发与测试方式

第一版开发和测试流程固定如下：

1. 在 `backend` 目录启动 Spring Boot 服务。
2. 打开 Knife4j UI，先完成接口级调试。
3. 在独立终端进入 `frontend` 目录，执行前端开发命令启动页面。
4. 使用前端页面串联创建任务、执行任务、查看报告全流程。
5. 接口问题先通过 Knife4j 复现并修复，再回到前端进行回归测试。

## 7. 项目结构目录

建议目录如下：

```text
Mul-agnet/
├── plan.md
├── README.md
├── backend/
│   ├── pom.xml
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/
│   │   │   │       └── example/
│   │   │   │           └── competitoragent/
│   │   │   │               ├── CompetitorAgentApplication.java
│   │   │   │               ├── agent/
│   │   │   │               │   ├── Agent.java
│   │   │   │               │   ├── AgentContext.java
│   │   │   │               │   ├── AgentResult.java
│   │   │   │               │   ├── collector/
│   │   │   │               │   │   └── CollectorAgent.java
│   │   │   │               │   ├── extractor/
│   │   │   │               │   │   └── SchemaExtractorAgent.java
│   │   │   │               │   ├── analyzer/
│   │   │   │               │   │   └── CompetitorAnalysisAgent.java
│   │   │   │               │   ├── writer/
│   │   │   │               │   │   └── ReportWriterAgent.java
│   │   │   │               │   └── quality/
│   │   │   │               │       └── QualityCheckAgent.java
│   │   │   │               ├── workflow/
│   │   │   │               │   ├── DagExecutor.java
│   │   │   │               │   ├── TaskNode.java
│   │   │   │               │   ├── TaskNodeStatus.java
│   │   │   │               │   ├── WorkflowFactory.java
│   │   │   │               │   └── contract/
│   │   │   │               │       ├── CollectResult.java
│   │   │   │               │       ├── CollectedDocument.java
│   │   │   │               │       ├── ExtractResult.java
│   │   │   │               │       ├── AnalysisResult.java
│   │   │   │               │       ├── ReportDraft.java
│   │   │   │               │       └── QualityCheckResult.java
│   │   │   │               ├── schema/
│   │   │   │               │   ├── CompetitorKnowledge.java
│   │   │   │               │   ├── CompetitorFeature.java
│   │   │   │               │   ├── PricingInfo.java
│   │   │   │               │   ├── EvidenceSource.java
│   │   │   │               │   └── FieldEvidenceRef.java
│   │   │   │               ├── task/
│   │   │   │               │   ├── AnalysisTask.java
│   │   │   │               │   ├── AnalysisTaskStatus.java
│   │   │   │               │   └── AnalysisTaskService.java
│   │   │   │               ├── report/
│   │   │   │               │   ├── Report.java
│   │   │   │               │   ├── ReportService.java
│   │   │   │               │   └── MarkdownExportService.java
│   │   │   │               ├── source/
│   │   │   │               │   ├── SourceCollector.java
│   │   │   │               │   ├── ApiRequestSourceCollector.java
│   │   │   │               │   ├── BrowserSourceCollector.java
│   │   │   │               │   └── PageContentCleaner.java
│   │   │   │               ├── llm/
│   │   │   │               │   ├── LlmClient.java
│   │   │   │               │   ├── LangChain4jLlmClient.java
│   │   │   │               │   ├── AnthropicDirectClient.java
│   │   │   │               │   ├── PromptTemplateService.java
│   │   │   │               │   └── prompts/
│   │   │   │               ├── log/
│   │   │   │               │   ├── AgentExecutionLog.java
│   │   │   │               │   └── AgentLogService.java
│   │   │   │               ├── controller/
│   │   │   │               │   ├── TaskController.java
│   │   │   │               │   ├── ReportController.java
│   │   │   │               │   └── AgentLogController.java
│   │   │   │               ├── config/
│   │   │   │               │   ├── PlaywrightConfig.java
│   │   │   │               │   ├── LangChain4jConfig.java
│   │   │   │               │   └── Knife4jConfig.java
│   │   │   │               └── common/
│   │   │   │                   ├── ApiResponse.java
│   │   │   │                   ├── BusinessException.java
│   │   │   │                   └── JsonUtils.java
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       └── prompts/
│   │   └── test/
│   │       └── java/
│   │           └── com/
│   │               └── example/
│   │                   └── competitoragent/
│   │                       ├── workflow/
│   │                       ├── agent/
│   │                       ├── controller/
│   │                       └── task/
│   └── scripts/
│       ├── start-backend.bat
│       └── start-backend.sh
├── frontend/
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── router/
│       ├── api/
│       ├── views/
│       │   ├── TaskListPage.tsx
│       │   ├── TaskCreatePage.tsx
│       │   ├── TaskDetailPage.tsx
│       │   └── ReportPage.tsx
│       ├── components/
│       │   ├── DagNodeList.tsx
│       │   ├── AgentLogPanel.tsx
│       │   ├── EvidenceList.tsx
│       │   └── MarkdownReport.tsx
│       └── styles/
│           └── index.css
│   └── scripts/
│       ├── start-frontend.bat
│       └── start-frontend.sh
└── docs/
    ├── schema.md
    ├── api.md
    └── prompts.md
```

## 8. 分阶段开发计划

第一版计划用 3-4 天完成，目标是跑通主流程并具备演示价值。

### 第 1 阶段：项目基础与数据模型，0.5 天

目标：

1. 初始化 `frontend` 与 `backend` 分离项目结构。
2. 确定竞品知识 Schema。
3. 建立核心领域模型。
4. 建立任务、节点、日志、证据、报告的数据结构。
5. 配置 PostgreSQL、Spring Data JPA 和 Flyway。

主要工作：

1. 创建 `backend` 和 `frontend` 基础目录。
2. 定义 AnalysisTask、TaskNode、AgentExecutionLog、EvidenceSource、CompetitorKnowledge、Report 等模型。
3. 定义任务状态和节点状态枚举。
4. 设计 PostgreSQL 表结构和实体映射。
5. 编写统一 API 返回结构。
6. 初始化 React 18 + TypeScript + Vite 工程骨架。
7. 初始化 Flyway 基础迁移脚本。

### 第 2 阶段：DAG 执行器与 Agent 框架，0.5-1 天

目标：

1. 实现轻量 DAG Executor。
2. 定义统一 Agent 接口。
3. 将不同 Agent 接入 DAG 节点。
4. 完成节点状态流转和日志记录。
5. 接入 Knife4j，打通基础接口调试页面。

主要工作：

1. 定义 Agent 输入输出协议和强类型数据契约。
2. 实现固定 DAG：采集、抽取、分析、撰写、质检。
3. 实现按依赖顺序执行节点。
4. 每个节点执行前后记录日志。
5. 支持节点失败时中断任务。
6. 支持简单重试。
7. 完成基础 OpenAPI 文档暴露。
8. 完成节点状态持久化和节点级恢复语义。

### 第 3 阶段：核心 Agent MVP，1 天

目标：

1. 实现采集 Agent。
2. 实现抽取 Agent。
3. 实现分析 Agent。
4. 实现撰写 Agent。
5. 实现质检 Agent。

主要工作：

1. 采集 Agent 基于 Playwright 技术栈实现轻量 HTTP 抓取和浏览器渲染回退抓取。
2. 抽取 Agent 通过 `LlmClient` 调用模型生成结构化 Schema，并尽量附带字段级原文引用片段。
3. 分析 Agent 输出结构化竞品横向对比结果。
4. 撰写 Agent 基于结构化分析结果生成 Markdown 报告。
5. 质检 Agent 只检查报告结构、证据引用、字段覆盖率和明显空字段。
6. 保存所有中间产物。

说明：

1. 如果 LangChain4j 在 Claude 结构化输出场景不稳定，可由 `AnthropicDirectClient` 承担关键抽取任务。
2. 如果大模型接口暂时不可用，可先实现 Mock `LlmClient`，保证主流程可演示。
3. Prompt 模板需要独立存放，便于后续优化。

### 第 4 阶段：后端 API 与页面联调，0.5-1 天

目标：

1. 提供任务创建、查询、执行、报告查看等 API。
2. 完成最小可用 React 页面。
3. 用户可以从页面创建任务并查看报告。
4. 可以通过 Knife4j 和前端页面双通道完成联调。

主要工作：

1. 实现 TaskController。
2. 实现 ReportController。
3. 实现 AgentLogController。
4. 实现任务列表页。
5. 实现新建任务页。
6. 实现任务详情页。
7. 实现报告预览页。
8. 打通前端请求后端 API 的代理或跨域配置。

### 第 5 阶段：质检闭环、溯源和演示打磨，0.5 天

目标：

1. 强化字段级证据引用和报告溯源体验。
2. 展示 Agent 执行过程。
3. 完成演示数据和验收测试。
4. 整理 README 和使用说明。
5. 固化“后端启动 -> Knife4j 调试 -> 前端启动 -> 页面回归”的测试流程。

主要工作：

1. 报告中统一使用证据编号并支持展开原文片段。
2. 任务详情页展示每个节点输入输出。
3. 报告页展示证据列表。
4. 准备 1-2 个示例任务。
5. 验证失败节点日志是否可查看。
6. 补充基础测试。
7. 检查 Knife4j 接口调试链路是否完整。

## 9. 每个阶段验收标准

### 第 1 阶段验收标准

1. `backend` 和 `frontend` 两个目录结构已建立。
2. 核心包结构已经创建。
3. 竞品知识 Schema 已定义。
4. 任务、节点、日志、证据、报告模型已完成。
5. 能够创建并保存一个分析任务。
6. 数据结构能够表达至少 2 个竞品的信息。
7. PostgreSQL 已完成初始化建表并可正常连接。
8. Flyway 迁移脚本可以成功执行。
9. React 18 + TypeScript + Vite 基础工程可以正常启动。

### 第 2 阶段验收标准

1. DAG Executor 能按固定顺序执行节点。
2. 每个节点状态能够从待执行变为运行中、成功或失败。
3. 节点执行失败时，任务状态能够变为失败。
4. 每个 Agent 的输入和输出都能被记录。
5. 至少能通过 Mock Agent 跑完整个 DAG。
6. 日志中能看到每个节点的开始时间、结束时间和耗时。
7. Knife4j 页面可以正常访问并调试基础接口。
8. `TaskNode` 已显式定义 `dependsOn`、`required`、`retryable` 和 `maxRetries`。
9. 执行状态已入库，服务重启后可基于节点状态恢复。

### 第 3 阶段验收标准

1. 采集 Agent 能在轻量 HTTP 抓取和浏览器渲染抓取之间完成策略切换。
2. 抽取 Agent 能通过 `LlmClient` 输出符合 Schema 的结构化竞品信息。
3. 抽取结果中的关键字段至少能附带基本原文引用片段。
4. 分析 Agent 能基于多个竞品生成结构化横向对比结果。
5. 撰写 Agent 能生成包含完整章节的 Markdown 报告。
6. 质检 Agent 能输出质检结果、问题列表和是否通过。
7. 报告中的关键结论至少包含基本证据引用。
8. 即使使用 Mock `LlmClient`，也能跑通完整主流程。

### 第 4 阶段验收标准

1. 用户可以通过页面或 API 创建竞品分析任务。
2. 用户可以查看任务列表。
3. 用户可以进入任务详情查看 DAG 节点状态。
4. 用户可以查看 Agent 执行日志。
5. 用户可以查看结构化竞品信息。
6. 用户可以查看最终 Markdown 报告。
7. 前后端主流程无阻塞错误。
8. 可以先在 Knife4j 中完成接口调试，再在前端页面完成联调验证。

### 第 5 阶段验收标准

1. 报告中的证据列表完整展示来源标题、URL、摘要、原文片段和采集时间。
2. 每条关键结论能关联至少一个证据编号。
3. 质检 Agent 能发现缺少证据或缺少章节的问题。
4. 任务执行失败时，用户能看到失败节点和错误摘要。
5. 至少准备一个可稳定演示的竞品分析案例。
6. README 中包含 `backend`、`frontend` 启动方式、Knife4j 地址和演示流程。
7. 已验证“后端启动 -> Knife4j 调试 -> 前端启动 -> 页面测试”流程。
8. 第一版可以在 3-4 天内完成演示交付。
