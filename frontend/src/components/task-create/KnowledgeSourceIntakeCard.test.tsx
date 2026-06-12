import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import * as client from '../../api/client'
import type { KnowledgeDocumentInfo, KnowledgeDomainInfo } from '../../types'
import KnowledgeSourceIntakeCard from './KnowledgeSourceIntakeCard'

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return {
    ...actual,
    listKnowledgeDomains: vi.fn(),
    listKnowledgeDomainDocuments: vi.fn(),
    ingestKnowledgeSource: vi.fn(),
  }
})

function buildApiResponse<T>(data: T) {
  return {
    code: 200,
    message: 'ok',
    data,
    timestamp: '2026-06-06T20:30:00',
    traceId: 'trace-knowledge-source-card-test',
  }
}

function buildDomain(overrides: Partial<KnowledgeDomainInfo> = {}): KnowledgeDomainInfo {
  return {
    id: 7,
    domainKey: 'org-product-docs',
    domainName: '组织产品资料',
    description: '沉淀组织产品资料、发布说明与培训手册',
    allowedSourceCategories: ['UPLOADED_DOCUMENTS', 'USER_PROVIDED'],
    defaultLifecycle: 'ACTIVE',
    defaultTrustLevel: 'CURATED',
    status: 'ACTIVE',
    ...overrides,
  }
}

function buildDocument(overrides: Partial<KnowledgeDocumentInfo> = {}): KnowledgeDocumentInfo {
  return {
    id: 11,
    taskId: null,
    evidenceId: 'ORG-org-product-docs-launch-guide',
    documentKey: 'ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE',
    knowledgeScope: 'ORGANIZATION',
    knowledgeDomainId: 7,
    knowledgeDomainKey: 'org-product-docs',
    competitorName: 'Feishu',
    sourceType: 'UPLOAD',
    sourceCategory: 'UPLOADED_DOCUMENTS',
    discoveryMethod: 'UPLOAD',
    sourceDomain: 'docs.example.com',
    sourceLifecycle: 'ACTIVE',
    trustLevel: 'VERIFIED',
    connectorKey: null,
    title: '组织产品资料',
    url: 'https://docs.example.com/launch-guide.pdf',
    sourceUrls: ['https://docs.example.com/launch-guide.pdf'],
    issueFlags: [],
    consumedTaskIds: [],
    consumedEvidenceIds: [],
    traceSummary: '来源已回指到 https://docs.example.com/launch-guide.pdf，尚未发现后续任务消费记录',
    ...overrides,
  }
}

describe('KnowledgeSourceIntakeCard', () => {
  beforeEach(() => {
    vi.mocked(client.listKnowledgeDomains).mockResolvedValue(buildApiResponse([
      buildDomain(),
    ]))
    vi.mocked(client.listKnowledgeDomainDocuments).mockResolvedValue(buildApiResponse([
      buildDocument({
        id: 3,
        title: '既有资料',
        url: 'https://docs.example.com/history.pdf',
        sourceUrls: ['https://docs.example.com/history.pdf'],
        traceSummary: '来源已回指到 https://docs.example.com/history.pdf，尚未发现后续任务消费记录',
      }),
    ]))
    vi.mocked(client.ingestKnowledgeSource).mockResolvedValue(buildApiResponse(
      buildDocument({
        id: 18,
        title: '新接入资料',
        consumedTaskIds: [88],
        consumedEvidenceIds: ['T0088-COLLECT-001'],
        traceSummary: '来源已回指到 https://docs.example.com/launch-guide.pdf，并已进入 task-88 的证据消费链路',
      }),
    ))
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads domains, ingests a source, and shows a readable trace summary', async () => {
    const user = userEvent.setup()

    render(<KnowledgeSourceIntakeCard taskId={42} competitorName="Feishu" />)

    expect((await screen.findAllByText('组织产品资料')).length).toBeGreaterThan(0)
    expect(await screen.findByText('来源已回指到 https://docs.example.com/history.pdf，尚未发现后续任务消费记录')).toBeInTheDocument()

    await user.type(screen.getByLabelText('资料标题'), '新接入资料')
    await user.type(screen.getByLabelText('主链接'), 'https://docs.example.com/launch-guide.pdf')
    await user.type(screen.getByLabelText('正文内容'), '发布手册覆盖产品定位与定价。')
    await user.click(screen.getByRole('button', { name: '接入资料' }))

    await waitFor(() => {
      expect(client.ingestKnowledgeSource).toHaveBeenCalledWith({
        taskId: 42,
        competitorName: 'Feishu',
        domainKey: 'org-product-docs',
        sourceCategory: 'UPLOADED_DOCUMENTS',
        title: '新接入资料',
        url: 'https://docs.example.com/launch-guide.pdf',
        sourceUrls: ['https://docs.example.com/launch-guide.pdf'],
        contentText: '发布手册覆盖产品定位与定价。',
      })
    })

    expect(await screen.findByText('来源已回指到 https://docs.example.com/launch-guide.pdf，并已进入 task-88 的证据消费链路')).toBeInTheDocument()
    expect(screen.getByText('T0088-COLLECT-001')).toBeInTheDocument()
  }, 10000)
})
