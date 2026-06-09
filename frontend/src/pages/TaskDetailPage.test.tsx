import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import * as client from '../api/client'
import * as taskEventStreamHook from '../hooks/useTaskEventStream'
import type { ReportInfo, TaskInfo, TaskNodeInfo } from '../types'
import { appMessage } from '../utils/appMessage'
import TaskDetailPage, { shouldFetchTaskReport } from './TaskDetailPage'

type ClientModuleWithReplayMock = typeof client & {
  getTaskReplay: ReturnType<typeof vi.fn>
}

const mockedClient = client as ClientModuleWithReplayMock

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    getTask: vi.fn(),
    getTaskNodes: vi.fn(),
    getAgentLogs: vi.fn(),
    getReport: vi.fn(),
    getTaskReplay: vi.fn(),
  }
})

vi.mock('../hooks/useTaskEventStream', async () => {
  const actual = await vi.importActual<typeof import('../hooks/useTaskEventStream')>('../hooks/useTaskEventStream')
  return {
    ...actual,
    useTaskEventStream: vi.fn(),
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
    id: 24,
    taskName: '测试任务',
    subjectProduct: '本方产品',
    competitorNames: '["哔哩哔哩"]',
    competitorUrls: '[]',
    analysisDimensions: '["目标用户"]',
    sourceScope: '["官网"]',
    status: 'RUNNING',
    errorMessage: null,
    totalNodes: 7,
    completedNodes: 4,
    createdAt: '2026-06-03 13:00:00',
    updatedAt: '2026-06-03 13:00:00',
    completedAt: null,
    canExecute: false,
    canResume: false,
    canRetry: false,
    canStop: true,
    canViewReport: false,
    interventionSummary: null,
    resumeAdvice: null,
    retryAdvice: null,
    replayEntrySummary: null,
    ...overrides,
  }
}

function buildNode(overrides: Partial<TaskNodeInfo> = {}): TaskNodeInfo {
  return {
    id: 1,
    nodeName: 'extract_schema',
    displayName: '竞品结构化抽取',
    nodeConfig: '{}',
    configSummary: null,
    configSummaryData: null,
    collectorInsight: null,
    nodeNotes: null,
    allowFailedDependency: false,
    agentType: 'EXTRACTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 0,
    status: 'RUNNING',
    controlState: 'NONE',
    errorMessage: null,
    interventionReason: null,
    executionOrder: 5,
    inputSummary: null,
    outputSummary: null,
    inputData: null,
    outputData: null,
    startedAt: '2026-06-03 13:00:00',
    completedAt: null,
    canRerun: false,
    canUpdateConfigAndRerun: false,
    affectedNodeCount: 0,
    affectedNodeNames: [],
    canReuseCheckpoint: false,
    canPause: false,
    canResumeNode: false,
    canSkip: false,
    canTerminate: false,
    interventionSummary: null,
    rerunActionSummary: null,
    configRerunActionSummary: null,
    impactSummary: null,
    checkpointSummary: null,
    replayEntrySummary: null,
    ...overrides,
  }
}

function buildApiResponse<T>(data: T) {
  return {
    code: 0,
    message: 'ok',
    data,
    timestamp: '2026-06-03T13:00:00',
    traceId: 'trace-task-detail-test',
  }
}

function buildTaskReplayData() {
  return {
    taskId: 24,
    currentPlanVersionId: 12,
    timeline: [
      {
        eventId: 'evt-collect-failed',
        taskId: 24,
        planVersionId: 12,
        planVersion: 3,
        branchKey: 'root/review-3',
        nodeName: 'collect_sources_web',
        eventType: 'NODE_FAILED',
        summary: '13:05 官网补源失败，系统进入正式恢复判定。',
        occurredAt: '2026-06-03T13:05:00',
        sourceUrls: ['https://trace.local/events/evt-collect-failed'],
      },
    ],
    nodeSummaries: [],
    recoveryAdvice: {
      recommendedAction: 'RESUME_FROM_CHECKPOINT',
      summary: '建议从官网补源检查点恢复，并沿用当前计划分支继续执行。',
      blockingNodeNames: ['collect_sources_web'],
      recommendedCheckpointId: 801,
      recommendedCheckpointKey: 'checkpoint-collect-web-1',
      resumeSupported: true,
      recoveryWindow: {
        windowScope: 'ACTIVE_PLAN_BRANCH',
        planVersionId: 12,
        branchKey: 'root/review-3',
        boundaryNodeNames: ['collect_sources_web', 'extract_schema'],
        replayableEventIds: ['evt-collect-failed'],
        windowStartAt: '2026-06-03T13:00:00',
        windowEndAt: '2026-06-03T13:05:00',
      },
      releasePolicy: {
        releaseTaskExecutionLock: true,
        releaseNodeExecutionLocks: false,
        releaseReason: '保持节点占位，避免恢复窗口被并发重跑覆盖。',
      },
      auditTrail: {
        decisionSource: 'RECOVERY_ENGINE',
        planVersionId: 12,
        triggerEventId: 'evt-collect-failed',
        latestAttemptId: 301,
        latestAttemptNo: 2,
      },
      sourceUrls: ['https://trace.local/recovery/advice'],
    },
    recoveryCheckpoints: [
      {
        id: 801,
        taskId: 24,
        planVersionId: 12,
        planVersion: 3,
        checkpointKey: 'checkpoint-collect-web-1',
        checkpointType: 'NODE_SUCCESS',
        nodeName: 'collect_sources_web',
        summary: '保留候选站点与已验证来源，恢复后可直接继续抽取。',
        payloadSnapshot: '{}',
        createdAt: '2026-06-03T13:04:00',
        sourceUrls: ['https://trace.local/checkpoints/801'],
      },
    ],
    planVersions: [
      {
        planVersionId: 12,
        planVersion: 3,
        parentPlanId: 8,
        branchKey: 'root/review-3',
        planType: 'DYNAMIC_BACKFLOW',
        triggerNodeName: 'quality_check',
        active: true,
        createdAt: '2026-06-03T12:59:00',
        sourceUrls: ['https://trace.local/plans/12'],
      },
    ],
    integrationEntryPoints: [
      {
        entryKey: 'CONVERSATION_ACTION_REPLAY',
        readinessStatus: 'RESERVED_FOR_TASK_5_9',
        targetTaskKey: 'Task 5.9',
        summary: '预留给 Task 5.9 接入对话确认、动作预览与执行结果回放。',
        sourceUrls: ['https://trace.local/task/24/replay'],
      },
      {
        entryKey: 'REPORT_EXPORT_REPLAY',
        readinessStatus: 'RESERVED_FOR_TASK_5_7',
        targetTaskKey: 'Task 5.7',
        summary: '预留给 Task 5.7 接入正式导出记录回放。',
        sourceUrls: ['https://trace.local/task/24/replay'],
      },
    ],
    sourceUrls: ['https://trace.local/task/24/replay'],
  }
}

function buildMessageHandle() {
  return undefined as never
}

function ConversationRouteProbe() {
  const location = useLocation()
  const params = new URLSearchParams(location.search)

  return (
    <div>
      <span data-testid="conversation-page-type">{params.get('pageType') || ''}</span>
      <span data-testid="conversation-task-id">{params.get('taskId') || ''}</span>
      <span data-testid="conversation-task-name">{params.get('taskName') || ''}</span>
    </div>
  )
}

describe('TaskDetailPage report gating', () => {
  it('does not fetch report while task is running before writer node succeeds', () => {
    const task = buildTask({ status: 'RUNNING', canViewReport: false })
    const nodes = [buildNode()]

    expect(shouldFetchTaskReport(task, nodes)).toBe(false)
  })

  it('fetches report after writer node succeeds even if task is not finished', () => {
    const task = buildTask({ status: 'RUNNING', canViewReport: false })
    const nodes = [
      buildNode({
        id: 2,
        nodeName: 'write_report',
        displayName: '生成分析报告',
        agentType: 'WRITER',
        status: 'SUCCESS',
        completedAt: '2026-06-03 13:10:00',
      }),
    ]

    expect(shouldFetchTaskReport(task, nodes)).toBe(true)
  })
})

describe('TaskDetailPage runtime safety', () => {
  beforeEach(() => {
    vi.mocked(taskEventStreamHook.useTaskEventStream).mockReturnValue({
      connectionStatus: 'idle',
      fallbackPollingActive: false,
      lastEventCursor: null,
      lastEventAt: null,
      lastError: null,
    })
    vi.mocked(client.getTask).mockResolvedValue(buildApiResponse(buildTask()))
    vi.mocked(client.getTaskNodes).mockResolvedValue(buildApiResponse([]))
    vi.mocked(client.getAgentLogs).mockResolvedValue(buildApiResponse([]))
    vi.mocked(client.getReport).mockResolvedValue(buildApiResponse(null as unknown as ReportInfo))
    mockedClient.getTaskReplay.mockResolvedValue(buildApiResponse(buildTaskReplayData()))
    vi.mocked(appMessage.error).mockImplementation(buildMessageHandle)
    vi.mocked(appMessage.info).mockImplementation(buildMessageHandle)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('opens the unified conversation entry with the current task context', async () => {
    const user = userEvent.setup()

    render(
      <MemoryRouter
        initialEntries={['/task/24']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/task/:id" element={<TaskDetailPage />} />
          <Route path="/conversation" element={<ConversationRouteProbe />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('测试任务')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '进入解释入口' }))

    expect(screen.getByTestId('conversation-page-type')).toHaveTextContent('TASK_DETAIL')
    expect(screen.getByTestId('conversation-task-id')).toHaveTextContent('24')
    expect(screen.getByTestId('conversation-task-name')).toHaveTextContent('测试任务')
  })

  it('keeps task detail render stable when route auto-selects a node with malformed affectedNodeNames', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    vi.mocked(client.getTaskNodes).mockResolvedValue(
      buildApiResponse([
        buildNode({
          nodeName: 'extract_schema',
          displayName: '竞品结构化抽取',
          affectedNodeNames: 'rewrite_report' as unknown as string[],
          affectedNodeCount: 1,
        }),
      ]),
    )

    render(
      <MemoryRouter
        initialEntries={['/task/24?from=report&targetNode=extract_schema&actionType=RERUN_NODE']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/task/:id" element={<TaskDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('测试任务')).toBeInTheDocument()
    expect(await screen.findByText('竞品结构化抽取追踪')).toBeInTheDocument()
    expect(screen.getByText('暂无影响范围信息')).toBeInTheDocument()
  })

  it('surfaces stage, key issue, next actions, and task graph progress on the first screen', async () => {
    vi.mocked(client.getTaskNodes).mockResolvedValue(
      buildApiResponse([
        buildNode({
          id: 2,
          nodeName: 'write_report',
          displayName: '生成分析报告',
          agentType: 'WRITER',
          status: 'FAILED',
          errorMessage: '证据摘要不足，当前报告无法继续生成',
          canRerun: true,
        }),
      ]),
    )

    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/" element={<TaskDetailPage />} />
          <Route path="/task/:id" element={<TaskDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('测试任务')).toBeInTheDocument()
    expect(screen.getAllByText('报告生成受阻').length).toBeGreaterThan(0)
    expect(screen.getByText('当前关键问题')).toBeInTheDocument()
    expect(screen.getByText('下一步动作')).toBeInTheDocument()
    expect(screen.getByText('任务图进展')).toBeInTheDocument()
    expect(screen.queryByText('DAG 总览')).not.toBeInTheDocument()
  })

  it('shows a formal recovery and intervention view with recovery, rerun, config rerun, and replay guidance', async () => {
    vi.mocked(client.getTask).mockResolvedValue(
      buildApiResponse(
        buildTask({
          status: 'FAILED',
          canResume: true,
          canRetry: true,
          canStop: false,
          interventionSummary: '当前支持恢复执行、整任务重置，以及从指定节点重跑。',
          resumeAdvice: '适合服务中断、手动停止或仅剩少量节点未完成时使用。系统会保留已完成节点，只补跑中断链路。',
          retryAdvice: '适合怀疑中间结果已经失真、需要从头重走采集到报告全链路时使用。',
          replayEntrySummary: '需要回看失败原因、检查点和原始输入输出时，请先进入节点追踪，再展开高级诊断。',
        }),
      ),
    )
    vi.mocked(client.getTaskNodes).mockResolvedValue(
      buildApiResponse([
        buildNode({
          id: 1,
          nodeName: 'collect_sources_web',
          displayName: '官网补源',
          agentType: 'COLLECTOR',
          status: 'FAILED',
          errorMessage: '候选来源不足，当前无法继续采集',
          canRerun: true,
          canUpdateConfigAndRerun: true,
          affectedNodeCount: 3,
          affectedNodeNames: ['collect_sources_web', 'extract_schema', 'write_report'],
          canReuseCheckpoint: true,
          rerunActionSummary: '适合当前节点结果不可信，但上游输入仍然可复用时使用。',
          configRerunActionSummary: '适合要扩大补源范围、调整入口策略后再继续时使用。',
          impactSummary: '从当前节点重新执行会影响 3 个节点：collect_sources_web、extract_schema、write_report。',
          checkpointSummary: '当前采集节点存在可复用检查点，重跑时会优先复用候选与选源现场。',
          replayEntrySummary: '如需确认本次动作为什么触发，请先打开节点追踪，再进入高级诊断查看回放。',
        }),
        buildNode({
          id: 2,
          nodeName: 'extract_schema',
          displayName: '竞品结构化抽取',
          agentType: 'EXTRACTOR',
          status: 'PAUSED',
          canResumeNode: true,
          canSkip: true,
          interventionReason: '节点已由用户暂停，等待恢复',
          interventionSummary: '该节点已暂停，不会继续参与 DAG 调度。可恢复为待执行节点继续流程。',
          impactSummary: '恢复后会继续当前节点及其后续待执行链路，不会清空无关已完成节点。',
          checkpointSummary: '该节点不提供独立检查点，主要复用的是未受影响上游结果。',
          replayEntrySummary: '如需确认暂停前发生了什么，请先打开节点追踪，再进入高级诊断查看回放。',
        }),
      ]),
    )

    render(
      <MemoryRouter
        initialEntries={['/task/24']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/task/:id" element={<TaskDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('恢复与人工干预')).toBeInTheDocument()
    expect(screen.getByText('适合服务中断、手动停止或仅剩少量节点未完成时使用。系统会保留已完成节点，只补跑中断链路。')).toBeInTheDocument()
    expect(screen.getByText('适合当前节点结果不可信，但上游输入仍然可复用时使用。')).toBeInTheDocument()
    expect(screen.getByText('适合要扩大补源范围、调整入口策略后再继续时使用。')).toBeInTheDocument()
    expect(screen.getByText('从当前节点重新执行会影响 3 个节点：collect_sources_web、extract_schema、write_report。')).toBeInTheDocument()
    expect(screen.getByText('当前采集节点存在可复用检查点，重跑时会优先复用候选与选源现场。')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '查看追踪与回放' }).length).toBeGreaterThan(0)
  })

  it('shows a formal replay timeline, recovery window, checkpoint explanation, and plan version summary on task detail', async () => {
    // 使用正式 replay 响应驱动断言，确保 5.6.d 聚焦的是任务详情页主路径上的正式回放视图。
    mockedClient.getTaskReplay.mockResolvedValue(buildApiResponse(buildTaskReplayData()))

    render(
      <MemoryRouter
        initialEntries={['/task/24']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/task/:id" element={<TaskDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('测试任务')).toBeInTheDocument()
    expect(screen.getByText('正式回放与恢复')).toBeInTheDocument()
    expect(screen.getByText('13:05 官网补源失败，系统进入正式恢复判定。')).toBeInTheDocument()
    expect(screen.getByText('恢复窗口：ACTIVE_PLAN_BRANCH / root/review-3 / 计划版本 v3')).toBeInTheDocument()
    expect(screen.getByText('建议动作：RESUME_FROM_CHECKPOINT')).toBeInTheDocument()
    expect(screen.getByText('保留候选站点与已验证来源，恢复后可直接继续抽取。')).toBeInTheDocument()
    expect(screen.getByText('计划版本 v3 · root/review-3 · DYNAMIC_BACKFLOW')).toBeInTheDocument()
  })
})
