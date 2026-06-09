import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { TaskNodeInfo } from '../../types'
import NodeTraceDrawer from './NodeTraceDrawer'

function buildNode(overrides: Partial<TaskNodeInfo> = {}): TaskNodeInfo {
  return {
    id: 7,
    nodeName: 'collect_sources_01',
    displayName: '官网资料采集',
    nodeConfig: '{"sourceScope":["官网"]}',
    configSummary: '优先采集官网与产品文档',
    configSummaryData: null,
    collectorInsight: null,
    nodeNotes: '先确认官网入口，再补充产品文档。',
    allowFailedDependency: false,
    agentType: 'COLLECTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 1,
    status: 'RUNNING',
    controlState: 'NONE',
    errorMessage: null,
    interventionReason: null,
    executionOrder: 1,
    inputSummary: '已接收竞品与来源范围',
    outputSummary: '已验证 2 条候选来源',
    inputData: '{"competitor":"Perplexity"}',
    outputData: '{"selectedTargets":[{"url":"https://www.perplexity.ai"}]}',
    startedAt: '2026-06-04 09:00:00',
    completedAt: null,
    canRerun: true,
    canUpdateConfigAndRerun: true,
    affectedNodeCount: 2,
    affectedNodeNames: ['extract_schema', 'analyze_competitors'],
    canReuseCheckpoint: true,
    canPause: false,
    canResumeNode: false,
    canSkip: false,
    canTerminate: true,
    interventionSummary: '如果补源仍不足，建议先调整入口再继续执行。',
    ...overrides,
  }
}

describe('NodeTraceDrawer', () => {
  it('keeps raw json and trace detail behind an explicit advanced diagnostic path', async () => {
    const user = userEvent.setup()

    render(
      <NodeTraceDrawer
        open
        selectedNode={buildNode()}
        actionLoading={false}
        onClose={() => undefined}
        onSelectNode={() => undefined}
        onPauseNode={() => undefined}
        onResumeNode={() => undefined}
        onSkipNode={() => undefined}
        onTerminateNode={() => undefined}
        onRerunNode={() => undefined}
        onOpenConfigEditor={() => undefined}
        readableConfigFields={[{ label: '本次策略', value: '优先官网，其次产品文档' }]}
        readableInputFields={[{ label: '进入条件', value: '需要先拿到竞品名称与来源范围' }]}
        readableOutputFields={[{ label: '当前结果', value: '已验证 2 条候选来源' }]}
        selectedSearchProgress={{
          status: 'RUNNING',
          currentStep: '验证候选来源',
          completedSteps: 2,
          totalSteps: 4,
          progressPercent: 50,
          message: '正在确认候选页面是否属于官网',
        }}
        selectedSearchExecutionTrace={{
          searchMode: 'HYBRID',
          browserSearchSummary: '浏览器补充了 2 条候选来源',
          plannedCandidateCount: 4,
          verifiedCandidateCount: 2,
          supplementedCandidateCount: 1,
          selectedCandidateCount: 1,
        }}
        selectedSearchExecutionPlan={null}
        selectedSearchProgressSnapshots={[]}
        selectedSourceCandidates={[]}
        sourceCandidateStageSummary={[]}
        sourceCandidateGroups={[]}
        selectedTargets={[]}
        selectedReviewPayload={null}
        selectedNodeDependencies={[]}
        selectedNodeEvidenceIds={[]}
        streamStatus="open"
        fallbackPollingActive={false}
        lastEventAt="2026-06-04 09:00:10"
      />,
    )

    expect(screen.getByText('配置摘要')).toBeInTheDocument()
    expect(screen.getByText('输入摘要')).toBeInTheDocument()
    expect(screen.getByText('输出摘要')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '进入高级诊断' })).toBeInTheDocument()
    expect(screen.queryByText('查看调试用原始配置 JSON')).not.toBeInTheDocument()
    expect(screen.queryByText('查看原始输入数据 JSON（调试）')).not.toBeInTheDocument()
    expect(screen.queryByText('查看原始输出数据 JSON（调试）')).not.toBeInTheDocument()
    expect(screen.queryByText('搜索执行轨迹')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '进入高级诊断' }))

    expect(screen.getByText('搜索执行轨迹')).toBeInTheDocument()
    expect(screen.getByText('浏览器补充了 2 条候选来源')).toBeInTheDocument()
    expect(screen.getByText('原始配置记录')).toBeInTheDocument()
    expect(screen.getByText('原始输入记录')).toBeInTheDocument()
    expect(screen.getByText('原始输出记录')).toBeInTheDocument()
  })
})
