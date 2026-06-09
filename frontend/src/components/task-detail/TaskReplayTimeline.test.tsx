import { render, screen } from '@testing-library/react'
import type { TaskReplayResponse } from '../../types'
import TaskReplayTimeline from './TaskReplayTimeline'

function buildReplay(overrides: Partial<TaskReplayResponse> = {}): TaskReplayResponse {
  return {
    taskId: 42,
    currentPlanVersionId: 1001,
    timeline: [
      {
        eventId: 'evt-1',
        taskId: 42,
        planVersionId: 1001,
        planVersion: 3,
        branchKey: 'main',
        nodeName: 'collect_sources_web',
        eventType: 'NODE_FAILED',
        summary: '官网补源节点因候选来源不足而失败',
        occurredAt: '2026-06-04T09:30:00',
        sourceUrls: ['https://docs.notion.so/security'],
      },
      {
        eventId: 'evt-2',
        taskId: 42,
        planVersionId: 1001,
        planVersion: 3,
        branchKey: 'main',
        nodeName: 'write_report',
        eventType: 'NODE_WAITING',
        summary: '',
        occurredAt: '2026-06-04T09:35:00',
        sourceUrls: ['https://docs.notion.so/security'],
      },
    ],
    nodeSummaries: [],
    recoveryAdvice: {
      recommendedAction: '优先从最近检查点恢复，再补充官网证据。',
      summary: '当前任务已具备正式恢复条件，建议先复用已完成节点成果。',
      blockingNodeNames: ['collect_sources_web'],
      recommendedCheckpointId: 501,
      recommendedCheckpointKey: 'checkpoint-001',
      resumeSupported: true,
      recoveryWindow: {
        windowScope: 'FAILED_BRANCH',
        planVersionId: 1001,
        branchKey: 'main',
        boundaryNodeNames: ['collect_sources_web'],
        replayableEventIds: ['evt-1', 'evt-2'],
        windowStartAt: '2026-06-04T09:30:00',
        windowEndAt: '2026-06-04T09:35:00',
      },
      releasePolicy: {
        releaseTaskExecutionLock: true,
        releaseNodeExecutionLocks: true,
        releaseReason: '恢复前需要释放已过期执行锁',
      },
      auditTrail: {
        decisionSource: 'RECOVERY_ENGINE',
        planVersionId: 1001,
        triggerEventId: 'evt-1',
        latestAttemptId: 301,
        latestAttemptNo: 2,
      },
      sourceUrls: ['https://docs.notion.so/security'],
    },
    recoveryCheckpoints: [
      {
        id: 501,
        taskId: 42,
        planVersionId: 1001,
        planVersion: 3,
        checkpointKey: 'checkpoint-001',
        checkpointType: 'NODE_OUTPUT',
        nodeName: 'collect_sources_web',
        summary: '已保留官网候选来源筛选结果，可直接作为恢复起点。',
        payloadSnapshot: '{"selected":1}',
        createdAt: '2026-06-04T09:29:00',
        sourceUrls: ['https://docs.notion.so/security'],
      },
    ],
    planVersions: [
      {
        planVersionId: 1001,
        planVersion: 3,
        parentPlanId: 1000,
        branchKey: 'main',
        planType: 'RECOVERY',
        triggerNodeName: 'collect_sources_web',
        active: true,
        createdAt: '2026-06-04T09:20:00',
        sourceUrls: ['https://docs.notion.so/security'],
      },
    ],
    integrationEntryPoints: [],
    sourceUrls: ['https://docs.notion.so/security'],
    ...overrides,
  }
}

describe('TaskReplayTimeline', () => {
  it('shows recovery advice, readable timeline summaries, checkpoint, and plan version summary', () => {
    render(<TaskReplayTimeline replay={buildReplay()} />)

    expect(screen.getByText('正式回放与恢复')).toBeInTheDocument()
    expect(screen.getByText('恢复入口')).toBeInTheDocument()
    expect(screen.getByText('建议动作：优先从最近检查点恢复，再补充官网证据。')).toBeInTheDocument()
    expect(screen.getByText('当前任务已具备正式恢复条件，建议先复用已完成节点成果。')).toBeInTheDocument()
    expect(screen.getByText('恢复窗口：FAILED_BRANCH / main / 计划版本 v3')).toBeInTheDocument()
    expect(screen.getByText('官网补源节点因候选来源不足而失败')).toBeInTheDocument()
    expect(screen.getByText('09:35 NODE_WAITING')).toBeInTheDocument()
    expect(screen.getByText('已保留官网候选来源筛选结果，可直接作为恢复起点。')).toBeInTheDocument()
    expect(screen.getByText('计划版本 v3 · main · RECOVERY')).toBeInTheDocument()
  })

  it('falls back to empty state when there are no replay events', () => {
    render(
      <TaskReplayTimeline
        replay={buildReplay({
          timeline: [],
          recoveryAdvice: null,
          recoveryCheckpoints: [],
          planVersions: [],
        })}
      />,
    )

    expect(screen.getByText('当前没有可展示的回放事件')).toBeInTheDocument()
  })
})
