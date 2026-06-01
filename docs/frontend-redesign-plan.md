# 前端界面重构方案

**文档定位：** 本方案旨在应对高级别商业产品评审（如阿里等大厂标准），将现有系统前端界面从"后端开发者的 Debug 面板"全面升级为"业务视角的智能工作台"，重点解决信息过载、层级倒置与缺乏交互闭环等问题。

---

## 总体诊断与重构核心原则

当前界面的通病在于 **"机器思维与底层数据直出"** 。虽然底层 Agent 具备了复杂的 DAG 编排、自动补源与溯源能力，但前端依然在做"JSON 格式化渲染"。

**重构的三大核心原则：**

1. **业务结论先行，技术细节后置**：用户第一诉求是业务进度与结果，原始运行参数必须折叠收纳。
2. **渐进式信息披露 (Progressive Disclosure)**：只在用户需要（如排查异常）时，才展示高级日志与配置。
3. **强化交互闭环 (Actionable)**：数据面板必须具备操作能力，如"人工修改配置并重跑"、"一键触发重写"。

---

## 模块一：创建任务预览页（启动前动态感知）

**涉及文件：**
- `frontend/src/pages/TaskCreatePage.tsx`
- `frontend/src/utils/taskNodeInsights.ts`
- `backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java` (configSummary 拼接逻辑)

### 当前存在的问题

#### 1.1 毫无 DAG 编排感

[TaskCreatePage.tsx:347-496] 右侧面板平铺采集卡片 + 线性 Steps，无法体现多节点并行/串行流转关系。

`previewCollectorCards` 是扁平数组，前端没有按竞品名做 `groupBy`。后端 `previewWorkflow` 返回的节点列表虽有 `executionOrder` 和 `dependsOn`，但前端完全未用于构建拓扑视图。

#### 1.2 参数大杂烩与标签滥用

单个采集卡片包含 10+ 种不同颜色 Tag：`blue`(HYBRID)、`cyan`(可见浏览器补源)、`default`(依赖)、`geekblue`(范围)、`purple`(规划入口)、`green`(候选来源)、`gold`(结果页验证)。所有技术枚举都被映射为带色标签。

`searchQueries` 裸露展示最多 3 个 Query，`sourceCandidates` 预览直接显示 URL。

[TaskCreatePage.tsx:415-424] 搜索 Query 全部展开：

```tsx
{insight.searchQueries.slice(0, 3).map((query) => (
  <Tag key={query}>{query}</Tag>
))}
```

#### 1.3 搜索步骤缺乏动态预期

[TaskCreatePage.tsx:426-437] `searchExecutionPlan.steps` 渲染为静态 `<div>` 列表，如 `"1. 加载规划候选"` `"2. 验证候选页面"`，没有传达出"这是一个 5 步管道即将启动"的动态感。

#### 1.4 采集卡片嵌套过深

`Card > Space > Card > Space > ...`，内外层 Card 视觉区分度不够，难以辨认卡片归属哪个竞品。

#### 1.5 后端 configSummary 信息密度过高

[AnalysisTaskService.java:735-758] 生成的摘要是一行 100+ 字符的拼接字符串，如：

```
"Notion AI · 官网采集 · 搜索模式：混合 · 候选 4 条 · Query 2 条 · 计划 5 步 · 浏览器补源：开启 · 结果页验证：开启"
```

前端原样展示在 `Text type="secondary"` 里，用户难以快速提取关键信息。

### 优化方案

#### 1.1 按竞品分组 + 泳道拓扑

新增 `groupBy` 逻辑，按竞品名聚合采集节点：

```tsx
const previewGroups = useMemo(() => {
  const groups = new Map<string, typeof previewCollectorCards>()
  previewCollectorCards.forEach(item => {
    const name = item.insight.competitorName || '未知竞品'
    if (!groups.has(name)) groups.set(name, [])
    groups.get(name)!.push(item)
  })
  return Array.from(groups.entries())
}, [previewCollectorCards])
```

渲染为树状拓扑视图：

```
┌─ 🏷️ Notion AI ────────────────────────────────────────────┐
│  🌐 官网采集 (5 候选 · 3 Query) ──────┐                   │
│  📄 文档采集 (3 候选 · 2 Query) ──────┤ 并行执行 (无依赖)  │
│  💰 定价采集 (2 候选 · 2 Query) ──────┘                   │
└────────────────────────────────────────────────────────────┘
┌─ 🏷️ 飞书知识库 ──────────────────────────────────────────┐
│  🌐 官网采集 (4 候选 · 3 Query) ───── 串行                │
└────────────────────────────────────────────────────────────┘
                         ↓↓↓
┌─ 汇聚节点 ────────────────────────────────────────────────┐
│  抽取 → 分析 → 撰写 → 初审 → (条件: 修订 → 终审)          │
└───────────────────────────────────────────────────────────┘
```

