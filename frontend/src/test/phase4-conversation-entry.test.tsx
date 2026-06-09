import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import * as client from '../api/client'
import ConversationPage from '../pages/ConversationPage'
import type { ConversationResponse } from '../types'
import { appMessage } from '../utils/appMessage'

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
    timestamp: '2026-06-06T13:20:00',
    traceId: 'trace-phase4-conversation-entry',
  }
}

function buildConversationResponse(
  overrides: Partial<ConversationResponse> = {},
): ConversationResponse {
  return {
    sessionId: 12,
    mode: 'RESEARCH',
    answer: '当前最需要优先补的是官方安全说明，再决定是否重写结论。',
    currentStage: '报告修订',
    statusSummary: '报告当前卡在证据补强阶段。',
    sourceUrls: ['https://docs.notion.so/security'],
    intentDecision: {
      decisionId: 21,
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

function renderConversationEntry(initialEntry: string) {
  return render(
    <MemoryRouter
      initialEntries={[initialEntry]}
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
    >
      <Routes>
        <Route path="/conversation" element={<ConversationPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('phase 4 conversation entry', () => {
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

  it('shows explanation, action preview, and evidence cards after clicking a contextual report question', async () => {
    const user = userEvent.setup()

    renderConversationEntry(
      '/conversation?pageType=REPORT&taskId=42&reportId=9&taskName=企业级竞品分析&reportTitle=企业级竞品分析报告',
    )

    await user.click(screen.getByRole('button', { name: '这条结论的证据来自哪里？' }))

    expect(client.sendConversationMessage).toHaveBeenCalledWith({
      sessionId: null,
      taskId: 42,
      reportId: 9,
      pageType: 'REPORT',
      message: '这条结论的证据来自哪里？',
    })

    expect(await screen.findByText('当前最需要优先补的是官方安全说明，再决定是否重写结论。')).toBeInTheDocument()
    expect(screen.getByText('报告当前卡在证据补强阶段。')).toBeInTheDocument()
    expect(screen.getByText('补充证据预览')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Notion 安全文档' })).toHaveAttribute(
      'href',
      'https://docs.notion.so/security',
    )
    expect(screen.queryByText('RESEARCH')).not.toBeInTheDocument()
    expect(screen.queryByText('SUPPLEMENT_EVIDENCE')).not.toBeInTheDocument()
  })

  it('falls back to a safe explanation when the conversation request fails', async () => {
    const user = userEvent.setup()
    vi.mocked(client.sendConversationMessage).mockRejectedValueOnce(new Error('gateway timeout'))
    const errorSpy = vi.spyOn(appMessage, 'error').mockImplementation(() => undefined as never)

    renderConversationEntry('/conversation?pageType=TASK_DETAIL&taskId=24&taskName=测试任务')

    await user.click(screen.getByRole('button', { name: '这个任务现在卡在哪里？' }))

    expect(await screen.findByText(/当前解释入口暂时不可用/)).toBeInTheDocument()
    expect(errorSpy).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('下一步建议')).not.toBeInTheDocument()
  })
})
