import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TaskCreatePage from '../pages/TaskCreatePage'
import ConversationPage from '../pages/ConversationPage'

const listSchemasMock = vi.fn()
const previewWorkflowMock = vi.fn()
const listKnowledgeDomainsMock = vi.fn()
const listKnowledgeDomainDocumentsMock = vi.fn()
const sendConversationMessageMock = vi.fn()

vi.mock('../api/client', () => ({
  createTask: vi.fn(),
  executeTask: vi.fn(),
  listSchemas: (...args: unknown[]) => listSchemasMock(...args),
  previewWorkflow: (...args: unknown[]) => previewWorkflowMock(...args),
  listKnowledgeDomains: (...args: unknown[]) => listKnowledgeDomainsMock(...args),
  listKnowledgeDomainDocuments: (...args: unknown[]) => listKnowledgeDomainDocumentsMock(...args),
  ingestKnowledgeSource: vi.fn(),
  sendConversationMessage: (...args: unknown[]) => sendConversationMessageMock(...args),
}))

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => vi.fn(),
  }
})

describe('Phase 5 knowledge intake entry', () => {
  beforeEach(() => {
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
    sendConversationMessageMock.mockResolvedValue({
      data: {
        sessionId: 7,
        answer: '可以继续补充资料。',
        sourceUrls: [],
        retrievalEvidences: [],
      },
    })
  })

  it('renders the intake entry on task creation and conversation pages', async () => {
    render(
      <MemoryRouter initialEntries={['/task/create']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/task/create" element={<TaskCreatePage />} />
          <Route path="/conversation" element={<ConversationPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('资料接入')).toBeInTheDocument()

    render(
      <MemoryRouter
        initialEntries={['/conversation?pageType=TASK_CREATE&taskName=AI%20Workspace']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/conversation" element={<ConversationPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findAllByText('资料接入')).not.toHaveLength(0)
  })
})
