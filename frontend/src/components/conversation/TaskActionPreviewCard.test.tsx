import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TaskActionPreviewCard from './TaskActionPreviewCard'

describe('TaskActionPreviewCard', () => {
  it('renders orchestration decision summary and source links', () => {
    render(
      <TaskActionPreviewCard
        preview={{
          actionType: 'SUPPLEMENT_EVIDENCE',
          taskId: 42,
          targetNodeName: 'collect_sources_web',
          title: '补充证据预览',
          actionSummary: '先补官方安全说明，再决定是否重写结论。',
          impactSummary: '会影响结论段落以及后续终审判断。',
          riskLevel: 'MEDIUM',
          requiresConfirmation: true,
          confirmationHint: '确认后再进入既有任务动作入口。',
          executable: false,
          orchestrationDecision: {
            decisionId: 'od-42-analyze-human',
            triggerNodeName: 'analyze_competitors',
            decisionType: 'WAIT_FOR_HUMAN',
            actionType: 'MANUAL_REVIEW',
            reason: 'Analyzer 发现关键结论缺少可回指来源。',
            requiresHumanIntervention: true,
            evidenceState: 'MISSING_SOURCE',
            sourceUrls: ['https://ops.example.com/analyzer-gap'],
          },
          sourceUrls: [
            'https://docs.notion.so/security',
            'https://ops.example.com/analyzer-gap',
          ],
        }}
        confirmationRequest={{
          actionType: 'SUPPLEMENT_EVIDENCE',
          targetType: 'TASK_NODE',
          targetId: 'collect_sources_web',
          confirmationMessage: '确认后再进入既有任务动作入口。',
          impactScope: 'TASK_EVIDENCE_CHAIN',
        }}
        onConfirm={vi.fn().mockResolvedValue(true)}
      />,
    )

    expect(screen.getByText('编排决策')).toBeInTheDocument()
    expect(screen.getByText('等待人工介入')).toBeInTheDocument()
    expect(screen.getByText('触发节点')).toBeInTheDocument()
    expect(screen.getByText('analyze_competitors')).toBeInTheDocument()
    expect(screen.getByText('证据状态')).toBeInTheDocument()
    expect(screen.getByText('缺少可回指来源')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'docs.notion.so/security' })).toHaveAttribute(
      'href',
      'https://docs.notion.so/security',
    )
  })

  it('hides confirm button when preview does not require confirmation', () => {
    render(
      <TaskActionPreviewCard
        preview={{
          actionType: 'WAIT_FOR_HUMAN',
          taskId: 42,
          title: '等待人工介入',
          actionSummary: '当前只展示决策摘要，不生成可执行确认按钮。',
          riskLevel: 'HIGH',
          requiresConfirmation: false,
          executable: false,
          orchestrationDecision: {
            decisionType: 'WAIT_FOR_HUMAN',
            actionType: 'MANUAL_REVIEW',
            requiresHumanIntervention: true,
            evidenceState: 'MISSING_SOURCE',
          },
          sourceUrls: [],
        }}
        confirmationRequest={{
          actionType: 'WAIT_FOR_HUMAN',
          confirmationMessage: '不应显示确认按钮',
        }}
        onConfirm={vi.fn().mockResolvedValue(true)}
      />,
    )

    expect(screen.queryByRole('button', { name: '确认执行这个动作' })).not.toBeInTheDocument()
  })

  it('forwards the confirmation request when confirm button is clicked', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn().mockResolvedValue(true)

    render(
      <TaskActionPreviewCard
        preview={{
          actionType: 'RERUN_NODE',
          taskId: 24,
          targetNodeName: 'rewrite_report',
          title: '从 rewrite_report 开始重跑',
          actionSummary: '系统会从报告改写节点重新组织后续执行链路。',
          impactSummary: '将影响当前节点和所有下游改写结果',
          riskLevel: 'HIGH',
          requiresConfirmation: true,
          confirmationHint: '请先确认影响范围，再正式执行重跑。',
          executable: false,
          sourceUrls: [],
        }}
        confirmationRequest={{
          actionType: 'RERUN_NODE',
          targetType: 'TASK_NODE',
          targetId: 'rewrite_report',
          confirmationMessage: '请先确认影响范围，再正式执行重跑。',
          impactScope: 'CURRENT_NODE_AND_DOWNSTREAM',
        }}
        onConfirm={onConfirm}
      />,
    )

    await user.click(screen.getByRole('button', { name: '确认执行这个动作' }))

    expect(onConfirm).toHaveBeenCalledWith({
      actionType: 'RERUN_NODE',
      targetType: 'TASK_NODE',
      targetId: 'rewrite_report',
      confirmationMessage: '请先确认影响范围，再正式执行重跑。',
      impactScope: 'CURRENT_NODE_AND_DOWNSTREAM',
    })
  })
})
