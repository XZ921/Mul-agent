import { render, screen } from '@testing-library/react'
import type { TaskNodeInfo } from '../../types'
import NodeAccordionList from './NodeAccordionList'

function buildCollectorNode(): TaskNodeInfo {
  return {
    id: 25,
    nodeName: 'collect_sources_01_01',
    displayName: '竞品官网采集',
    nodeConfig: '{}',
    configSummary: '测试采集节点',
    configSummaryData: null,
    collectorInsight: {
      competitorName: 'Perplexity',
      sourceType: 'OFFICIAL',
      // 模拟运行时收到的脏数据：这些字段在真实场景下如果不是数组，会直接把详情页渲染打崩。
      sourceScope: '官网' as unknown as string[],
      competitorUrls: 'https://www.perplexity.ai' as unknown as string[],
      searchMode: 'HYBRID',
      searchModeLabel: '混合补源',
      searchQueries: 'perplexity ai 官网' as unknown as string[],
      browserSearchEnabled: true,
      verifyResultPage: true,
      minVerifiedCandidates: 2,
      preferredDomains: 'perplexity.ai' as unknown as string[],
      candidateCount: 3,
      selectedCount: 1,
      successCollected: 1,
      totalCollected: 1,
      discoveryNotes: '搜索中',
      searchProgress: {
        status: 'RUNNING',
        currentStep: '验证候选来源',
        progressPercent: 60,
      },
      searchExecutionPlan: {
        stage: 'SEARCH',
        steps: 'bad-step-payload' as unknown as Array<{
          stepCode: string
          goal: string
          expectedDurationMs: number
          dependency: string
        }>,
      },
      searchExecutionTrace: null,
      searchProgressSnapshots: { currentStep: '验证候选来源' } as unknown as Array<{
        currentStep?: string
      }>,
      sourceCandidates: { url: 'https://www.perplexity.ai' } as unknown as Array<{ url: string }>,
      selectedTargets: { url: 'https://www.perplexity.ai' } as unknown as Array<{ url: string }>,
    },
    nodeNotes: null,
    allowFailedDependency: false,
    agentType: 'COLLECTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 0,
    status: 'RUNNING',
    controlState: 'NONE',
    errorMessage: null,
    interventionReason: null,
    executionOrder: 1,
    inputSummary: null,
    outputSummary: '运行中',
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
  }
}

describe('NodeAccordionList', () => {
  it('renders collector cards safely when runtime collector insight contains malformed array fields', () => {
    expect(() =>
      render(
        <NodeAccordionList
          nodes={[buildCollectorNode()]}
          defaultExpandedNodeKeys={['25']}
          actionLoading={false}
          streamStatus="open"
          fallbackPollingActive={false}
          lastEventAt="2026-06-03T13:00:10"
          onSelectNode={() => undefined}
          onResumeNode={() => undefined}
          onTerminateNode={() => undefined}
          onRerunNode={() => undefined}
        />,
      ),
    ).not.toThrow()

    expect(screen.getByText('搜索与采集进度')).toBeInTheDocument()
    expect(screen.getByText('Perplexity - 官网采集')).toBeInTheDocument()
  })

  it('prioritizes node status, handling reason, and next actions on the default node path', () => {
    const failedWriterNode: TaskNodeInfo = {
      id: 41,
      nodeName: 'write_report',
      displayName: '生成分析报告',
      nodeConfig: '{}',
      configSummary: '根据已采集证据生成可交付报告',
      configSummaryData: null,
      collectorInsight: null,
      nodeNotes: null,
      allowFailedDependency: false,
      agentType: 'WRITER',
      dependsOn: '["analyze_competitors"]',
      required: true,
      retryable: true,
      maxRetries: 3,
      retryCount: 1,
      status: 'FAILED',
      controlState: 'NONE',
      errorMessage: '证据摘要不足，当前报告无法继续生成',
      interventionReason: null,
      executionOrder: 5,
      inputSummary: null,
      outputSummary: null,
      inputData: null,
      outputData: null,
      startedAt: '2026-06-04 09:00:00',
      completedAt: null,
      canRerun: true,
      canUpdateConfigAndRerun: false,
      affectedNodeCount: 1,
      affectedNodeNames: ['rewrite_report'],
      canReuseCheckpoint: false,
      canPause: false,
      canResumeNode: false,
      canSkip: false,
      canTerminate: false,
      interventionSummary: '建议先补证据，再决定是否直接重跑写作节点。',
    }

    render(
      <NodeAccordionList
        nodes={[failedWriterNode]}
        defaultExpandedNodeKeys={['41']}
        actionLoading={false}
        streamStatus="open"
        fallbackPollingActive={false}
        lastEventAt="2026-06-04T09:00:10"
        onSelectNode={() => undefined}
        onResumeNode={() => undefined}
        onTerminateNode={() => undefined}
        onRerunNode={() => undefined}
      />,
    )

    expect(screen.getByText('现在怎么了')).toBeInTheDocument()
    expect(screen.getByText('为什么需要处理')).toBeInTheDocument()
    expect(screen.getByText('可执行动作')).toBeInTheDocument()
    expect(screen.getByText('证据摘要不足，当前报告无法继续生成')).toBeInTheDocument()
    expect(screen.getByText('建议先补证据，再决定是否直接重跑写作节点。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '从该节点重跑' })).toBeInTheDocument()
    expect(screen.queryByText('技术属性')).not.toBeInTheDocument()
  })
})
