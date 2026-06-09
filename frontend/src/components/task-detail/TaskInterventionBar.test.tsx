import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TaskInterventionBar from './TaskInterventionBar'

describe('TaskInterventionBar', () => {
  it('renders intervention summary and triggers actions', async () => {
    const user = userEvent.setup()
    const onResume = vi.fn()
    const onSkip = vi.fn()

    render(
      <TaskInterventionBar
        title="节点干预操作"
        description="这里的动作只影响当前节点及其受影响下游。"
        tone="warning"
        badgeText="PAUSED"
        statusHint="实时进度通道已连接，后续状态会持续同步到页面"
        summaryItems={[
          { label: '何时使用', value: '当这个节点只是被人工暂停、现在希望恢复执行时使用。' },
          { label: '影响范围', value: '会继续当前节点及后续待执行链路，不会清空无关已完成节点。' },
        ]}
        actions={[
          { key: 'resume', label: '恢复节点', type: 'primary', onClick: onResume },
          { key: 'skip', label: '手动跳过', onClick: onSkip },
        ]}
      />,
    )

    expect(screen.getByText('节点干预操作')).toBeInTheDocument()
    expect(screen.getByText('这里的动作只影响当前节点及其受影响下游。')).toBeInTheDocument()
    expect(screen.getByText('PAUSED')).toBeInTheDocument()
    expect(screen.getByText('实时进度通道已连接，后续状态会持续同步到页面')).toBeInTheDocument()
    expect(screen.getByText('何时使用')).toBeInTheDocument()
    expect(screen.getByText('当这个节点只是被人工暂停、现在希望恢复执行时使用。')).toBeInTheDocument()
    expect(screen.getByText('影响范围')).toBeInTheDocument()
    expect(screen.getByText('会继续当前节点及后续待执行链路，不会清空无关已完成节点。')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '恢复节点' }))
    await user.click(screen.getByRole('button', { name: '手动跳过' }))

    expect(onResume).toHaveBeenCalledTimes(1)
    expect(onSkip).toHaveBeenCalledTimes(1)
  })
})
