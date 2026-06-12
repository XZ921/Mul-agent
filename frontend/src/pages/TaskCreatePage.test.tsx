import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import TaskCreatePage, { buildCreateTaskRequest } from './TaskCreatePage'
import type { TaskPlanPreviewContract } from '../types'

const navigateMock = vi.fn()
const createTaskMock = vi.fn()
const executeTaskMock = vi.fn()
const listSchemasMock = vi.fn()
const previewWorkflowMock = vi.fn()
const listKnowledgeDomainsMock = vi.fn()
const listKnowledgeDomainDocumentsMock = vi.fn()

function buildFormalPreviewContract(): TaskPlanPreviewContract {
  return {
    contractType: 'TASK_PLAN_PREVIEW_V1',
    goal: '围绕企业级 RAG 平台开展竞品研究',
    competitorCount: 1,
    collectorCount: 1,
    pipelineCount: 4,
    lanes: [],
    stages: [
      {
        key: 'source-strategy',
        stageCode: 'SOURCE_STRATEGY',
        title: '规划来源策略',
        summary: '优先覆盖官网、产品文档',
        detail: '不足时再补充公网搜索',
        sourceUrls: [],
      },
    ],
    nodes: [
      {
        nodeName: 'collect_sources_01_01',
        displayName: 'Notion AI - DOCS采集',
        agentType: 'COLLECTOR',
        stageCode: 'SOURCE_STRATEGY',
        goal: '优先覆盖官网与产品文档',
        summary: '不足时再补充公网搜索',
        configSummaryData: {
          competitorName: 'Notion AI',
          sourceType: 'DOCS',
          sourceTypeLabel: '产品文档',
          sourceScope: ['官网', '产品文档'],
          competitorUrls: ['https://www.notion.so/product/ai'],
          candidateCount: 4,
          queryCount: 2,
          browserSearchEnabled: true,
          verificationEnabled: true,
          minVerifiedCandidates: 1,
          preferredDomains: ['notion.so'],
          discoveryNotes: '优先官网，不足时再补充公网搜索',
        },
        dependsOn: [],
        required: true,
        executionOrder: 0,
        fallbackOrder: ['PLANNED', 'BROWSER', 'HEURISTIC', 'HTTP'],
        sourceUrls: [],
      },
    ],
    sourceUrls: [],
  }
}

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

vi.mock('../api/client', () => ({
  createTask: (...args: unknown[]) => createTaskMock(...args),
  executeTask: (...args: unknown[]) => executeTaskMock(...args),
  listSchemas: (...args: unknown[]) => listSchemasMock(...args),
  previewWorkflow: (...args: unknown[]) => previewWorkflowMock(...args),
  listKnowledgeDomains: (...args: unknown[]) => listKnowledgeDomainsMock(...args),
  listKnowledgeDomainDocuments: (...args: unknown[]) => listKnowledgeDomainDocumentsMock(...args),
  ingestKnowledgeSource: vi.fn(),
}))

describe('TaskCreatePage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    createTaskMock.mockReset()
    executeTaskMock.mockReset()
    listSchemasMock.mockResolvedValue({ data: [] })
    previewWorkflowMock.mockResolvedValue({ data: null })
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

    expect(previewWorkflowMock).toHaveBeenCalledTimes(1)
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

  it('renders preview board from formal preview contract instead of raw runtime node list', async () => {
    vi.useFakeTimers()
    previewWorkflowMock.mockResolvedValue({ data: buildFormalPreviewContract() })

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
    expect(screen.getByText(/^1\. 规划来源策略$/)).toBeInTheDocument()
    expect(screen.getByText('来源策略')).toBeInTheDocument()
    expect(screen.getByText('执行计划预览')).toBeInTheDocument()
    expect(screen.queryByText(/搜索 Query/)).not.toBeInTheDocument()
    expect(screen.queryByText(/候选来源预览/)).not.toBeInTheDocument()
    expect(screen.queryByText('浏览器补源开启')).not.toBeInTheDocument()
  })
})
