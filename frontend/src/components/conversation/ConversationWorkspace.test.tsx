import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ConversationEntryContext } from '../../utils/conversationPresentation'
import ConversationWorkspace from './ConversationWorkspace'

function buildContext(overrides: Partial<ConversationEntryContext> = {}): ConversationEntryContext {
  return {
    pageType: 'TASK_DETAIL',
    taskId: 24,
    reportId: null,
    taskName: '测试任务',
    reportTitle: null,
    contextLabel: '任务详情上下文',
    contextName: '测试任务',
    title: '围绕当前任务继续追问',
    description: '这里会先解释任务卡点、下一步建议和可继续动作。',
    sampleQuestions: ['这个任务现在卡在哪里？', '系统建议我下一步做什么？'],
    ...overrides,
  }
}

describe('ConversationWorkspace', () => {
  it('shows context-first guidance before the user starts asking questions', () => {
    render(
      <ConversationWorkspace
        context={buildContext()}
        messages={[]}
        submitting={false}
        onSend={vi.fn().mockResolvedValue(true)}
        onConfirmAction={vi.fn().mockResolvedValue(true)}
      />,
    )

    expect(screen.getByText('围绕当前任务继续追问')).toBeInTheDocument()
    expect(screen.getByText('任务详情上下文')).toBeInTheDocument()
    expect(screen.getByText('测试任务')).toBeInTheDocument()
    expect(screen.getByText('你可以这样问')).toBeInTheDocument()
  })

  it('forwards the selected sample question to the shared send handler', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn().mockResolvedValue(true)

    render(
      <ConversationWorkspace
        context={buildContext()}
        messages={[]}
        submitting={false}
        onSend={onSend}
        onConfirmAction={vi.fn().mockResolvedValue(true)}
      />,
    )

    await user.click(screen.getByRole('button', { name: '这个任务现在卡在哪里？' }))

    expect(onSend).toHaveBeenCalledWith('这个任务现在卡在哪里？')
  })
})
