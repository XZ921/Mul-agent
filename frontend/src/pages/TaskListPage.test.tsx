import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import * as client from '../api/client'
import type { TaskInfo } from '../types'
import TaskListPage from './TaskListPage'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    listTasks: vi.fn(),
    deleteTask: vi.fn(),
  }
})

vi.mock('../utils/appMessage', () => ({
  appMessage: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}))

function buildTask(overrides: Partial<TaskInfo> = {}): TaskInfo {
  return {
    id: 42,
    taskName: 'Task research',
    subjectProduct: 'Workspace knowledge base',
    competitorNames: 'not-json',
    competitorUrls: '[]',
    analysisDimensions: '["feature", "pricing"]',
    sourceScope: '["official", "docs"]',
    status: 'RUNNING',
    errorMessage: null,
    totalNodes: 6,
    completedNodes: 2,
    createdAt: '2026-06-04 10:00:00',
    updatedAt: '2026-06-04 10:03:00',
    completedAt: null,
    ...overrides,
  }
}

function buildApiResponse<T>(data: T) {
  return {
    code: 0,
    message: 'ok',
    data,
    timestamp: '2026-06-04T10:03:00',
    traceId: 'trace-task-list-test',
  }
}

function buildTaskListPageData(
  items: TaskInfo[],
  overrides: Partial<{
    attentionItems: TaskInfo[]
    total: number
    pageNum: number
    pageSize: number
    summary: {
      total: number
      running: number
      success: number
      failed: number
      stopped: number
      avgProgress: number
    }
  }> = {},
) {
  const total = overrides.total ?? items.length
  const pageSize = overrides.pageSize ?? 10
  return {
    items,
    attentionItems: overrides.attentionItems ?? items,
    summary: overrides.summary ?? {
      total,
      running: items.filter((task) => task.status === 'RUNNING').length,
      success: items.filter((task) => task.status === 'SUCCESS').length,
      failed: items.filter((task) => task.status === 'FAILED').length,
      stopped: items.filter((task) => task.status === 'STOPPED').length,
      avgProgress: 0,
    },
    total,
    totalPages: Math.max(1, Math.ceil(total / pageSize)),
    pageNum: overrides.pageNum ?? 1,
    pageSize,
  }
}

describe('TaskListPage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses shared presentation copy and avoids exposing technical labels on the main entry page', async () => {
    vi.mocked(client.listTasks).mockResolvedValue(
      buildApiResponse(buildTaskListPageData([buildTask()])) as never,
    )

    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskListPage />
      </MemoryRouter>,
    )

    expect(await screen.findAllByText('Task research')).toHaveLength(2)
    expect(screen.getByText(/实时进度/)).toBeInTheDocument()
    expect(screen.queryByText(/SSE/i)).not.toBeInTheDocument()
  })

  it('uses formal pagination instead of truncating task results to the first ten rows', async () => {
    const user = userEvent.setup()
    vi.mocked(client.listTasks)
      .mockResolvedValueOnce(
        buildApiResponse(buildTaskListPageData(
          Array.from({ length: 10 }, (_, index) => buildTask({
            id: index + 1,
            taskName: `Task ${index + 1}`,
            status: 'SUCCESS',
            completedNodes: 6,
          })),
          { total: 12, pageNum: 1, pageSize: 10 },
        )) as never,
      )
      .mockResolvedValueOnce(
        buildApiResponse(buildTaskListPageData(
          Array.from({ length: 2 }, (_, index) => buildTask({
            id: index + 11,
            taskName: `Task ${index + 11}`,
            status: 'SUCCESS',
            completedNodes: 6,
          })),
          { total: 12, pageNum: 2, pageSize: 10 },
        )) as never,
      )

    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskListPage />
      </MemoryRouter>,
    )

    expect(await screen.findByText('Task 1')).toBeInTheDocument()
    expect(client.listTasks).toHaveBeenNthCalledWith(1, undefined, 1, 10)
    expect(screen.queryByText('Task 11')).not.toBeInTheDocument()

    const secondPageTrigger = document.querySelector('.ant-pagination-item-2')
    expect(secondPageTrigger).not.toBeNull()

    await user.click(secondPageTrigger as HTMLElement)

    expect(await screen.findByText('Task 11')).toBeInTheDocument()
    expect(screen.getByText('Task 12')).toBeInTheDocument()
    expect(client.listTasks).toHaveBeenNthCalledWith(2, undefined, 2, 10)
  })

  it('keeps the lightweight refresh timer stable while running tasks remain active', async () => {
    vi.useFakeTimers()
    const setIntervalSpy = vi.spyOn(window, 'setInterval')

    try {
      vi.mocked(client.listTasks).mockResolvedValue(
        buildApiResponse(buildTaskListPageData([buildTask({ status: 'RUNNING' })])) as never,
      )

      render(
        <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <TaskListPage />
        </MemoryRouter>,
      )

      await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
      })

      expect(screen.getAllByText('Task research')).toHaveLength(2)
      expect(setIntervalSpy).toHaveBeenCalledTimes(1)

      await act(async () => {
        vi.advanceTimersByTime(3000)
        await Promise.resolve()
        await Promise.resolve()
      })

      expect(client.listTasks).toHaveBeenCalledTimes(2)
      expect(client.listTasks).toHaveBeenNthCalledWith(2, undefined, 1, 10)
      expect(setIntervalSpy).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('prioritizes blocked tasks ahead of running tasks in the default attention list', async () => {
    const attentionItems = [
      buildTask({
        id: 2,
        taskName: 'Failed task',
        status: 'FAILED',
        errorMessage: 'evidence missing',
        completedNodes: 4,
      }),
      buildTask({
        id: 3,
        taskName: 'Stopped task',
        status: 'STOPPED',
        completedNodes: 5,
      }),
      buildTask({
        id: 1,
        taskName: 'Running task',
        status: 'RUNNING',
        completedNodes: 2,
      }),
    ]

    vi.mocked(client.listTasks).mockResolvedValue(
      buildApiResponse(buildTaskListPageData(
        [buildTask({ id: 99, taskName: 'Current page task', status: 'SUCCESS', completedNodes: 6 })],
        { total: 4, attentionItems },
      )) as never,
    )

    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <TaskListPage />
      </MemoryRouter>,
    )

    expect(await screen.findByText(/Current page task/)).toBeInTheDocument()

    const attentionRows = Array.from(document.querySelectorAll('.ant-list-item')).map((item) => item.textContent || '')
    expect(attentionRows[0]).toContain('Failed task')
    expect(attentionRows[1]).toContain('Stopped task')
    expect(attentionRows[2]).toContain('Running task')
  })
})
