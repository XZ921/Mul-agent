import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { TaskInfo, TaskNodeInfo } from '../../types'
import TaskRecoveryPanel from './TaskRecoveryPanel'

function buildTask(overrides: Partial<TaskInfo> = {}): TaskInfo {
  return {
    id: 24,
    taskName: '测试任务',
    subjectProduct: '本方产品',
    competitorNames: '["哔哩哔哩"]',
    competitorUrls: '[]',
    analysisDimensions: '["目标用户"]',
    sourceScope: '["官网"]',
    status: 'FAILED',
    errorMessage: '任务在报告生成阶段中断',
    totalNodes: 7,
    completedNodes: 4,
    createdAt: '2026-06-03 13:00:00',
    updatedAt: '2026-06-03 13:00:00',
    completedAt: null,
    canExecute: false,
    canResume: true,
    canRetry: true,
    canStop: false,
    canViewReport: false,
    interventionSummary: '当前支持恢复执行、整任务重置，以及从指定节点重跑。',
    resumeAdvice: '适合服务中断、手动停止或仅剩少量节点未完成时使用。系统会保留已完成节点，只补跑中断链路。',
    retryAdvice: '适合怀疑中间结果已经失真、需要从头重走采集到报告全链路时使用。',
    replayEntrySummary: '需要回看失败原因、检查点和原始输入输出时，请先进入节点追踪，再展开高级诊断。',
    ...overrides,
  }
}

function buildNode(overrides: Partial<TaskNodeInfo> = {}): TaskNodeInfo {
  return {
    id: 1,
    nodeName: 'collect_sources_web',
    displayName: '官网补源',
    nodeConfig: '{}',
    configSummary: null,
    configSummaryData: null,
    collectorInsight: null,
    nodeNotes: null,
    allowFailedDependency: false,
    agentType: 'COLLECTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 0,
    status: 'FAILED',
    controlState: 'NONE',
    errorMessage: '候选来源不足，当前无法继续采集',
    interventionReason: null,
    executionOrder: 1,
    inputSummary: null,
    outputSummary: null,
    inputData: null,
    outputData: null,
    startedAt: '2026-06-03 13:00:00',
    completedAt: null,
    canRerun: true,
    canUpdateConfigAndRerun: true,
    affectedNodeCount: 3,
    affectedNodeNames: ['collect_sources_web', 'extract_schema', 'write_report'],
    canReuseCheckpoint: true,
    canPause: false,
    canResumeNode: false,
    canSkip: false,
    canTerminate: false,
    interventionSummary: '从当前节点重跑会重置当前节点及 2 个下游节点，其余成果会保留。',
    rerunActionSummary: '适合当前节点结果不可信，但上游输入仍然可复用时使用。',
    configRerunActionSummary: '适合要扩大补源范围、调整入口策略后再继续时使用。',
    impactSummary: '从当前节点重新执行会影响 3 个节点：collect_sources_web、extract_schema、write_report。',
    checkpointSummary: '当前采集节点存在可复用检查点，重跑时会优先复用候选与选源现场。',
    replayEntrySummary: '如需确认本次动作为什么触发，请先打开节点追踪，再进入高级诊断查看回放。',
    eventKey: 'collect_sources_web',
    ...overrides,
  }
}

describe('TaskRecoveryPanel', () => {
  it('explains when to use recovery, rerun, config rerun, and trace replay entry in business language', async () => {
    const user = userEvent.setup()
    const onResumeTask = vi.fn()
    const onRetryTask = vi.fn()
    const onResumeNode = vi.fn()
    const onRerunNode = vi.fn()
    const onOpenConfigEditor = vi.fn()
    const onOpenTrace = vi.fn()
    const failedNode = buildNode()
    const pausedNode = buildNode({
      id: 2,
      nodeName: 'extract_schema',
      displayName: '竞品结构化抽取',
      agentType: 'EXTRACTOR',
      status: 'PAUSED',
      canRerun: false,
      canUpdateConfigAndRerun: false,
      canReuseCheckpoint: false,
      canResumeNode: true,
      canSkip: true,
      interventionReason: '节点已由用户暂停，等待恢复',
      interventionSummary: '该节点已暂停，不会继续参与 DAG 调度。可恢复为待执行节点继续流程。',
      rerunActionSummary: null,
      configRerunActionSummary: null,
      impactSummary: '恢复后会继续当前节点及其后续待执行链路，不会清空无关已完成节点。',
      checkpointSummary: '该节点不提供独立检查点，主要复用的是未受影响上游结果。',
      replayEntrySummary: '如需确认暂停前发生了什么，请先打开节点追踪，再进入高级诊断查看回放。',
    })

    render(
      <TaskRecoveryPanel
        task={buildTask()}
        nodes={[failedNode, pausedNode]}
        actionLoading={false}
        onResumeTask={onResumeTask}
        onRetryTask={onRetryTask}
        onResumeNode={onResumeNode}
        onRerunNode={onRerunNode}
        onOpenConfigEditor={onOpenConfigEditor}
        onOpenTrace={onOpenTrace}
      />,
    )

    expect(screen.getByText('恢复与人工干预')).toBeInTheDocument()
    expect(screen.getByText('适合服务中断、手动停止或仅剩少量节点未完成时使用。系统会保留已完成节点，只补跑中断链路。')).toBeInTheDocument()
    expect(screen.getByText('适合当前节点结果不可信，但上游输入仍然可复用时使用。')).toBeInTheDocument()
    expect(screen.getByText('适合要扩大补源范围、调整入口策略后再继续时使用。')).toBeInTheDocument()
    expect(screen.getByText('从当前节点重新执行会影响 3 个节点：collect_sources_web、extract_schema、write_report。')).toBeInTheDocument()
    expect(screen.getByText('当前采集节点存在可复用检查点，重跑时会优先复用候选与选源现场。')).toBeInTheDocument()
    expect(screen.getByText('需要回看失败原因、检查点和原始输入输出时，请先进入节点追踪，再展开高级诊断。')).toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: '查看追踪与回放' })[0])
    await user.click(screen.getByRole('button', { name: '恢复执行' }))
    await user.click(screen.getByRole('button', { name: '整任务重置' }))
    await user.click(screen.getByRole('button', { name: '恢复节点' }))
    await user.click(screen.getByRole('button', { name: '从该节点重跑' }))
    await user.click(screen.getByRole('button', { name: '修改配置后重跑' }))

    expect(onOpenTrace).toHaveBeenCalledWith(failedNode.id)
    expect(onResumeTask).toHaveBeenCalledTimes(1)
    expect(onRetryTask).toHaveBeenCalledTimes(1)
    expect(onResumeNode).toHaveBeenCalledWith(pausedNode.nodeName)
    expect(onRerunNode).toHaveBeenCalledWith(failedNode.nodeName)
    expect(onOpenConfigEditor).toHaveBeenCalledWith(failedNode)
  })
})
