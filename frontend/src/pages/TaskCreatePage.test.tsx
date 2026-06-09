import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import TaskCreatePage, { buildCreateTaskRequest } from './TaskCreatePage'
import type { TaskNodeInfo } from '../types'

const navigateMock = vi.fn()
const listSchemasMock = vi.fn()
const previewWorkflowMock = vi.fn()
const listKnowledgeDomainsMock = vi.fn()
const listKnowledgeDomainDocumentsMock = vi.fn()

function buildPreviewNode(overrides: Partial<TaskNodeInfo>): TaskNodeInfo {
  return {
    id: 1,
    nodeName: 'collect_sources_01_01',
    displayName: 'Notion AI - 官网采集',
    nodeConfig: '{}',
    configSummary: null,
    configSummaryData: null,
    collectorInsight: null,
    nodeNotes: null,
    allowFailedDependency: false,
    agentType: 'COLLECTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 0,
    status: 'PENDING',
    controlState: 'NONE',
    errorMessage: null,
    interventionReason: null,
    executionOrder: 1,
    inputSummary: null,
    outputSummary: null,
    inputData: null,
    outputData: null,
    startedAt: null,
    completedAt: null,
    ...overrides,
  }
}

function buildPreviewWorkflow(): TaskNodeInfo[] {
  return [
    buildPreviewNode({
      id: 11,
      nodeName: 'collect_sources_01_01',
      displayName: 'Notion AI - 官网采集',
      agentType: 'COLLECTOR',
      configSummaryData: {
        competitorName: 'Notion AI',
        sourceType: 'OFFICIAL',
        sourceTypeLabel: '官网',
        searchMode: 'HYBRID',
        searchModeLabel: '混合',
        candidateCount: 4,
        queryCount: 2,
        browserSearchEnabled: true,
        verificationEnabled: true,
        minVerifiedCandidates: 1,
        sourceScope: ['官网', '产品文档'],
        preferredDomains: ['notion.so'],
        competitorUrls: ['https://www.notion.so/product/ai'],
        discoveryNotes: '会优先从官网入口开始，再补充文档资料。',
      },
      collectorInsight: {
        competitorName: 'Notion AI',
        sourceType: 'OFFICIAL',
        sourceTypeLabel: '官网',
        sourceScope: ['官网', '产品文档'],
        competitorUrls: ['https://www.notion.so/product/ai'],
        searchMode: 'HYBRID',
        searchModeLabel: '混合',
        searchQueries: ['Notion AI official site', 'Notion AI docs'],
        browserSearchEnabled: true,
        verifyResultPage: true,
        minVerifiedCandidates: 1,
        preferredDomains: ['notion.so'],
        candidateCount: 4,
        selectedCount: 0,
        successCollected: 0,
        totalCollected: 0,
        discoveryNotes: '会优先从官网入口开始，再补充文档资料。',
        searchProgress: null,
        searchExecutionPlan: {
          stage: 'COLLECT',
          steps: [
            {
              stepCode: 'PLAN',
              goal: '规划资料入口',
              expectedDurationMs: 1000,
              dependency: '',
            },
          ],
        },
        searchExecutionTrace: null,
        searchProgressSnapshots: [],
        sourceCandidates: [
          {
            url: 'https://www.notion.so/help',
            title: 'Notion Help Center',
            domain: 'notion.so',
          },
        ],
        selectedTargets: [],
      },
    }),
    buildPreviewNode({
      id: 12,
      nodeName: 'extract_schema',
      displayName: '结构化提炼',
      agentType: 'EXTRACTOR',
      executionOrder: 2,
      dependsOn: '["collect_sources_01_01"]',
      configSummaryData: {
        summaryText: '将按产品功能、目标用户进行结构化抽取',
        dimensions: ['产品功能', '目标用户'],
      },
    }),
    buildPreviewNode({
      id: 13,
      nodeName: 'analyze_competitors',
      displayName: '竞品分析',
      agentType: 'ANALYZER',
      executionOrder: 3,
      dependsOn: '["extract_schema"]',
      configSummaryData: {
        summaryText: '汇总 1 个竞品，分析 2 个维度',
        competitorCount: 1,
        dimensionCount: 2,
      },
    }),
    buildPreviewNode({
      id: 14,
      nodeName: 'write_report',
      displayName: '报告撰写',
      agentType: 'WRITER',
      executionOrder: 4,
      dependsOn: '["analyze_competitors"]',
      configSummaryData: {
        summaryText: '输出中文 / 标准版报告',
        mode: 'initial',
        reportLanguage: '中文',
        reportTemplate: '标准版',
      },
    }),
    buildPreviewNode({
      id: 15,
      nodeName: 'quality_review',
      displayName: '质量复核',
      agentType: 'REVIEWER',
      executionOrder: 5,
      dependsOn: '["write_report"]',
      configSummaryData: {
        summaryText: '按标准质量评审复核 write_report',
        qualityPolicy: '标准质量评审',
        sourceNode: 'write_report',
      },
    }),
  ]
}

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

vi.mock('../api/client', () => ({
  createTask: vi.fn(),
  executeTask: vi.fn(),
  listSchemas: (...args: unknown[]) => listSchemasMock(...args),
  previewWorkflow: (...args: unknown[]) => previewWorkflowMock(...args),
  listKnowledgeDomains: (...args: unknown[]) => listKnowledgeDomainsMock(...args),
  listKnowledgeDomainDocuments: (...args: unknown[]) => listKnowledgeDomainDocumentsMock(...args),
  ingestKnowledgeSource: vi.fn(),
}))