#### 1.2 提取业务摘要，折叠底层参数

每个采集卡片默认只显示业务可读摘要：

- "预备从 **5 个候选来源** 采集，已生成 **3 组搜索 Query**"
- 搜索模式：`HYBRID` → `混合模式（浏览器优先，HTTP 兜底）`
- `searchQueries`、`sourceCandidates`、`searchExecutionPlan.steps` 全部收进 `<Collapse>` 或 Popover

所有底层枚举值（`HYBRID`、`BROWSER_ONLY`、`HEURISTIC_ONLY`）替换为中文业务描述。

#### 1.3 来源分类图标化

```tsx
const sourceTypeIcon: Record<string, string> = {
  OFFICIAL: '🌐', DOCS: '📄', PRICING: '💰', NEWS: '📰', REVIEW: '⭐'
}
```

卡片标题改为 `{icon} {competitorName} / {sourceTypeLabel}` 格式。

#### 1.4 色彩规范

采集卡片内只保留状态相关颜色（`cyan` = 浏览器补源开启），其余全部改用灰色描边 Tag 或无 Tag 纯文本。

#### 1.5 后端 configSummary 结构化

后端 `buildConfigSummary` 改为返回结构化 JSON，由前端按需渲染。或者至少将当前拼接字符串的"·"分隔改为前端可解析的结构。

---

## 模块二：工作流节点追踪页（执行进度大盘）

**涉及文件：**
- `frontend/src/pages/TaskDetailPage.tsx`
- `frontend/src/utils/taskNodeInsights.ts`
- `frontend/src/utils/display.ts`
- `frontend/src/styles/index.css` (`.work-card` `.workflow-steps` `.live-activity-card`)

### 当前存在的问题

#### 2.1 DAG 进度图在页面底部

[TaskDetailPage.tsx:1158-1199] Steps 组件在 `Col lg={16}` 的"实时执行看板"Card 内部。在大屏上用户需要先跨过左侧任务概览卡才能看到全局进度，在中小屏上 Steps 被推到折叠 Tab 下方。

Steps 只按 `executionOrder` 排序渲染，没有按竞品分组展示并行关系。

#### 2.2 节点追踪 Tab 全展开式卡片

[TaskDetailPage.tsx:1211-1285] 每张卡片默认展示完整信息：`Descriptions`（节点名、智能体类型、依赖、配置摘要、执行结果）+ 内嵌采集进度卡片（搜索范围标签、候选数、选中数、采集成功数、进度条、搜索步骤）。

6 个节点 = 6 张大卡片，全部强制展开，形成视觉噪音。

#### 2.3 顶部任务概览卡是静态配置

[TaskDetailPage.tsx:991-1114] 任务概览卡位于左侧 `Col lg={8}`，展示产品名、竞品列表、分析维度、采集范围——这些都是创建任务时填的参数。对"执行进度大盘"这个定位而言，首屏第一眼看到的应该是 DAG 进度，而非当初的配置表单。

#### 2.4 彩色标签用于非业务属性

`node.allowFailedDependency` 显示为 `<Tag color="gold">允许失败依赖</Tag>`。`retryable: true` 等内部布尔值也曾有彩色标签。

#### 2.5 已具备的能力（无需改动）

以下能力已经在当前代码中实现，不属于问题：

- ✅ **节点级操作：** pause / resume / skip / terminate / rerun / config-rerun 的后端 API [TaskController.java:109-135] + 前端 UI [TaskDetailPage.tsx:1303-1338] 全部到位
- ✅ **PAUSED 状态** 在 `TaskNodeStatus` 枚举中定义，DagExecutor 已支持 PAUSED 节点的跳过逻辑
- ✅ **干预规则提示** `interventionSummary` 已在任务级和节点级展示
- ✅ **检查点复用** `canReuseCheckpoint` 在节点抽屉中展示

### 优化方案

#### 2.1 架构翻转：DAG 进度置顶

将 DAG 进度从右侧面板提至页面最顶部，构建独立的全宽 DAG 进度条/Tree：

