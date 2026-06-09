import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TaskConfigPanel from './TaskConfigPanel'

describe('TaskConfigPanel', () => {
  it('shows a readable task input summary first and keeps full parameter detail behind an explicit advanced path', async () => {
    const user = userEvent.setup()

    render(
      <TaskConfigPanel
        items={[
          { label: '主体产品', value: '本方产品' },
          { label: '竞品列表', value: '哔哩哔哩、Perplexity' },
          { label: '分析维度', value: '目标用户、定价策略' },
          { label: '采集范围', value: '官网、产品文档' },
          { label: '创建时间', value: '2026-06-04 10:00:00' },
        ]}
      />,
    )

    expect(screen.getByText('任务输入摘要')).toBeInTheDocument()
    expect(screen.getByText('本方产品')).toBeInTheDocument()
    expect(screen.getByText('哔哩哔哩、Perplexity')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看完整任务参数' })).toBeInTheDocument()
    expect(screen.queryByText('创建时间')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '查看完整任务参数' }))

    expect(screen.getByText('创建时间')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '收起任务参数' })).toBeInTheDocument()
  })
})
