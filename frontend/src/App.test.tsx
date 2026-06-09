import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'

let shouldThrowTaskList = false
let shouldThrowTaskCreate = false
let shouldThrowTaskDetail = false
let delayConversationPage = false
let resolveConversationPage: (() => void) | null = null

vi.mock('./pages/TaskListPage', () => ({
  default: () => {
    if (shouldThrowTaskList) {
      throw new Error('task list render failed')
    }
    return <div>任务列表页</div>
  },
}))

vi.mock('./pages/TaskCreatePage', () => ({
  default: () => {
    if (shouldThrowTaskCreate) {
      throw new Error('task create render failed')
    }
    return <div>任务创建页</div>
  },
}))

vi.mock('./pages/ReportPage', () => ({
  default: () => <div>报告页</div>,
}))

vi.mock('./pages/ConversationPage', async () => {
  /**
   * 懒加载场景要在模块导入阶段制造延迟，
   * 这样测试才能真实覆盖 React.lazy 的共享加载兜底。
   */
  if (delayConversationPage) {
    await new Promise<void>((resolve) => {
      resolveConversationPage = resolve
    })
  }

  return {
    default: () => <div>统一对话页</div>,
  }
})

vi.mock('./pages/TaskDetailPage', () => ({
  default: () => {
    if (shouldThrowTaskDetail) {
      throw new Error('task detail render failed')
    }
    return <div>任务详情页</div>
  },
}))

function renderApp(initialEntry: string) {
  render(
    <MemoryRouter
      initialEntries={[initialEntry]}
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
    >
      <App />
    </MemoryRouter>,
  )
}

describe('App route safety', () => {
  beforeEach(() => {
    shouldThrowTaskList = false
    shouldThrowTaskCreate = false
    shouldThrowTaskDetail = false
    delayConversationPage = false
    resolveConversationPage = null
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps route render failures inside a fallback instead of unmounting the whole app', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    shouldThrowTaskDetail = true

    renderApp('/task/24')

    expect(await screen.findByText('页面渲染失败')).toBeInTheDocument()
    expect(screen.getByText('任务详情在渲染过程中发生异常，已自动阻止整页白屏。')).toBeInTheDocument()
  })

  it('shows a fallback when task list page crashes during render', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    shouldThrowTaskList = true

    renderApp('/')

    expect(await screen.findByText('任务列表在渲染过程中发生异常，已自动阻止整页白屏。')).toBeInTheDocument()
  })

  it('shows a fallback when task create page crashes during render', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    shouldThrowTaskCreate = true

    renderApp('/task/new')

    expect(await screen.findByText('新建任务在渲染过程中发生异常，已自动阻止整页白屏。')).toBeInTheDocument()
  })

  it('shows a shared loading fallback before lazy route content becomes available', async () => {
    delayConversationPage = true

    renderApp('/conversation')

    expect(await screen.findByText('页面加载中，请稍候...')).toBeInTheDocument()

    await act(async () => {
      resolveConversationPage?.()
    })

    expect(await screen.findByText('统一对话页')).toBeInTheDocument()
  })

  it('exposes the unified conversation route as a first-class entry', async () => {
    renderApp('/conversation')

    expect(await screen.findByText('统一对话页')).toBeInTheDocument()
  })
})
