import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AgentLog } from '../types'
import AgentLogPanel from './AgentLogPanel'

function buildLog(overrides: Partial<AgentLog> = {}): AgentLog {
  return {
    id: 11,
    taskId: 24,
    nodeId: 6,
    nodeName: 'collect_sources_01',
    agentType: 'COLLECTOR',
    agentName: 'CollectorAgent',
    status: 'FAILED',
    modelName: 'gpt-4.1',
    durationMs: 3200,
    reasoningSummary: '候选来源不足，需要补充官网入口',
    promptUsed: '{"messages":["prompt"]}',
    inputData: '{"competitor":"Perplexity"}',
    outputData: '{"searchExecutionTrace":{"fallbackDecision":"继续补源"}}',
    tokenUsage: '1024',
    errorMessage: '官网入口验证失败',
    traceId: 'trace-log-test',
    needsHumanIntervention: true,
    createdAt: '2026-06-04 10:00:00',
    eventCursor: 'cursor-1',
    ...overrides,
  }
}

describe('AgentLogPanel', () => {
  it('keeps log table and raw execution detail behind an explicit advanced diagnostic path', async () => {
    const user = userEvent.setup()

    render(
      <AgentLogPanel
        taskId={24}
        logs={[buildLog()]}
        streamStatus="open"
        fallbackPollingActive={false}
      />,
    )

    expect(screen.getByText('日志风险摘要')).toBeInTheDocument()
    expect(screen.getAllByText('1').length).toBeGreaterThan(0)
    expect(screen.getByText('需人工关注')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '进入高级诊断' })).toBeInTheDocument()
    expect(screen.queryByText('CollectorAgent')).not.toBeInTheDocument()
    expect(screen.queryByText('追踪编号')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '进入高级诊断' }))

    expect(screen.getByText('CollectorAgent')).toBeInTheDocument()
    expect(screen.getByText('追踪编号')).toBeInTheDocument()
    expect(screen.getByText('实时进度通道已连接，后续状态会持续同步到页面')).toBeInTheDocument()
    expect(screen.queryAllByText(/SSE/i)).toHaveLength(0)
    expect(screen.getByRole('button', { name: '收起高级诊断' })).toBeInTheDocument()
  })
})
