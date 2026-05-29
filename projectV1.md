# AI 驱动的竞品分析 Agent 协作系统 — V1 技术手册

> **版本：** V1（MVP）
> **完成日期：** 2026-05
> **目的：** 本文档是 V1 完整技术留存，供 V2 升级后对比参考。

---

## 目录

1. [项目概述](#1-项目概述)
2. [总体架构](#2-总体架构)
3. [技术栈](#3-技术栈)
4. [工程结构](#4-工程结构)
5. [Agent 框架设计](#5-agent-框架设计)
6. [五个 Agent 详解](#6-五个-agent-详解)
7. [DAG 工作流引擎](#7-dag-工作流引擎)
8. [LLM 集成层](#8-llm-集成层)
9. [信息采集层](#9-信息采集层)
10. [数据库设计](#10-数据库设计)
11. [Agent 间数据契约](#11-agent-间数据契约)
12. [REST API 设计](#12-rest-api-设计)
13. [前端实现](#13-前端实现)
14. [任务执行全流程](#14-任务执行全流程)
15. [配置体系](#15-配置体系)
16. [V1 设计决策与限制](#16-v1-设计决策与限制)

---

## 1. 项目概述

### 1.1 项目定位

AI 驱动的竞品分析 Agent 协作系统，通过多个专职 Agent 的协同工作，模拟真实数字调研小组，自动完成从公开信息采集、结构化整理、商业分析、报告撰写到质检审查的完整竞品分析流程。

### 1.2 V1 核心目标

1. 自动化竞品调研流程：将传统依赖人工搜索、复制、整理和撰写的流程转化为可配置、可追踪、可复用的自动化任务流。
2. 多 Agent 分工协作：构建采集、抽取、分析、撰写、质检五个独立 Agent。
3. 基于竞品知识 Schema 的结构化沉淀：统一描述竞品信息，避免分析结果只停留在自然语言文本中。
4. DAG 式任务流转：通过有向无环图组织任务执行顺序，形成明确依赖关系。
5. 结论可溯源：每条关键分析结论关联对应证据，包括来源 URL、网页标题、原文摘要和引用片段。
6. 系统可观测：记录每个 Agent 的执行过程、输入、输出、耗时、状态、调用模型、推理摘要。

### 1.3 V1 范围边界

- **固定 5 节点 DAG**：`collect_sources → extract_schema → analyze_competitors → write_report → quality_check`
- **串行执行**：节点按依赖顺序依次执行，不并行
- **Mock 模式**：支持无 API Key 的演示模式
- **Markdown 报告**：仅支持 Markdown 导出，不含 PDF
- **不自动修订**：质检结果不自动触发重新生成
- **节点级恢复**：服务重启后基于 DB 状态恢复，不做断点续跑

---

## 2. 总体架构

### 2.1 分层架构

```
┌────────────────────────────────────────────────────┐
│                   Frontend (React 18)               │
│   TaskList │ TaskCreate │ TaskDetail │ ReportPage  │
└──────────────────────┬─────────────────────────────┘
                       │ REST API (Axios)
┌──────────────────────┴─────────────────────────────┐
│               Controller Layer                      │
│   TaskController │ ReportController                 │
│   SchemaController │ AgentLogController             │
└──────────────────────┬─────────────────────────────┘
                       │
┌──────────────────────┴─────────────────────────────┐
│               Service Layer                         │
│   AnalysisTaskService │ SchemaService               │
│   ReportService │ AgentLogService                   │
└──────────────────────┬─────────────────────────────┘
                       │
┌──────────────────────┴─────────────────────────────┐
│               Workflow Engine                       │
│   AnalysisTaskRunner (@Async)                       │
│   DagExecutor (serial execution)                    │
│   WorkflowFactory (fixed 5-node DAG)               │
└──────────────────────┬─────────────────────────────┘
                       │
┌──────────────────────┴─────────────────────────────┐
│               Agent Layer (5 agents)                │
│   CollectorAgent → SchemaExtractorAgent             │
│   → CompetitorAnalysisAgent → ReportWriterAgent     │
│   → QualityReviewAgent                              │
└──────────────────────┬─────────────────────────────┘
                       │
┌──────────────────────┴─────────────────────────────┐
│          Infrastructure Layer                       │
│   SourceCollector (Playwright / Mock)               │
│   LlmClient (OpenAI-compatible / Mock)              │
│   PromptTemplateService                             │
│   JPA Repositories + Flyway + PostgreSQL            │
└────────────────────────────────────────────────────┘
```

### 2.2 核心设计模式

| 模式 | 应用场景 |
|------|---------|
| **Agent 接口模式** | 所有 Agent 实现统一的 `Agent` 接口，`BaseAgent` 提供日志/计时/异常包装 |
| **策略模式** | `SourceCollector`（HTTP vs Playwright）和 `LlmClient`（真实 vs Mock）通过 `@ConditionalOnProperty` 切换 |
| **模板方法模式** | `BaseAgent.execute()` 定义执行骨架，子类实现 `doExecute()` |
| **DAG 引擎模式** | `DagExecutor` 按依赖顺序串行执行节点，检查依赖满足后才执行下游 |
| **数据契约模式** | Agent 间通过强类型契约对象传递数据，不传松散 JSON 字符串 |
| **异步执行** | `@Async` + `TransactionSynchronization.afterCommit()` 保证任务异步执行 |

---

## 3. 技术栈

### 3.1 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.3.5 | 应用框架 |
| Spring Web | 6.x | REST API |
| Spring Data JPA | 3.3.5 | ORM |
| Spring Validation | 3.3.5 | 参数校验 |
| Flyway | 10.x | 数据库迁移管理 |
| PostgreSQL | 16+ | 主数据库 |
| H2 | — | 开发回退数据库 |
| LangChain4j | 0.35.0 | AI 编排接入层 |
| Playwright for Java | 1.44.0 | 浏览器渲染抓取 |
| Jackson | 2.17.x | JSON 序列化 |
| Lombok | 1.18.x | 样板代码消除 |
| Knife4j / springdoc-openapi | 4.5.0 | API 文档与调试 UI |

### 3.2 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18.x | UI 框架 |
| TypeScript | 5.x | 类型安全 |
| Vite | 5.x | 构建工具 |
| Ant Design | 5.x | UI 组件库 |
| React Router | 6.x | 页面路由 |
| Axios | 1.x | HTTP 客户端 |
| react-markdown | 9.x | Markdown 渲染 |
| dayjs | 1.x | 日期处理 |

### 3.3 AI 提供商支持

V1 通过 OpenAI-compatible API 协议支持以下提供商：

| 提供商 | Base URL | 状态 |
|--------|----------|------|
| DeepSeek | `https://api.deepseek.com` | 默认激活 |
| 阿里云百炼 (Bailian) | `https://dashscope.aliyuncs.com` | 已配置 |
| SiliconFlow | `https://api.siliconflow.cn` | 已配置 |
| Ollama | `http://localhost:11434` | 注释备用 |

---

## 4. 工程结构

```
Mul-agnet/
├── plan.md                          # V1 架构计划 (823 行)
├── V2.md                            # V2 升级计划
├── project_rules.md                 # 编码规范（中文注释、OOP、容错、溯源）
├── projectV1.md                     # 本文档
├── pom.xml                          # 根 Maven POM（多模块）
├── backend/
│   ├── pom.xml                      # Spring Boot 3.3.5 + 全部依赖
│   ├── data/                        # H2 数据库文件（dev 回退）
│   ├── logs/                        # 应用日志
│   └── src/main/
│       ├── java/cn/bugstack/competitoragent/
│       │   ├── CompetitorAgentApplication.java    # Spring Boot 入口
│       │   ├── agent/                # Agent 框架 + 5 个 Agent 实现
│       │   │   ├── Agent.java                     # Agent 接口
│       │   │   ├── AgentContext.java              # 执行上下文（共享状态）
│       │   │   ├── AgentResult.java               # 执行结果
│       │   │   ├── BaseAgent.java                 # 抽象基类
│       │   │   ├── collector/CollectorAgent.java
│       │   │   ├── extractor/SchemaExtractorAgent.java
│       │   │   ├── analyzer/CompetitorAnalysisAgent.java
│       │   │   ├── writer/ReportWriterAgent.java
│       │   │   └── reviewer/QualityReviewAgent.java
│       │   ├── workflow/             # DAG 工作流引擎
│       │   │   ├── DagExecutor.java               # DAG 执行器
│       │   │   ├── WorkflowFactory.java           # DAG 节点工厂
│       │   │   └── contract/                      # Agent 间数据契约
│       │   ├── llm/                  # LLM 集成
│       │   │   ├── LlmClient.java                 # LLM 客户端接口
│       │   │   ├── OpenAiCompatibleClient.java    # 真实 LLM 客户端
│       │   │   ├── MockLlmClient.java             # Mock LLM 客户端
│       │   │   ├── PromptTemplateService.java     # Prompt 模板引擎
│       │   │   ├── LlmException.java
│       │   │   └── TokenUsage.java
│       │   ├── source/               # 信息采集
│       │   │   ├── SourceCollector.java           # 采集器接口
│       │   │   ├── PlaywrightPageCollector.java   # 双模式采集器
│       │   │   └── MockSourceCollector.java       # Mock 采集器
│       │   ├── model/                # 领域模型
│       │   │   ├── entity/                         # JPA 实体 (7 个)
│       │   │   ├── dto/                            # 数据传输对象 (6 个)
│       │   │   └── enums/                          # 枚举 (3 个)
│       │   ├── repository/           # Spring Data JPA Repositories (7 个)
│       │   ├── controller/           # REST Controller (4 个)
│       │   ├── task/                 # 任务编排
│       │   │   ├── AnalysisTaskService.java        # 任务 CRUD
│       │   │   └── AnalysisTaskRunner.java         # 异步任务执行器
│       │   ├── report/ReportService.java
│       │   ├── schema/SchemaService.java
│       │   ├── log/AgentLogService.java
│       │   ├── config/               # Spring 配置 (5 个)
│       │   └── common/               # 通用工具 (6 个)
│       └── resources/
│           ├── application.yml                     # 主配置
│           ├── db/migration/                       # Flyway 迁移脚本 (3 个)
│           └── static/                             # 静态资源
├── frontend/
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts                # Vite 配置（代理 /api 到 9093）
│   └── src/
│       ├── main.tsx                  # React 入口
│       ├── App.tsx                   # 路由定义
│       ├── api/client.ts             # Axios 客户端
│       ├── types/index.ts            # TypeScript 类型
│       ├── pages/                    # 4 个页面
│       │   ├── TaskListPage.tsx      # 任务列表（仪表盘）
│       │   ├── TaskCreatePage.tsx    # 新建任务
│       │   ├── TaskDetailPage.tsx    # 任务详情 + DAG 视图
│       │   └── ReportPage.tsx        # 报告预览
│       ├── components/               # 3 个组件
│       │   ├── AgentLogPanel.tsx     # Agent 日志面板
│       │   ├── EvidenceList.tsx      # 证据列表
│       │   └── MarkdownReport.tsx    # Markdown 渲染
│       └── styles/index.css
└── logs/                             # 顶层日志目录
```

---

## 5. Agent 框架设计

### 5.1 Agent 接口 (`Agent.java`)

```java
public interface Agent {
    AgentType getType();       // 返回 Agent 类型标识（枚举）
    String getName();           // 返回显示名称，用于日志和 UI
    AgentResult execute(AgentContext context);  // 执行核心逻辑
}
```

所有 Agent 必须实现此接口。`AgentType` 枚举定义了 5 种角色：`COLLECTOR`、`EXTRACTOR`、`ANALYZER`、`WRITER`、`REVIEWER`。

### 5.2 AgentContext — 执行上下文 (`AgentContext.java`)

`AgentContext` 在整个 DAG 执行过程中共享，上下游 Agent 通过它传递中间产物。

**核心字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | Long | 分析任务 ID |
| `taskName` | String | 任务名称 |
| `subjectProduct` | String | 本方产品名称 |
| `competitorNames` | String | 竞品名称列表（JSON 数组字符串） |
| `competitorUrls` | String | 竞品 URL 列表（JSON 数组字符串） |
| `analysisDimensions` | String | 分析维度（JSON 数组字符串） |
| `sourceScope` | String | 信息源范围（JSON 数组字符串） |
| `reportLanguage` | String | 报告语言 |
| `reportTemplate` | String | 报告模板 |
| `currentNodeName` | String | 当前正在执行的节点名称 |
| `traceId` | String | 追踪 ID |
| `sharedState` | `Map<String, String>` | **节点间共享状态**（ConcurrentHashMap） |

**共享状态机制：**

- Key 为节点名称（如 `"collect_sources"`），Value 为该节点的输出（JSON 字符串）
- 上游通过 `context.putSharedOutput(nodeName, output)` 写入
- 下游通过 `context.getSharedOutput("collect_sources")` 读取
- 线程安全（ConcurrentHashMap）

### 5.3 AgentResult — 执行结果 (`AgentResult.java`)

```java
public class AgentResult {
    TaskNodeStatus status;      // SUCCESS / FAILED
    String outputData;          // 输出数据（JSON 字符串）
    String outputSummary;       // 输出摘要（用于 UI 展示）
    long durationMs;            // 执行耗时（毫秒）
    String reasoningSummary;    // LLM 推理过程摘要
    String tokenUsage;          // Token 用量 JSON: {"input":1500,"output":800,"total":2300}
    String modelName;           // 使用的 LLM 模型名称
    String promptUsed;          // 实际 Prompt 内容
    String errorMessage;        // 失败时的错误信息
}
```

工厂方法：
- `AgentResult.success(outputData, outputSummary)` — 创建成功结果
- `AgentResult.failed(errorMessage)` — 创建失败结果

### 5.4 BaseAgent — 抽象基类 (`BaseAgent.java`)

采用**模板方法模式**，`execute()` 定义执行骨架，子类实现 `doExecute()`：

```
execute(context):
  1. 记录开始时间
  2. 获取 traceId
  3. 打印开始日志
  4. try:
       result = doExecute(context)   ← 子类实现
       result.durationMs = now - start
       log 完成日志
     catch Exception:
       result = AgentResult.failed(errorMessage)
       log 异常日志
  5. saveExecutionLog(context, result, traceId)  ← 入库
  6. return result
```

**日志持久化：** `saveExecutionLog()` 将一次 Agent 执行的完整信息写入 `agent_execution_log` 表，包括：
- 输入数据 (`inputData`)
- 输出数据 (`outputData`)
- 执行状态 (`status`)
- 使用的模型名 (`modelName`)
- Prompt 全文 (`promptUsed`)
- 耗时 (`durationMs`)
- Token 用量 (`tokenUsage`)
- Trace ID (`traceId`)
- 推理摘要 (`reasoningSummary`)
- 是否需要人工介入 (`needsHumanIntervention`)

**容错设计：** 日志入库失败不会中断主流程（catch 后仅打 error 日志）。

---

## 6. 五个 Agent 详解

### 6.1 采集 Agent — `CollectorAgent`

| 属性 | 值 |
|------|-----|
| 类路径 | `agent/collector/CollectorAgent.java` |
| AgentType | `COLLECTOR` |
| 中文名 | 采集Agent |
| 上游依赖 | 无（DAG 入口节点） |
| 输出给 | SchemaExtractorAgent |

**职责：**
遍历竞品 URL 列表，调用 `SourceCollector` 抓取每个页面内容，清洗正文后存入 `evidence_source` 表，输出采集结果摘要 JSON。

**实现细节：**

1. 解析 `context.getCompetitorNames()` 和 `context.getCompetitorUrls()`，两者都是 JSON 数组
2. URL 分配策略：
   - 如果 URL 数量 == 竞品数量 → 一一对应
   - 否则每个竞品分配所有 URL
   - 如果都没有 URL → 标记为 skipped（V1 不支持自动搜索）
3. 对每个 URL 调用 `sourceCollector.collect(url, competitorName, "官网")`
4. 为每次采集生成全局唯一证据编号 `E001`、`E002`...
5. 将采集结果保存到 `EvidenceSource` 实体（taskId, competitorName, evidenceId, title, url, contentSnippet, fullContent, pageMetadata, collectedAt）
6. 单个采集失败不阻断流程
7. 输出 JSON 格式：
```json
{
  "totalCollected": 3,
  "totalEvidenceIds": 3,
  "results": [
    {"competitor": "Notion AI", "url": "https://...", "evidenceId": "E001",
     "success": true, "title": "...", "contentLength": 5000, "errorMessage": null}
  ]
}
```

### 6.2 抽取 Agent — `SchemaExtractorAgent`

| 属性 | 值 |
|------|-----|
| 类路径 | `agent/extractor/SchemaExtractorAgent.java` |
| AgentType | `EXTRACTOR` |
| 中文名 | 抽取Agent |
| 上游依赖 | `collect_sources` |
| 输出给 | CompetitorAnalysisAgent |

**职责：**
读取 Collector 采集的原始页面内容，通过 LLM 抽取为结构化的 `CompetitorKnowledge` 并持久化到数据库。

**实现细节：**

1. 从 `evidence_source` 表读取当前任务的所有证据
2. 按 `competitorName` 分组
3. 对每个竞品：
   a. 拼接其全部采集内容为 Prompt 输入（每个来源截断至 8000 字防止超 token）
   b. 调用 `promptService.render("extractor", {competitorName, collectedContent})` 渲染 Prompt
   c. 调用 `llmClient.chatForJson(systemPrompt, prompt, "ExtractedSchema")` 获取结构化 JSON
   d. 清洗 LLM 返回（去除 markdown 代码块标记 ` ```json `）
   e. 解析 JSON，保存为 `CompetitorKnowledge` 实体
4. 单个竞品抽取失败不阻断其他竞品
5. 输出 JSON 包含每个竞品的抽取状态和字段数量

**抽取的 Schema 字段：**
- `competitorName` — 竞品名称
- `officialUrl` — 官网 URL
- `summary` — 产品简介
- `positioning` — 市场定位
- `targetUsers` — 目标用户（JSON 数组）
- `coreFeatures` — 核心功能（JSON 数组，每项含 name/description/evidenceIds）
- `pricing` — 定价信息（JSON 对象，含 model/plans/evidenceIds）
- `strengths` — 优势（JSON 数组，每项含 point/evidenceIds）
- `weaknesses` — 劣势（JSON 数组，每项含 point/evidenceIds）
- `sources` — 信息来源（JSON 数组，含 id/title/url/contentSnippet/collectedAt）

### 6.3 分析 Agent — `CompetitorAnalysisAgent`

| 属性 | 值 |
|------|-----|
| 类路径 | `agent/analyzer/CompetitorAnalysisAgent.java` |
| AgentType | `ANALYZER` |
| 中文名 | 分析Agent |
| 上游依赖 | `extract_schema` |
| 输出给 | ReportWriterAgent |

**职责：**
加载所有竞品的 `CompetitorKnowledge` 记录，调用 LLM 进行横向对比分析，输出结构化分析结果。

**实现细节：**

1. 从 `competitor_knowledge` 表读取当前任务的所有竞品知识
2. 将每个竞品知识序列化为 Prompt payload（含 summary、positioning、targetUsers、coreFeatures、pricing、strengths、weaknesses、sources）
3. 渲染 Prompt 传入 `"analyzer"` 模板（含 subjectProduct、analysisDimensions、competitorData）
4. 调用 `llmClient.chatForJson(systemPrompt, prompt, "Analysis")`
5. 校验返回为合法 JSON
6. 输出写入 `context.putSharedOutput("analyze_competitors", analysisJson)`

**分析结果结构（期望的 LLM 输出）：**
```json
{
  "overview": "整体概述",
  "featureComparison": "功能对比...",
  "positioningComparison": "定位对比...",
  "pricingComparison": "定价对比...",
  "targetUserComparison": "目标用户对比...",
  "strengthsSummary": "优势汇总",
  "weaknessesSummary": "劣势汇总",
  "opportunities": ["机会点1", "机会点2"],
  "risks": ["风险点1", "风险点2"],
  "recommendations": ["建议1", "建议2"]
}
```

### 6.4 撰写 Agent — `ReportWriterAgent`

| 属性 | 值 |
|------|-----|
| 类路径 | `agent/writer/ReportWriterAgent.java` |
| AgentType | `WRITER` |
| 中文名 | 撰写Agent |
| 上游依赖 | `analyze_competitors` |
| 输出给 | QualityReviewAgent |

**职责：**
基于 Analyzer 的分析结果和 Extractor 的证据列表，生成完整的 Markdown 格式竞品分析报告并持久化到 `report` 表。

**实现细节：**

1. 从 `context.getSharedOutput("analyze_competitors")` 获取分析结果
2. 从 `evidence_source` 表读取证据列表，格式化为 `"[E001] 标题 (URL)"` 列表
3. 渲染 Prompt 传入 `"writer"` 模板（含 taskName、subjectProduct、analysisResult、evidenceList）
4. 调用 `llmClient.chat(systemPrompt, prompt)` 生成 Markdown 文本（非 JSON，自由文本）
5. 保存到 `report` 表（taskId 唯一，存在则更新）：
   - `title` = 任务名 + " — 竞品分析报告"
   - `content` = 生成的 Markdown 全文
   - `evidenceCount` = 证据总数
   - `summary` = 报告前 300 字（自动截取）
6. 输出为 Markdown 全文，报告包含 9 大章节

### 6.5 质检 Agent — `QualityReviewAgent`

| 属性 | 值 |
|------|-----|
| 类路径 | `agent/reviewer/QualityReviewAgent.java` |
| AgentType | `REVIEWER` |
| 中文名 | 质检Agent |
| 上游依赖 | `write_report` |
| 输出给 | 终端用户（DAG 终点） |

**职责：**
检查 Writer 生成的报告质量，输出质检评分和问题列表，写回 Report 表。

**实现细节：**

1. 从 `report` 表读取当前任务的报告
2. 从 `evidence_source` 表读取证据列表
3. 渲染 Prompt 传入 `"reviewer"` 模板（含 reportContent、evidenceList）
4. 调用 `llmClient.chatForJson(systemPrompt, prompt, "QualityReview")`
5. 解析质检结果，提取 score、passed、issues
6. **写回 Report 表**：更新 `quality_score`、`quality_passed`、`quality_issues` 三个字段
7. 输出 JSON 含 score、passed、issues、summary

**质检四个维度（Prompt 中定义）：**
- 结构完整性（30 分）— 章节是否齐全
- 证据引用充分性（30 分）— 结论是否有证据支撑
- 逻辑一致性（20 分）— 分析推断是否自洽
- 客观性（20 分）— 是否存在无依据的主观臆断

**V1 限制：** 质检只输出评分和问题列表，不自动触发重新撰写或重新抽取（无闭环）。

---

## 7. DAG 工作流引擎

### 7.1 WorkflowFactory (`WorkflowFactory.java`)

负责为每个任务创建固定的 5 节点 DAG。创建时机：任务创建时（`AnalysisTaskService.createTask()` 内调用）。

**五节点定义：**

| order | nodeName | displayName | agentType | dependsOn | required |
|-------|----------|-------------|-----------|-----------|----------|
| 0 | `collect_sources` | 采集公开信息 | COLLECTOR | `null` | true |
| 1 | `extract_schema` | 抽取竞品知识 Schema | EXTRACTOR | `["collect_sources"]` | true |
| 2 | `analyze_competitors` | 竞品横向对比分析 | ANALYZER | `["extract_schema"]` | true |
| 3 | `write_report` | 撰写竞品分析报告 | WRITER | `["analyze_competitors"]` | true |
| 4 | `quality_check` | 质检审查报告 | REVIEWER | `["write_report"]` | true |

所有节点初始状态：`PENDING`，`retryable=false`, `maxRetries=0`（V3 迁移加了字段但 V1 执行器未使用）。

### 7.2 DagExecutor (`DagExecutor.java`)

**核心执行逻辑：**

```
execute(taskId, context):
  1. markTaskRunning(taskId)                          — 标记任务 RUNNING
  2. nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)
  3. hasFailedRequiredNode = false
  4. for each node:
       a. if hasFailedRequiredNode && node.required  — 上游必选节点失败
            → skipNode(node, "上游必选节点执行失败")
            continue
       b. if !dependenciesSatisfied(node)             — 检查所有依赖是否 SUCCESS
            → skipNode(node, "依赖节点未全部成功")
            if node.required → hasFailedRequiredNode = true
            continue
       c. agent = agentRegistry.get(node.agentType)  — 从注册表找 Agent
          if agent == null → failNode, continue
       d. markNodeRunning(node)                      — 标记 RUNNING + 设置 inputData
       e. result = agent.execute(context)            — 执行 Agent
       f. if result SUCCESS:
            node.status = SUCCESS
            context.putSharedOutput(nodeName, result.outputData)
          else:
            node.status = FAILED
            if node.required → hasFailedRequiredNode = true
       g. nodeRepository.save(node)
  5. updateTaskFinalStatus(taskId)                   — 汇总节点状态 → 任务状态
```

**Agent 注册机制：**
`DagExecutor` 通过 Spring 注入 `List<Agent>`，在构造函数中构建 `EnumMap<AgentType, Agent>` 注册表。如果有重复的 AgentType 实现，启动时抛 `IllegalStateException`。

**依赖检查 (`dependenciesSatisfied`)：**
解析节点的 `dependsOn` 字段（JSON 数组格式如 `["collect_sources"]`），在已执行节点列表中查找依赖节点状态是否为 SUCCESS。解析失败则返回 false。

**最终任务状态判定 (`updateTaskFinalStatus`)：**
- 有必选节点 FAILED 或 SKIPPED → 任务标记 FAILED
- 所有必选节点 SUCCESS → 任务标记 SUCCESS

**JSON 转义：** `escapeJson()` 方法处理反斜杠、双引号、换行符和回车符，防止拼接 JSON 时格式错误。

### 7.3 AnalysisTaskRunner (`AnalysisTaskRunner.java`)

异步执行入口，使用 `@Async` 注解：

```java
@Async
public void runTask(Long taskId) {
    AnalysisTask task = taskRepository.findById(taskId).orElseThrow(...);
    dagExecutor.execute(taskId, buildContext(task));
}
```

`buildContext()` 将 `AnalysisTask` 实体字段映射到 `AgentContext`。

**异步触发机制：** `AnalysisTaskService.executeTask()` 使用 `TransactionSynchronizationManager.registerSynchronization()` 注册 `afterCommit` 回调，确保数据库事务提交后再启动异步任务，避免异步线程读到未提交的数据。

---

## 8. LLM 集成层

### 8.1 LlmClient 接口 (`LlmClient.java`)

```java
public interface LlmClient {
    String chat(String systemPrompt, String userPrompt);                        // 自由文本
    String chatForJson(String systemPrompt, String userPrompt, String schema);  // JSON 强制
    String getModelName();           // 返回当前模型名
    TokenUsage getLastTokenUsage();  // 返回最近一次 Token 用量
}
```

### 8.2 两种实现切换

通过 `application.yml` 中的 `llm.mock` 属性 + `@ConditionalOnProperty` 实现：

| 配置值 | 实现类 | 行为 |
|--------|--------|------|
| `llm.mock=true`（默认） | `MockLlmClient` | 返回预置 JSON，200-300ms 模拟延迟 |
| `llm.mock=false` | `OpenAiCompatibleClient` | 调用真实的 OpenAI-compatible API |

### 8.3 MockLlmClient (`MockLlmClient.java`)

- `chat()` — 返回固定中文提示文本，200ms 模拟延迟
- `chatForJson()` — 根据 `responseSchema` 参数返回不同模拟 JSON：
  - 含 `"ExtractedSchema"` → 返回竞品 Schema JSON（含 summary、features、pricing、strengths 等）
  - 含 `"Analysis"` → 返回分析结果 JSON（含 overview、featureComparison、opportunities 等）
  - 含 `"QualityReview"` → 返回质检结果 JSON（score:85, passed:true）
- Token 用量固定返回 input=100, output=200, total=300

### 8.4 OpenAiCompatibleClient (`OpenAiCompatibleClient.java`)

使用 LangChain4j 的 `OpenAiChatModel` 构建：

```java
OpenAiChatModel.builder()
    .baseUrl(baseUrl)         // 从 AiProviderProperties 读取
    .apiKey(apiKey)
    .modelName(modelName)
    .maxTokens(maxTokens)     // 默认 4096
    .temperature(temperature) // 默认 0.3
    .timeout(Duration.ofSeconds(120))
    .logRequests(true)
    .logResponses(true)
    .build();
```

**JSON 模式实现方式：** `chatForJson()` 通过在 system prompt 末尾追加 `"【重要】请只输出 JSON，不要包含 markdown 代码块标记或其他解释文字。期望的 JSON 结构: ..."` 来约束 LLM 输出 JSON，并非使用 function calling / structured output API。

**Token 用量：** V1 中 `OpenAiCompatibleClient.chat()` 方法 token 用量固定返回 0（LangChain4j 的 `generate()` 返回的 `Response` 对象未做 token 提取），仅 Mock 客户端返回有意义的 token 计数。

### 8.5 PromptTemplateService (`PromptTemplateService.java`)

Prompt 模板引擎，支持从 classpath 加载模板文件或使用硬编码默认模板。

**模板格式：** 使用 `{variableName}` 占位符，例如：
```
请对竞品 {competitorName} 进行信息抽取。
以下是采集到的内容：
{collectedContent}
```

**`render(templateName, variables)` 方法**：
1. 尝试从 `resources/prompts/{templateName}.txt` 加载模板文件
2. 文件不存在时使用硬编码默认模板（四个模板：extractor、analyzer、writer、reviewer）
3. 遍历 variables Map，替换所有 `{key}` 占位符
4. 返回渲染后的完整 Prompt

**V1 四个 Prompt 模板用途：**

| 模板名 | 使用者 | 输入变量 |
|--------|--------|---------|
| `extractor` | SchemaExtractorAgent | competitorName, collectedContent |
| `analyzer` | CompetitorAnalysisAgent | subjectProduct, analysisDimensions, competitorData |
| `writer` | ReportWriterAgent | taskName, subjectProduct, analysisResult, evidenceList |
| `reviewer` | QualityReviewAgent | reportContent, evidenceList |

---

## 9. 信息采集层

### 9.1 SourceCollector 接口 (`SourceCollector.java`)

```java
public interface SourceCollector {
    CollectedPage collect(String url, String competitorName, String sourceType);
    List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType);
}
```

**CollectedPage 字段：** url, title, content (清洗后正文), snippet (前500字), metadata (JSON), competitorName, sourceType, collectedAt, success, errorMessage

### 9.2 两种实现切换

通过 `application.yml` 中的 `collector.mock` 属性：

| 配置值 | 实现类 | 行为 |
|--------|--------|------|
| `collector.mock=true`（默认） | `MockSourceCollector` | 返回预置中文模拟内容，100ms 延迟 |
| `collector.mock=false` | `PlaywrightPageCollector` | HTTP 优先，不足时 Playwright 回退 |

### 9.3 MockSourceCollector (`MockSourceCollector.java`)

返回包含中文模拟内容的页面：
- 产品概述段落
- 核心功能列表（功能 A/B/C）
- 定价信息（免费版/专业版 $12/月/企业版 $49/月）
- 用户评价段落

`collectBatch()` 限制最多 5 个 URL。

### 9.4 PlaywrightPageCollector (`PlaywrightPageCollector.java`)

**双模式抓取策略：**

```
collect(url):
  1. httpPage = collectByHttp(url)      // 轻量 HTTP GET
  2. if httpPage.success && content.length >= 300:
       return httpPage                  // HTTP 满足需求
  3. return collectByBrowser(url)       // 回退到 Playwright 渲染
```

**HTTP 采集 (`collectByHttp`)：**
- 使用 Java 11 `HttpClient`，连接超时 10s，请求超时 15s
- User-Agent: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36`
- 自动跟随重定向
- 非 2xx 状态码 → 标记失败
- 成功后用正则提取 `<title>`，HTML→文本清洗

**浏览器采集 (`collectByBrowser`)：**
- 使用 Playwright Chromium（headless）
- `networkidle` 等待策略，30s 超时
- 正文提取优先级：
  1. `document.querySelector('article')` → `innerText`
  2. `document.querySelector('main')` → `innerText`
  3. `document.body.innerText`
- 若 JS 提取失败，回退到 HTML→文本清洗

**HTML→文本清洗 (`htmlToText`)：**
1. 移除 `<script>`、`<style>`、`<noscript>` 标签及内容
2. `<br>` → `\n`
3. `</p>`, `</div>`, `</section>`, `</article>`, `</li>`, `</h[1-6]>` → `\n`
4. 移除所有剩余 HTML 标签
5. 解码常见 HTML 实体（`&nbsp;`, `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`）

**正文后处理 (`cleanContent`)：**
1. 统一换行为 `\n`
2. 合并多个空白字符为单个空格
3. 压缩连续 3+ 个换行为 2 个换行
4. 移除控制字符（`\x00-\x08`, `\x0E-\x1F`）

**批量采集：** `collectBatch()` 限制最多 5 个 URL，逐个调用 `collect()`。

---

## 10. 数据库设计

### 10.1 数据库选型

- **主数据库：** PostgreSQL 16+
- **ORM：** Spring Data JPA
- **迁移工具：** Flyway
- **JPA DDL 策略：** `validate`（不对数据库做任何修改，仅校验实体与表映射）

### 10.2 核心表结构

#### 表 1: `analysis_task` — 分析任务

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `task_name` | VARCHAR(200) | 任务名称 |
| `subject_product` | VARCHAR(200) | 本方产品 |
| `competitor_names` | TEXT | 竞品名称 JSON 数组 |
| `competitor_urls` | TEXT | 竞品 URL JSON 数组 |
| `analysis_dimensions` | TEXT | 分析维度 JSON 数组 |
| `source_scope` | TEXT | 信息源范围 JSON 数组 |
| `report_language` | VARCHAR(20) | 报告语言，默认 '中文' |
| `report_template` | VARCHAR(50) | 报告模板，默认 '标准版' |
| `status` | VARCHAR(20) | PENDING / RUNNING / SUCCESS / FAILED |
| `schema_id` | BIGINT | 引用的分析模板 ID |
| `error_message` | TEXT | 失败原因 |
| `created_at` / `updated_at` | TIMESTAMP | 创建/更新时间 |
| `started_at` / `completed_at` | TIMESTAMP | 开始/完成时间 |

索引：`idx_task_status`, `idx_task_created_at`

#### 表 2: `task_node` — DAG 节点

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `task_id` | BIGINT FK | 所属任务 |
| `node_name` | VARCHAR(50) | 节点标识名（如 `collect_sources`） |
| `display_name` | VARCHAR(100) | 节点显示名（如"采集公开信息"） |
| `agent_type` | VARCHAR(30) | Agent 类型（COLLECTOR/EXTRACTOR/...） |
| `depends_on` | TEXT | 依赖节点 JSON 数组 |
| `required` | BOOLEAN | 是否为必选节点（默认 true） |
| `status` | VARCHAR(20) | PENDING / RUNNING / SUCCESS / FAILED / SKIPPED |
| `input_data` | TEXT | 节点输入 (JSON) |
| `output_data` | TEXT | 节点输出 (JSON) |
| `error_message` | TEXT | 错误信息 |
| `execution_order` | INT | 执行顺序 |
| `started_at` / `completed_at` | TIMESTAMP | 开始/完成时间 |
| `created_at` | TIMESTAMP | 创建时间 |

索引：`idx_node_task_id`, `idx_node_status`

#### 表 3: `agent_execution_log` — Agent 执行日志

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `task_id` | BIGINT FK | 所属任务 |
| `node_id` | BIGINT | 所属节点 |
| `agent_type` | VARCHAR(30) | Agent 类型 |
| `agent_name` | VARCHAR(100) | Agent 实例名称 |
| `input_data` | TEXT | 输入数据 (JSON) |
| `output_data` | TEXT | 输出数据 (JSON) |
| `status` | VARCHAR(20) | 执行状态 |
| `model_name` | VARCHAR(100) | LLM 模型名称 |
| `prompt_used` | TEXT | 使用的 Prompt |
| `duration_ms` | BIGINT | 执行耗时（毫秒） |
| `token_usage` | TEXT | Token 用量 (JSON) |
| `error_message` | TEXT | 错误信息 |
| `trace_id` | VARCHAR(50) | 追踪 ID |
| `reasoning_summary` | TEXT | 推理过程摘要 |
| `needs_human_intervention` | BOOLEAN | 是否需要人工介入 |
| `created_at` | TIMESTAMP | 创建时间 |

索引：`idx_log_task_id`, `idx_log_agent_type`, `idx_log_trace_id`, `idx_log_created_at`

#### 表 4: `evidence_source` — 证据来源

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `task_id` | BIGINT FK | 所属任务 |
| `competitor_name` | VARCHAR(100) | 所属竞品名称 |
| `evidence_id` | VARCHAR(20) | 任务内唯一编号（E001, E002...）|
| `title` | VARCHAR(500) | 来源标题 |
| `url` | VARCHAR(2048) | 来源 URL |
| `content_snippet` | TEXT | 原文引用片段 |
| `full_content` | TEXT | 完整采集内容 |
| `page_metadata` | TEXT | 页面元数据 (JSON) |
| `collected_at` / `created_at` | TIMESTAMP | 采集/创建时间 |

索引：`idx_evidence_task_id`, `idx_evidence_competitor`, `idx_evidence_evidence_id`

#### 表 5: `competitor_knowledge` — 竞品知识

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `task_id` | BIGINT FK | 所属任务 |
| `competitor_name` | VARCHAR(100) | 竞品名称 |
| `official_url` | VARCHAR(2048) | 官网 URL |
| `summary` | TEXT | 产品简介 |
| `positioning` | VARCHAR(500) | 市场定位 |
| `target_users` | TEXT | 目标用户 (JSON) |
| `core_features` | TEXT | 核心功能 (JSON) |
| `pricing` | TEXT | 定价信息 (JSON) |
| `strengths` | TEXT | 优势 (JSON) |
| `weaknesses` | TEXT | 劣势 (JSON) |
| `sources` | TEXT | 信息来源 (JSON) |
| `extracted_at` | TIMESTAMP | 抽取时间 |
| `created_at` / `updated_at` | TIMESTAMP | 创建/更新时间 |

索引：`idx_knowledge_task_id`, `idx_knowledge_competitor`

#### 表 6: `report` — 分析报告

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `task_id` | BIGINT FK UNIQUE | 所属任务（一对一） |
| `title` | VARCHAR(300) | 报告标题 |
| `content` | TEXT | Markdown 格式正文 |
| `summary` | TEXT | 报告摘要 |
| `quality_score` | INT | 质检评分 (0-100) |
| `quality_passed` | BOOLEAN | 质检是否通过 |
| `quality_issues` | TEXT | 质检问题列表 (JSON) |
| `evidence_count` | INT | 证据引用数量 |
| `created_at` / `updated_at` | TIMESTAMP | 创建/更新时间 |

索引：`idx_report_task_id`

#### 表 7: `analysis_schema` — 分析模板

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 主键 |
| `name` | VARCHAR(100) UNIQUE | 模板名称 |
| `description` | VARCHAR(500) | 模板描述 |
| `dimensions` | TEXT | 维度定义 (JSON) |
| `is_preset` | BOOLEAN | 是否为预设模板 |
| `created_at` / `updated_at` | TIMESTAMP | 创建/更新时间 |

索引：`idx_schema_name`

### 10.3 Flyway 迁移

| 版本 | 文件 | 内容 |
|------|------|------|
| `V1` | `V1__init_schema.sql` | 创建 7 张核心表 + 全部索引 |
| `V2` | `V2__seed_preset_schemas.sql` | 插入 3 个预设分析模板：功能对比分析、定价策略分析、SWOT 分析 |
| `V3` | `V3__add_retry_columns.sql` | 为 `task_node` 添加 `retryable` 和 `max_retries` 列（V1 未使用） |

---

## 11. Agent 间数据契约

V1 定义了强类型数据契约对象（`workflow/contract/` 包），用于 Agent 间的数据传递，避免松散 JSON 字符串的直接传递。

| 契约类 | 传递方向 | 核心字段 |
|--------|---------|---------|
| `CollectResult` | Collector → Extractor | totalCollected, results |
| `CollectedDocument` | 含于 CollectResult | competitor, url, evidenceId, title, cleanedText, snippet |
| `ExtractResult` | Extractor → Analyst | totalCompetitors, [CompetitorKnowledgeDraft, ...] |
| `CompetitorKnowledgeDraft` | 含于 ExtractResult | 所有 Schema 字段 |
| `FeatureItem` | 含于 Knowledge | name, description, evidenceIds |
| `PricingItem` | 含于 Knowledge | model, plans, evidenceIds |
| `StrengthWeaknessItem` | 含于 Knowledge | point, evidenceIds |
| `AnalysisResult` | Analyst → Writer | overview, featureComparison, positioningComparison, pricingComparison, opportunities, risks, recommendations |
| `QualityCheckResult` | Reviewer → 用户 | score, passed, issues |
| `QualityIssue` | 含于 QC Result | type, section, severity, suggestion |

**契约设计原则：**
- 每个契约对象带 `contractVersion` 字段
- 每个字段尽量绑定至少一个证据引用（evidenceId）
- 执行器在运行前不校验数据类型（V1 未实现运行时校验，仅作为代码约定）

**实际运行时：** V1 的 Agent 间数据传递通过两种方式：
1. 通过 `AgentContext.sharedState`（内存 Map）传递 JSON 字符串 — 用于 Analyst → Writer
2. 通过数据库读取 — 用于 Collector → Extractor（读 evidence_source 表）、Extractor → Analyst（读 competitor_knowledge 表）

---

## 12. REST API 设计

### 12.1 统一响应格式

所有 API 响应均使用 `ApiResponse<T>` 包装：
```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... },
  "timestamp": "2026-05-26 10:30:00",
  "traceId": "a1b2c3d4"
}
```

### 12.2 任务管理 API (`/api/task/**`)

| 方法 | 路径 | 说明 | 关键实现 |
|------|------|------|---------|
| POST | `/create` | 创建分析任务 | 保存 `AnalysisTask` 实体 + `WorkflowFactory.createWorkflow()` 生成 5 个 `TaskNode` |
| GET | `/list?status=` | 任务列表（可选状态筛选） | 按创建时间倒序，仅前端分页（返回全量） |
| GET | `/{id}` | 任务详情 | 含任务信息 + 节点完成进度（completedNodes/totalNodes） |
| GET | `/{id}/nodes` | 任务 DAG 节点列表 | 按 execution_order 升序，inputData/outputData 截断 240 字 |
| POST | `/{id}/execute` | 启动任务执行 | 校验状态 → 重置 + 清理旧数据 → `afterCommit` 提交后异步调用 `AnalysisTaskRunner.runTask()` |
| POST | `/{id}/retry` | 重置失败任务 | 仅 FAILED 状态可重试，清除关联数据并重置节点 |
| DELETE | `/{id}` | 删除任务 | 先删关联数据（report/knowledge/evidence/log），再删节点，最后删任务。RUNNING 状态不可删 |

### 12.3 报告 API (`/api/report/**`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{taskId}` | 获取报告全文 + 证据列表 + 竞品知识 + 质检结果 |
| GET | `/{taskId}/export` | 下载 Markdown 文件（Content-Disposition: attachment） |

### 12.4 Schema 管理 API (`/api/schema/**`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 列出所有分析模板 |
| GET | `/{id}` | 模板详情 |
| POST | `/create` | 创建自定义模板 |
| PUT | `/{id}` | 更新模板 |
| DELETE | `/{id}` | 删除模板（预设模板不可删） |

### 12.5 Agent 日志 API (`/api/agent-log/**`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/task/{taskId}` | 获取任务的所有 Agent 执行日志 |
| GET | `/task/{taskId}/agent/{agentType}` | 按 Agent 类型筛选日志 |
| GET | `/{logId}` | 单条日志详情 |

### 12.6 全局异常处理

`GlobalExceptionHandler` 统一处理：
- `BusinessException` → 业务异常，返回对应 `ResultCode`
- `MethodArgumentNotValidException` → 参数校验失败
- `Exception` → 兜底处理，返回 INTERNAL_ERROR

### 12.7 请求追踪

`TraceFilter` 实现 `javax.servlet.Filter`：
- 每个 HTTP 请求自动生成 UUID 作为 traceId
- 通过 `TraceIdHolder`（ThreadLocal）存储
- 通过 SLF4J MDC 注入日志上下文
- 响应头返回 traceId

---

## 13. 前端实现

### 13.1 工程化配置

- **构建工具：** Vite 5，开发端口 3000，proxy `/api` → `localhost:9093`
- **语言：** TypeScript 5，ES2020 target，bundler module resolution
- **依赖：** React 18, Ant Design 5, react-router-dom 6, Axios, react-markdown, dayjs

### 13.2 页面与组件

#### 页面 1: TaskListPage（任务列表页）

- 功能：展示最近 10 条任务，Ant Design Table + 状态筛选
- 状态标签：PENDING(灰)、RUNNING(蓝, 动画)、SUCCESS(绿)、FAILED(红)
- 进度条：completedNodes / totalNodes
- 操作按钮：查看详情、查看报告、删除
- 自动刷新：检测到 RUNNING 状态任务时每 3 秒轮询
- 顶部汇总卡片：总数、运行中、成功、失败

#### 页面 2: TaskCreatePage（新建任务页）

- 表单字段：任务名称、本方产品、竞品列表（动态添加/删除，含名称和 URL）、分析模板选择、分析维度多选、信息源范围、报告语言、模板类型
- DAG 步骤预览：展示即将生成的 5 个 DAG 步骤（Ant Design Steps）
- 预填示例数据

#### 页面 3: TaskDetailPage（任务详情页）

- 任务概览：名称、产品、竞品列表、分析维度、进度
- DAG 可视化：Ant Design Steps 组件展示 5 个节点，显示状态和耗时，点击节点展示输入输出 JSON
- Agent 日志面板：内嵌 `AgentLogPanel` 组件
- 操作按钮：执行任务、重试（失败时）
- 自动刷新：RUNNING 状态每 3 秒轮询
- 中间数据查看：结构化竞品知识区域

#### 页面 4: ReportPage（报告预览页）

- 质检结果卡片：评分、是否通过、问题列表（可折叠）
- 报告正文：`MarkdownReport` 组件渲染
- 证据引用：`[证据: E001]` 转为可点击链接，tooltip 展示标题/URL/摘要
- 导出下载：下载 Markdown 文件
- 证据列表弹窗：完整证据表格

### 13.3 核心组件

#### AgentLogPanel

- Ant Design Table，列：Agent 类型、状态、模型、耗时、traceId、创建时间
- 按 Agent 类型筛选（Select 下拉）
- 点击行展开 Modal，显示完整输入、Prompt、输出（代码块格式）

#### EvidenceList

- List 组件，每项显示：证据编号标签(E001)、标题（可点击跳转）、URL、内容摘要、采集时间

#### MarkdownReport

- 使用 `react-markdown` 渲染 Markdown 内容
- 自定义插件将 `[证据: E001]` 匹配为证据链接
- 点击证据链接弹出 Tooltip 显示证据详情

### 13.4 API 客户端

`api/client.ts` — Axios 实例，配置 `baseURL: '/api'`, timeout: 30000ms。通过 Vite proxy 转发到后端 9093 端口。

---

## 14. 任务执行全流程

### 14.1 完整数据流

```
用户创建任务
  │
  ▼
POST /api/task/create
  │ AnalysisTaskService.createTask()
  ├─ 保存 AnalysisTask 实体 (status=PENDING)
  ├─ WorkflowFactory.createWorkflow(taskId)
  │    └─ 保存 5 个 TaskNode (status=PENDING)
  └─ 返回 TaskResponse (taskId + 节点列表)
  
用户点击执行
  │
  ▼
POST /api/task/{id}/execute
  │ AnalysisTaskService.executeTask()
  ├─ 校验状态 (RUNNING 不可重复执行)
  ├─ 如果是 FAILED/SUCCESS → resetTaskForExecution()
  │    ├─ 清除 report, knowledge, evidence, log
  │    └─ 重置所有 node → PENDING
  ├─ taskRepository.save(PENDING)
  └─ afterCommit → AnalysisTaskRunner.runTask(taskId) [@Async]
       │
       ▼
     DagExecutor.execute(taskId, context)
       │
       ├─ [1] markTaskRunning → status=RUNNING
       │
       ├─ [2] 按 executionOrder 遍历 5 个 node
       │    │
       │    ├─ Node: collect_sources (COLLECTOR)
       │    │   ├─ CollectorAgent.doExecute()
       │    │   │   ├─ 解析 competitorNames / competitorUrls
       │    │   │   ├─ 遍历 URL，调用 SourceCollector.collect()
       │    │   │   ├─ 保存 EvidenceSource 实体 ×N
       │    │   │   └─ 输出 collection JSON
       │    │   └─ context.putSharedOutput("collect_sources", json)
       │    │
       │    ├─ Node: extract_schema (EXTRACTOR)
       │    │   ├─ SchemaExtractorAgent.doExecute()
       │    │   │   ├─ 读取 evidence_source 表
       │    │   │   ├─ 按竞品分组
       │    │   │   ├─ 对每个竞品: 拼接内容 → 渲染 Prompt → llmClient.chatForJson()
       │    │   │   ├─ 解析 JSON → 保存 CompetitorKnowledge 实体 ×N
       │    │   │   └─ 输出 extraction JSON
       │    │   └─ context.putSharedOutput("extract_schema", json)
       │    │
       │    ├─ Node: analyze_competitors (ANALYZER)
       │    │   ├─ CompetitorAnalysisAgent.doExecute()
       │    │   │   ├─ 读取 competitor_knowledge 表
       │    │   │   ├─ 序列化竞品知识 → 渲染 Prompt → llmClient.chatForJson()
       │    │   │   ├─ 校验 JSON
       │    │   │   └─ 输出 analysis JSON
       │    │   └─ context.putSharedOutput("analyze_competitors", json)
       │    │
       │    ├─ Node: write_report (WRITER)
       │    │   ├─ ReportWriterAgent.doExecute()
       │    │   │   ├─ context.getSharedOutput("analyze_competitors")
       │    │   │   ├─ 读取 evidence_source 表
       │    │   │   ├─ 渲染 Prompt → llmClient.chat()
       │    │   │   ├─ 保存 Report 实体 (taskId 唯一)
       │    │   │   └─ 输出 Markdown 全文
       │    │   └─ context.putSharedOutput("write_report", markdown)
       │    │
       │    └─ Node: quality_check (REVIEWER)
       │         ├─ QualityReviewAgent.doExecute()
       │         │   ├─ 读取 report 表
       │         │   ├─ 读取 evidence_source 表
       │         │   ├─ 渲染 Prompt → llmClient.chatForJson()
       │         │   ├─ 解析 score/passed/issues
       │         │   └─ 写回 Report.quality_* 字段
       │         └─ context.putSharedOutput("quality_check", json)
       │
       └─ [3] updateTaskFinalStatus → SUCCESS / FAILED
```

### 14.2 失败处理策略

- **节点级失败：** 如果必选节点（required=true）执行失败 → 所有下游必选节点被 SKIPPED
- **非必选节点失败：** 不影响下游节点（V1 中所有节点都是 required=true，此特性为 V2 预留）
- **任务级失败：** 必选节点失败或 SKIPPED → 任务标记 FAILED
- **节点重试：** V1 通过 API `POST /api/task/{id}/retry` 整任务重置重跑（清空所有中间数据），不支持单节点重试

---

## 15. 配置体系

### 15.1 服务端口

- 后端：`9093`
- 前端 dev：`3000`（Vite），proxy `/api` → `localhost:9093`

### 15.2 数据库

- PostgreSQL `127.0.0.1:5432/ecommerce_agent`
- HikariCP 连接池：最大 10 连接，最小 5 空闲
- JPA `ddl-auto: validate` — 由 Flyway 管理表结构

### 15.3 AI 提供商

活动提供商通过 `ai.active-provider` 指定（默认 `deepseek`）：

```yaml
ai:
  active-provider: deepseek
  model-name: deepseek-v4-pro
  max-tokens: 4096
  temperature: 0.3
  timeout-seconds: 120
```

API Key 通过环境变量注入：`DEEPSEEK_API_KEY`, `BAILIAN_API_KEY`, `SILICONFLOW_API_KEY`

### 15.4 Mock 开关

```yaml
llm:
  mock: false       # false=真实AI, true=Mock LLM

collector:
  mock: true        # true=Mock采集器, false=Playwright真实抓取
```

两个 Mock 开关独立控制，可组合：
- `llm.mock=true + collector.mock=true` — 完全离线演示
- `llm.mock=false + collector.mock=true` — 真实 LLM + 模拟数据（测试 Prompt 效果）
- `llm.mock=true + collector.mock=false` — Mock LLM + 真实抓取（测试采集链路）
- `llm.mock=false + collector.mock=false` — 全真实运行

### 15.5 Playwright

```yaml
playwright:
  browser: chromium
  headless: true
  timeout-millis: 30000
  screenshot-on-collect: false
```

---

## 16. V1 设计决策与限制

### 16.1 关键设计决策

| 决策 | 理由 |
|------|------|
| 固定 5 节点 DAG | MVP 阶段不需要动态规划，固定流程够用且可预测 |
| 串行执行 | 足够简单，后续可扩展为并行 |
| Mock 模式为默认 | 无 API Key 即可演示，降低上手门槛 |
| 双模式采集（HTTP + Playwright） | 大部分页面 HTTP 足够，少数 SPA 需要浏览器渲染 |
| JSON 通过 Prompt 约束而非 function calling | 兼容所有 OpenAI-compatible 提供商 |
| 强类型数据契约 | 降低 Agent 联调返工风险 |
| 节点状态基于 DB 持久化 | 服务重启后可恢复，不丢任务 |
| 异步执行 + afterCommit | 保证任务提交不阻塞 HTTP 响应，且异步线程读到已提交数据 |
| 前后端分离 | 职责清晰，便于分别迭代 |
| PostgreSQL 直接使用 | 不做 H2/PostgreSQL 迁移，第一版就是正式数据库 |

### 16.2 V1 已知限制（V2 待解决）

| 限制 | 影响 | V2 计划 |
|------|------|---------|
| DAG 固定 5 节点 | 无法根据任务类型调整流程 | 动态 DAG 生成 |
| 串行执行 | 多竞品无法并行采集/抽取 | 并行执行 |
| 质检无闭环 | 质检不通过不会自动重写报告 | 质检反馈闭环 |
| 不支持自动发现信息源 | 完全依赖用户提供 URL | 搜索驱动的自动发现 |
| Token 用量统计不精确 | `OpenAiCompatibleClient` 返回 token=0 | 修复 token 提取 |
| 无节点级重试 | 失败只能整任务重跑 | 节点级重试 + 断点续跑 |
| JSON 模式通过 Prompt hack | 不够可靠，偶有非法 JSON | 使用 function calling 或 structured output API |
| 无 PDF 导出 | 仅支持 Markdown | PDF 导出 |
| 前端无分页 | 任务列表全量返回 | 后端分页 |
| Prompt 模板硬编码回退 | 无法热更新 Prompt | 外部化 Prompt 管理 |
| retryable/maxRetries 字段存在但未使用 | 数据库有字段，执行器没读 | 执行器消费这些字段 |

---

## 附录 A: 枚举定义

### AnalysisTaskStatus

```java
PENDING  — 待执行
RUNNING  — 运行中
SUCCESS  — 执行成功
FAILED   — 执行失败
```

### TaskNodeStatus

```java
PENDING  — 待执行
RUNNING  — 运行中
SUCCESS  — 执行成功
FAILED   — 执行失败
SKIPPED  — 已跳过（上游依赖失败）
```

### AgentType

```java
COLLECTOR  — 采集 Agent
EXTRACTOR  — 抽取 Agent
ANALYZER   — 分析 Agent
WRITER     — 撰写 Agent
REVIEWER   — 质检 Agent
```

---

## 附录 B: TokenUsage 类

```java
public class TokenUsage {
    int inputTokens;
    int outputTokens;
    int totalTokens;
    String modelName;

    String toJson(); // 序列化为 {"input":1500,"output":800,"total":2300}
}
```

---

## 附录 C: 通用组件

### ApiResponse (`common/ApiResponse.java`)

统一 API 响应：
```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... },
  "timestamp": 1716700000000,
  "traceId": "uuid-string"
}
```

### ResultCode (`common/ResultCode.java`)

预定义状态码：SUCCESS(200), PARAM_MISSING(400), PARAM_VALUE_INVALID(400), TASK_NOT_FOUND(404), TASK_ALREADY_RUNNING(409), TASK_STATUS_INVALID(409), TASK_DELETE_FAILED(409), INTERNAL_ERROR(500)

### BusinessException (`common/BusinessException.java`)

业务异常，含 `ResultCode` 和 `detail`，由 `GlobalExceptionHandler` 统一捕获处理。

### TraceIdHolder (`common/TraceIdHolder.java`)

ThreadLocal 存储 traceId，通过 `TraceFilter` 在每个请求开始时设置，请求结束时清理。联动 MDC 注入日志。

### TraceFilter (`common/TraceFilter.java`)

实现 `javax.servlet.Filter`，拦截所有请求，生成 UUID 作为 traceId，写入 TraceIdHolder 和 MDC。

### GlobalExceptionHandler (`common/GlobalExceptionHandler.java`)

`@RestControllerAdvice`，统一拦截：
- `BusinessException` → 返回对应 ResultCode
- `MethodArgumentNotValidException` → 参数校验失败 (400)
- `Exception` → 兜底 Internal Server Error (500)

---

## 附录 D: Spring 配置类

| 配置类 | 路径 | 用途 |
|--------|------|------|
| `AiProviderProperties` | `config/AiProviderProperties.java` | 读取 `ai.*` 配置，映射多提供商信息 |
| `AsyncConfig` | `config/AsyncConfig.java` | 启用 `@EnableAsync` + 线程池配置 |
| `Knife4jConfig` | `config/Knife4jConfig.java` | Swagger/OpenAPI 文档配置 |
| `PlaywrightConfig` | `config/PlaywrightConfig.java` | `Browser` Bean（Chromium headless 单例） |
| `WebConfig` | `config/WebConfig.java` | CORS 配置，允许跨域访问 `/api/**` |

---

> **文档版本：** V1.0
> **对应代码版本：** V1（固定 5 节点 DAG，串行执行，Mock + 真实双模式）
> **用途：** V2 升级完成后与此文档对比，记录架构演进轨迹。