```
┌─ 全局 DAG 进度（固定在页面顶部）──────────────────────────────────────┐
│                                                                       │
│  🏷️ Notion AI:  🟢 官网采集  🟢 文档采集  🔵 定价采集                 │
│  🏷️ 飞书:       🟢 官网采集  🔵 文档采集  ⚪ 定价采集                  │
│                         ↓ 全部采集完成 ↓                              │
│               🟢 抽取 → 🟢 分析 → ⚪ 撰写 → ⚪ 初审                    │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
┌─ 任务操作栏 ────────────────────────────────────────────────────────┐
│  [暂停任务] [恢复执行] [查看报告]                       状态: 🔵 运行中 │
└──────────────────────────────────────────────────────────────────────┘
┌─ 异常面板（仅在有错误/警告时显示）────────────────────────────────────┐
│  ⚠️ 初审发现 3 个缺证据章节 → [查看详情]  |  🔴 定价采集节点失败       │
└──────────────────────────────────────────────────────────────────────┘
```

`activeCollectorViews` 的活动卡片网格保留，但放在 DAG 进度下方。任务概览卡缩小为可折叠侧栏或移入 Tab 中。

#### 2.2 节点卡片手风琴化

默认收起所有节点详细信息，只保留一行摘要：

```
┌─ 🟢 抽取竞品知识 (结构抽取智能体) ── 12.3s ── [展开] [查看追踪] ──┐
└──────────────────────────────────────────────────────────────────┘
┌─ 🔴 质量初审 (质量评审智能体) ── 8.1s ── [展开] [查看追踪] ───────┐
│  ⚠️ 未通过 · 65/100 · 3 个问题                     [从此节点重跑] │
└──────────────────────────────────────────────────────────────────┘
```

实现方式：将所有节点卡片的详细信息默认收进 `<Collapse>`，`activeKey` 默认只展开失败节点 (`status === 'FAILED'`)。

当前的内嵌 `Descriptions` + 采集进度卡片在用户点击"展开"后才渲染。

#### 2.3 色彩纪律化

非业务状态的属性统一改为灰色描边/无 Tag：

```tsx
// 之前 (彩色 Tag 用于技术属性)
{node.allowFailedDependency && <Tag color="gold">允许失败依赖</Tag>}

// 改为 (灰色描边，降低视觉权重)
{node.allowFailedDependency && (
  <span style={{ color: '#8c8c8c', fontSize: 12 }}>· 允许失败依赖</span>
)}
```

严格将彩色保留给业务状态：
- 🔴 红色 → 失败、ERROR
- 🟠 橙色 → 警告、WARNING、PAUSED
- 🟢 绿色 → 成功、通过
- 🔵 蓝色 → 运行中
- ⚪ 灰色/默认 → 待执行、技术属性

#### 2.4 任务概览卡折叠

将任务概览卡中的"产品/竞品/维度/范围"等配置信息收入 `<Collapse>`，默认折叠。卡片主体只保留进度条 + 操作按钮 + 异常提示。

---

## 模块三：报告质量初审追踪页（质检与修订闭环）

**涉及文件：**
- `frontend/src/pages/ReportPage.tsx`
- `frontend/src/components/MarkdownReport.tsx`
- `frontend/src/api/client.ts`
- `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/controller/ReportController.java`

### 当前存在的问题

#### 3.1 Hero 区域不够醒目

[ReportPage.tsx:461-492] 质量评分只有一个普通 `Statistic` 卡片，与"证据来源数"和"质量问题数"平级排列。评审结论"通过/未通过"只是一个 Tag。高层评审场景中，评分和结论应该是页面上视觉权重最高的区域。

#### 3.2 修订计划与问题清单空间分离

当前页面卡片顺序：
```
  Header → 统计卡片 → 摘要 → 修订计划 → 初审结果 → 终审结果
  → 章节证据覆盖 → 搜索审计 → 知识溯源 → 质量反馈 → 报告正文
```

`revisionPlan` 卡片和 `initialReview` / `finalReview` 卡片中间隔着 `EvidenceCoverageCard` (章节证据覆盖) 和 `SearchAuditCard` (搜索审计)。用户需要上下滚动才能把"诊断"和"治疗方案"对应起来。

#### 3.3 nextActions 是静态文本，无交互按钮

[ReportPage.tsx:367-388] `ReviewCard` 组件中的 `nextActions` 用 `<List>` 渲染，每个 action 有 `actionType`（SUPPLEMENT_EVIDENCE / RERUN_NODE / REWRITE_CLAIM / MANUAL_REVIEW）但无可点击按钮。用户看到"建议重跑 extract_schema 节点"却需要手动回到 TaskDetailPage 操作。

#### 3.4 竞品知识溯源展示原始 JSON

[ReportPage.tsx:583] `knowledge.evidenceCoverage` 用 `<pre className="code-block">` 直接展示 JSON。对业务人员无意义，应转换为可视化覆盖矩阵。

