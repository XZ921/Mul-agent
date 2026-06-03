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
        actions={[
          { key: 'resume', label: '恢复节点', type: 'primary', onClick: onResume },
          { key: 'skip', label: '手动跳过', onClick: onSkip },
        ]}
      />,
    )

    expect(screen.getByText('节点干预操作')).toBeInTheDocument()
    expect(screen.getByText('这里的动作只影响当前节点及其受影响下游。')).toBeInTheDocument()
    expect(screen.getByText('PAUSED')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '恢复节点' }))
    await user.click(screen.getByRole('button', { name: '手动跳过' }))

    expect(onResume).toHaveBeenCalledTimes(1)
    expect(onSkip).toHaveBeenCalledTimes(1)
  })
})
