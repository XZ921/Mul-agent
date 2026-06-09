import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import * as client from '../api/client'
import type { ConversationResponse } from '../types'
import ConversationPage from './ConversationPage'

const listKnowledgeDomainsMock = vi.fn()
const listKnowledgeDomainDocumentsMock = vi.fn()

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    sendConversationMessage: vi.fn(),
    listKnowledgeDomains: (...args: unknown[]) => listKnowledgeDomainsMock(...args),
    listKnowledgeDomainDocuments: (...args: unknown[]) => listKnowledgeDomainDocumentsMock(...args),
    ingestKnowledgeSource: vi.fn(),
  }
})

function buildApiResponse<T>(data: T) {
  return {
    code: 0,
    message: 'ok',
    data,
    timestamp: '2026-06-06T11:00:00',
    traceId: 'trace-conversation-page-test',
  }
}

function buildConversationResponse(
  overrides: Partial<ConversationResponse> = {},
): ConversationResponse {
  return {
    sessionId: 7,
    mode: 'RESEARCH',
    answer: '当前最需要先补的是官方安全说明，再决定是否重写结论。',
    currentStage: '报告修订',
    statusSummary: '报告当前卡在证据补强阶段。',
    sourceUrls: ['https://docs.notion.so/security'],
    intentDecision: {
      decisionId: 18,
      mode: 'RESEARCH',
      intentType: 'SUPPLEMENT_EVIDENCE',
      decisionReason: '用户正在追问结论背后的证据来源。',
      highRiskAction: false,
      requiresConfirmation: true,
    },
    formDraft: null,
    taskActionPreview: {
      actionType: 'SUPPLEMENT_EVIDENCE',
      taskId: 42,
      targetNodeName: 'collect_sources_web',
      title: '补充证据预览',
      actionSummary: '先补官方安全说明，再决定是否重写结论。',
      impactSummary: '会影响结论段落以及后续终审判断。',
      riskLevel: 'MEDIUM',
      requiresConfirmation: true,
      confirmationHint: '确认后再进入既有任务动作入口。',
      executable: false,
      sourceUrls: ['https://docs.notion.so/security'],
    },
    retrievalEvidences: [
      {
        evidenceId: 'E-001',
        title: 'Notion 安全文档',
        snippet: 'Notion 提供了企业级安全与权限说明。',
        sourceCategory: 'DOCS',
        sourceUrl: 'https://docs.notion.so/security',
      },
    ],
    ...overrides,
  }
}