#### 3.5 报告正文在最底部

[ReportPage.tsx:609-611] MarkdownReport 在所有分析面板之后。对于"查看报告"这个核心目标，正文位置过于靠后。

### 优化方案

#### 3.1 Hero 区域升级

```tsx
// 根据初审/终审状态渲染不同 Hero
function ReviewHero({ report }: { report: ReportInfo }) {
  const passed = report.qualityPassed
  return (
    <div className="review-hero" style={{
      background: passed ? '#f6ffed' : '#fff2f0',
      border: `2px solid ${passed ? '#b7eb8f' : '#ffccc7'}`,
      borderRadius: 12, padding: 32, textAlign: 'center', marginBottom: 24
    }}>
      <div style={{ fontSize: 48, marginBottom: 8 }}>
        {passed ? '✅' : '🔴'}
      </div>
      <div style={{ fontSize: 24, fontWeight: 700, marginBottom: 4 }}>
        {passed ? '质量评审通过' : '初审未通过'}
      </div>
      <div style={{ fontSize: 56, fontWeight: 800, color: passed ? '#389e0d' : '#cf1322' }}>
        {report.qualityScore ?? '—'}<span style={{ fontSize: 24 }}>/100</span>
      </div>
      <div style={{ marginTop: 12, color: '#8c8c8c' }}>
        {report.qualityIssues?.length || 0} 个问题待处理 · {report.evidenceCount} 条证据源
      </div>
      <Space style={{ marginTop: 20 }} size={12}>
        {!passed && report.initialReview && (
          <Button type="primary" size="large" onClick={() => rerunTaskNode(taskId, 'rewrite_report')}>
            应用修订计划并触发 Writer 重写
          </Button>
        )}
        <Button size="large" onClick={() => navigate(`/task/${taskId}`)}>
          前往任务页补充证据
        </Button>
        <Button size="large" icon={<DownloadOutlined />} onClick={() => window.open(getExportUrl(taskId))}>
          导出报告
        </Button>
      </Space>
    </div>
  )
}
```

#### 3.2 质检工作台两栏布局

将当前的卡片排列重构为两栏：

```
┌─ Hero ────────────────────────────────────────────────────────────────┐
│  [评分 + 状态 + 操作按钮]                                              │
└───────────────────────────────────────────────────────────────────────┘

┌─ 左栏: 问题诊断看板 (flex: 1) ────────┐  ┌─ 右栏: 修订计划 (flex: 1) ─┐
│                                       │  │                            │
│  🔴 ERROR (2)                         │  │  修订摘要:                  │
│  ├─ 结论 章节 — 正文缺证据引用        │  │  优先为结论和建议补充证据... │
│  ├─ 建议 章节 — 结论无支撑            │  │                            │
│                                       │  │  改写指引:                  │
│  🟠 WARNING (1)                       │  │  1. 结论: 补 [证据:EID]     │
│  └─ 功能对比 — 声明过于绝对           │  │  2. 建议: 收紧表达          │
│                                       │  │                            │
│  📋 章节证据覆盖概览 (内嵌)           │  │  [▶ 应用计划并触发重写]     │
│                                       │  │                            │
└───────────────────────────────────────┘  └────────────────────────────┘
```

- 左栏：`ReviewCard` 的问题清单 + `EvidenceCoverageCard`（内嵌合并）
- 右栏：`revisionPlan` + `rewriteGuidelines` + 操作按钮

#### 3.3 nextActions 交互化

在 `ReviewCard` 中根据 `actionType` 渲染对应操作按钮：

```tsx
function ActionButton({ action, taskId }: { action: ReviewNextAction; taskId: number }) {
  const navigate = useNavigate()

  switch (action.actionType) {
    case 'RERUN_NODE':
      return (
        <Button type="primary" size="small"
          onClick={async () => {
            await rerunTaskNode(taskId, action.targetNode!)
            message.success(`已触发 ${action.targetNode} 重跑`)
          }}>
          {action.title}
        </Button>
      )
    case 'SUPPLEMENT_EVIDENCE':
      return (
        <Button size="small" onClick={() => navigate(`/task/${taskId}`)}>
          前往补充证据
        </Button>
      )
    case 'REWRITE_CLAIM':
      return (
        <Button type="primary" size="small"
          onClick={async () => {
            await rerunTaskNode(taskId, 'rewrite_report')
            message.success('已触发报告重写')
          }}>
          触发报告重写
        </Button>
      )
    case 'MANUAL_REVIEW':
      return <Button size="small">确认已复核</Button>
    default:
      return null
  }
}
```