describe('TaskCreatePage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    listSchemasMock.mockResolvedValue({ data: [] })
    previewWorkflowMock.mockResolvedValue({ data: [] })
    listKnowledgeDomainsMock.mockResolvedValue({
      data: [
        {
          id: 7,
          domainKey: 'org-product-docs',
          domainName: '组织产品资料',
          description: '沉淀组织产品资料',
          allowedSourceCategories: ['UPLOADED_DOCUMENTS', 'USER_PROVIDED'],
          defaultLifecycle: 'ACTIVE',
          defaultTrustLevel: 'CURATED',
          status: 'ACTIVE',
        },
      ],
    })
    listKnowledgeDomainDocumentsMock.mockResolvedValue({ data: [] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function flushPageEffects() {
    await act(async () => {
      await Promise.resolve()
    })
  }

  it('blocks submission when required fields are missing', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskCreatePage />
      </MemoryRouter>,
    )
    await flushPageEffects()

    await user.click(screen.getByRole('button', { name: /创建并执行/ }))

    expect(await screen.findByText('请输入任务名称')).toBeInTheDocument()
  })

  it('normalizes task request payload before preview and submit', () => {
    expect(
      buildCreateTaskRequest({
        taskName: '  Phase 1 前端闭环  ',
        subjectProduct: '  My Product  ',
        competitors: [
          { name: '  Notion AI  ', url: '  https://www.notion.so  ' },
          { name: '   ', url: 'https://should-be-ignored.test' },
        ],
        analysisDimensions: ['产品功能'],
        sourceScope: ['官网', '产品文档'],
      }),
    ).toEqual({
      taskName: 'Phase 1 前端闭环',
      subjectProduct: 'My Product',
      competitorNames: ['Notion AI'],
      competitorUrls: ['https://www.notion.so'],
      analysisDimensions: ['产品功能'],
      sourceScope: ['官网', '产品文档'],
      reportLanguage: '中文',
      reportTemplate: '标准版',
      schemaId: undefined,
    })
  })

  it('debounces preview requests while the user is typing', async () => {
    vi.useFakeTimers()
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskCreatePage />
      </MemoryRouter>,
    )
    await flushPageEffects()

    await act(async () => {
      fireEvent.change(screen.getByLabelText('分析主题'), { target: { value: 'AI 知识库竞品分析' } })
      fireEvent.change(screen.getByLabelText('本方产品'), { target: { value: '企业级 RAG 平台' } })
      fireEvent.change(screen.getByLabelText('竞品名称'), { target: { value: 'Dify' } })
      fireEvent.change(screen.getByLabelText('分析主题'), { target: { value: 'AI 知识库竞品分析 V2' } })
      await Promise.resolve()
    })

    expect(previewWorkflowMock).not.toHaveBeenCalled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(450)
      await Promise.resolve()
    })

    expect(previewWorkflowMock).toHaveBeenCalled()
  })

  it('organizes the first screen around task goal, source strategy, plan preview, and adjustable inputs', async () => {
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskCreatePage />
      </MemoryRouter>,
    )
    await flushPageEffects()

    expect(screen.getAllByText('任务目标').length).toBeGreaterThan(0)
    expect(screen.getByText('来源策略')).toBeInTheDocument()
    expect(screen.getByText('执行计划预览')).toBeInTheDocument()
    expect(screen.getByText('可调整输入')).toBeInTheDocument()
  })

  it('shows the minimal knowledge intake entry on the task creation page', async () => {
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskCreatePage />
      </MemoryRouter>,
    )
    await flushPageEffects()

    expect(screen.getByText('资料接入')).toBeInTheDocument()
    expect(screen.getByText('已有资料摘要')).toBeInTheDocument()
  })

  it('opens the unified conversation entry from the task draft context', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskCreatePage />
      </MemoryRouter>,
    )
    await flushPageEffects()

    await user.click(screen.getByRole('button', { name: '进入解释入口' }))

    expect(navigateMock).toHaveBeenCalledWith('/conversation?pageType=TASK_CREATE')
  })

  it('keeps raw queries, candidate previews, and technical tags out of the default preview path', async () => {
    vi.useFakeTimers()
    previewWorkflowMock.mockResolvedValue({ data: buildPreviewWorkflow() })

    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskCreatePage />
      </MemoryRouter>,
    )
    await flushPageEffects()

    await act(async () => {
      fireEvent.change(screen.getByLabelText('分析主题'), { target: { value: 'AI 知识库竞品分析' } })
      fireEvent.change(screen.getByLabelText('本方产品'), { target: { value: '企业级 RAG 平台' } })
      fireEvent.change(screen.getByLabelText('竞品名称'), { target: { value: 'Notion AI' } })
      await Promise.resolve()
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(450)
      await Promise.resolve()
    })

    expect(previewWorkflowMock).toHaveBeenCalled()
    expect(screen.getAllByText('Notion AI').length).toBeGreaterThan(0)
    expect(screen.getByText('来源策略')).toBeInTheDocument()
    expect(screen.getByText('执行计划预览')).toBeInTheDocument()
    expect(screen.queryByText(/搜索 Query/)).not.toBeInTheDocument()
    expect(screen.queryByText(/候选来源预览/)).not.toBeInTheDocument()
    expect(screen.queryByText('浏览器补源开启')).not.toBeInTheDocument()
  })
})