describe('ConversationPage', () => {
  beforeEach(() => {
    vi.mocked(client.sendConversationMessage).mockResolvedValue(
      buildApiResponse(buildConversationResponse()),
    )
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
    vi.restoreAllMocks()
  })

  it('renders explanation, action preview, and evidence cards after sending a contextual message', async () => {
    const user = userEvent.setup()

    render(
      <MemoryRouter
        initialEntries={[
          '/conversation?pageType=REPORT&taskId=42&reportId=9&taskName=企业级竞品分析&reportTitle=企业级竞品分析报告',
        ]}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/conversation" element={<ConversationPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('围绕当前报告继续追问')).toBeInTheDocument()
    expect(screen.getByText('企业级竞品分析报告')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('输入你想继续追问的问题'), '这条结论的证据来自哪里？')
    await user.click(screen.getByRole('button', { name: '发送' }))

    expect(client.sendConversationMessage).toHaveBeenCalledWith({
      sessionId: null,
      taskId: 42,
      reportId: 9,
      pageType: 'REPORT',
      message: '这条结论的证据来自哪里？',
    })

    expect(await screen.findByText('当前最需要先补的是官方安全说明，再决定是否重写结论。')).toBeInTheDocument()
    expect(screen.getByText('报告当前卡在证据补强阶段。')).toBeInTheDocument()
    expect(screen.getByText('补充证据预览')).toBeInTheDocument()
    expect(screen.getByText('会影响结论段落以及后续终审判断。')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Notion 安全文档' })).toHaveAttribute(
      'href',
      'https://docs.notion.so/security',
    )
    expect(screen.queryByText('RESEARCH')).not.toBeInTheDocument()
    expect(screen.queryByText('SUPPLEMENT_EVIDENCE')).not.toBeInTheDocument()
  })

  it('lets the user confirm a high-risk action and then shows the execution result in the unified workspace', async () => {
    const user = userEvent.setup()

    vi.mocked(client.sendConversationMessage)
      .mockResolvedValueOnce(
        buildApiResponse({
          ...buildConversationResponse({
            mode: 'TASK_ACTION',
            answer: '我已经先准备好动作预览，请你确认是否继续执行。',
            currentStage: '报告改写',
            statusSummary: '从 rewrite_report 开始重跑会影响当前节点和下游改写链路。',
            sourceUrls: [],
            intentDecision: {
              decisionId: 7001,
              mode: 'TASK_ACTION',
              intentType: 'RERUN_FROM_NODE',
              decisionReason: '这次请求会影响当前节点和下游流程，所以必须先确认。',
              highRiskAction: true,
              requiresConfirmation: true,
              riskLevel: 'HIGH',
              impactScope: 'CURRENT_NODE_AND_DOWNSTREAM',
              confirmationRequest: {
                actionType: 'RERUN_NODE',
                targetType: 'TASK_NODE',
                targetId: 'rewrite_report',
                confirmationTitle: '从 rewrite_report 开始重跑',
                confirmationMessage: '请先确认会影响当前节点及下游链路，再正式执行。',
                impactScope: 'CURRENT_NODE_AND_DOWNSTREAM',
                impactSummary: '将影响当前节点和所有下游改写结果',
                riskLevel: 'HIGH',
              },
            },
            taskActionPreview: {
              actionType: 'RERUN_NODE',
              taskId: 24,
              targetNodeName: 'rewrite_report',
              title: '从 rewrite_report 开始重跑',
              actionSummary: '系统会从报告改写节点重新组织后续执行链路。',
              impactSummary: '将影响当前节点和所有下游改写结果',
              riskLevel: 'HIGH',
              requiresConfirmation: true,
              confirmationHint: '请先确认影响范围，再正式执行重跑。',
              executable: false,
              sourceUrls: [],
            },
            retrievalEvidences: [],
          }),
        } as ConversationResponse),
      )
      .mockResolvedValueOnce(
        buildApiResponse({
          ...buildConversationResponse({
            sessionId: 7,
            mode: 'TASK_ACTION',
            answer: '系统已提交从 rewrite_report 开始重跑的执行请求。',
            currentStage: '报告改写',
            statusSummary: '动作已进入执行队列，后续结果会继续写回任务时间线。',
            sourceUrls: [],
            intentDecision: {
              decisionId: 7002,
              mode: 'TASK_ACTION',
              intentType: 'CONFIRMED_RERUN_NODE',
              decisionReason: '用户已经确认高风险动作，系统已提交正式执行请求。',
              highRiskAction: true,
              requiresConfirmation: false,
              riskLevel: 'HIGH',
              impactScope: 'CURRENT_NODE_AND_DOWNSTREAM',
            },
            taskActionPreview: null,
            retrievalEvidences: [],
          }),
          taskActionExecution: {
            actionType: 'RERUN_NODE',
            taskId: 24,
            targetNodeName: 'rewrite_report',
            executionStatus: 'SUBMITTED',
            executionMessage: '系统已提交从 rewrite_report 开始重跑的执行请求。',
            previewDecisionId: 7001,
            auditDecisionId: 7002,
            auditStatus: 'RECORDED',
          },
        } as ConversationResponse),
      )

    render(
      <MemoryRouter
        initialEntries={['/conversation?pageType=TASK_DETAIL&taskId=24&taskName=%E6%B5%8B%E8%AF%95%E4%BB%BB%E5%8A%A1']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/conversation" element={<ConversationPage />} />
        </Routes>
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: '如果从某个节点继续，会影响哪些后续步骤？' }))

    expect(await screen.findByText('我已经先准备好动作预览，请你确认是否继续执行。')).toBeInTheDocument()
    expect(screen.getByText('从 rewrite_report 开始重跑')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认执行这个动作' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '确认执行这个动作' }))

    expect(client.sendConversationMessage).toHaveBeenLastCalledWith({
      sessionId: 7,
      taskId: 24,
      reportId: null,
      pageType: 'TASK_DETAIL',
      message: '确认执行这个动作',
      executeConfirmedAction: true,
      confirmationRequest: {
        actionType: 'RERUN_NODE',
        targetType: 'TASK_NODE',
        targetId: 'rewrite_report',
        confirmationTitle: '从 rewrite_report 开始重跑',
        confirmationMessage: '请先确认会影响当前节点及下游链路，再正式执行。',
        impactScope: 'CURRENT_NODE_AND_DOWNSTREAM',
        impactSummary: '将影响当前节点和所有下游改写结果',
        riskLevel: 'HIGH',
      },
    })

    expect(await screen.findByText('执行结果')).toBeInTheDocument()
    expect(screen.getAllByText('系统已提交从 rewrite_report 开始重跑的执行请求。').length).toBeGreaterThan(0)
    expect(screen.getAllByText('已提交执行').length).toBeGreaterThan(0)
    expect(screen.getAllByText('审计已记录').length).toBeGreaterThan(0)
  })
})
