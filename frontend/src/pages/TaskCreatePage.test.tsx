import { render, screen } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import TaskCreatePage, { buildCreateTaskRequest } from './TaskCreatePage'

const navigateMock = vi.fn()
const listSchemasMock = vi.fn()
const previewWorkflowMock = vi.fn()

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
}))

describe('TaskCreatePage', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    listSchemasMock.mockResolvedValue({ data: [] })
    previewWorkflowMock.mockResolvedValue({ data: [] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('blocks submission when required fields are missing', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <TaskCreatePage />
      </MemoryRouter>,
    )

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
      <MemoryRouter>
        <TaskCreatePage />
      </MemoryRouter>,
    )

    fireEvent.change(screen.getByLabelText('分析主题'), { target: { value: 'AI 知识库竞品分析' } })
    fireEvent.change(screen.getByLabelText('本方产品'), { target: { value: '企业级 RAG 平台' } })
    fireEvent.change(screen.getByLabelText('竞品名称'), { target: { value: 'Dify' } })
    fireEvent.change(screen.getByLabelText('分析主题'), { target: { value: 'AI 知识库竞品分析 V2' } })

    expect(previewWorkflowMock).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(450)

    expect(previewWorkflowMock).toHaveBeenCalledTimes(1)
  })
})