#### 3.4 竞品知识可视化替代 JSON

将 `knowledge.evidenceCoverage` 的 `<pre>` JSON 替换为可视化覆盖矩阵：

```tsx
function CoverageMatrix({ knowledge }: { knowledge: CompetitorKnowledgeInfo }) {
  const coverage = knowledge.evidenceCoverage as Record<string, { status: string }>
  const fields = [
    { key: 'summary', label: '产品概览' },
    { key: 'positioning', label: '市场定位' },
    { key: 'targetUsers', label: '目标用户' },
    { key: 'coreFeatures', label: '核心能力' },
    { key: 'pricing', label: '定价策略' },
    { key: 'strengths', label: '优势判断' },
    { key: 'weaknesses', label: '短板与风险' },
  ]

  const statusColor = (status?: string) => {
    if (status === 'TRACEABLE') return '#52c41a'
    if (status === 'MISSING_EVIDENCE') return '#faad14'
    return '#d9d9d9'
  }

  return (
    <Space direction="vertical" size={4}>
      {fields.map(field => (
        <Row key={field.key} align="middle" gutter={8}>
          <Col span={8}><Text>{field.label}</Text></Col>
          <Col span={16}>
            <Tag color={statusColor(coverage[field.key]?.status)}>
              {coverage[field.key]?.status || 'EMPTY'}
            </Tag>
          </Col>
        </Row>
      ))}
    </Space>
  )
}
```

#### 3.5 调整信息层次顺序

```
Hero → 报告正文 (MarkdownReport) → 质检工作台(两栏) → 证据列表(折叠) → 搜索审计(折叠)
```

将 Markdown 报告前移，用户读完报告结论后直接看到质检诊断。证据和审计细节按渐进披露原则折叠到页面下方。

---

## 跨模块公共问题

| # | 问题 | 涉及文件 | 建议 |
|---|---|---|---|
| 1 | 后端 `configSummary` 是拼接字符串，前端无法按需格式化 | [AnalysisTaskService.java:721-782] | 后端返回结构化对象 `{ competitor, sourceType, searchMode, candidateCount, queryCount, stepCount, browserEnabled, verificationEnabled }` |
| 2 | `getCollectorNodeInsight` 在前端重复解析 JSON | [taskNodeInsights.ts] | 在 `TaskNodeResponse` 中增加预解析的 `insight` 对象字段，减少前端解析逻辑 |
| 3 | 全局 CSS 缺乏设计 Token 体系 | [index.css] | 利用 Ant Design 5 的 `ConfigProvider` theme，统一定义状态色、间距、圆角 |
| 4 | 多处重复使用 `<pre className="code-block">` 展示 JSON | TaskDetailPage / ReportPage | 封装 `<DebugJson>` 组件，默认折叠，仅在 `?debug=1` 或用户主动展开时渲染 |

---

## 实施优先级与工作量预估

| 优先级 | 模块 | 核心改动 | 预计工作量 |
|---|---|---|---|
| **P0** | 模块三：报告页 | Hero 区升级 + 两栏质检工作台 + nextActions 按钮化 + 报告正文前置 | 1–1.5 天 |
| **P1** | 模块二：任务详情页 | DAG 进度置顶 + 节点卡片手风琴化 + 色彩规范 + 任务概览折叠 | 1 天 |
| **P2** | 模块一：创建任务页 | 按竞品分组拓扑 + 来源图标化 + 底层参数折叠 + 后台 configSummary 结构化 | 0.5–1 天 |
| **P3** | 跨模块 | DebugJson 组件 + 全局色彩 Token + insight 字段后端化 | 0.5 天 |

P0 的 `nextActions` 交互化对后端无新增依赖（`rerunTaskNode` 接口已就绪），其余均为纯前端工作。

---

## 与后端现有能力的对齐确认

| 前端改动 | 所需后端能力 | 状态 |
|---|---|---|
| 触发重写按钮 | `POST /api/task/{id}/nodes/rewrite_report/rerun` | ✅ 已就绪 |
| 暂停/跳过/终止节点 | `POST /api/task/{id}/nodes/{nodeName}/pause\|skip\|terminate` | ✅ 已就绪 |
| 修改配置后重跑 | `POST /api/task/{id}/nodes/{nodeName}/config-rerun` | ✅ 已就绪 |
| configSummary 结构化 | 需改造 `buildConfigSummary` 返回对象 | 🔧 需小改 |
| insight 字段后端化 | 需在 `TaskNodeResponse` 增加 `insight` 字段 | 🔧 需小改(可选) |
