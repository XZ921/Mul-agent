import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AdvancedDiagnosticSection from './AdvancedDiagnosticSection'

describe('AdvancedDiagnosticSection', () => {
  it('keeps diagnostic detail hidden until users explicitly expand it', async () => {
    const user = userEvent.setup()

    render(
      <AdvancedDiagnosticSection
        title="高级诊断"
        summary="默认只展示摘要，需要排查时再看技术细节。"
      >
        <div>原始事件游标与 JSON 明细</div>
      </AdvancedDiagnosticSection>,
    )

    expect(screen.getByText('高级诊断')).toBeInTheDocument()
    expect(screen.getByText('默认只展示摘要，需要排查时再看技术细节。')).toBeInTheDocument()
    expect(screen.queryByText('原始事件游标与 JSON 明细')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '进入高级诊断' }))

    expect(screen.getByText('原始事件游标与 JSON 明细')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '收起高级诊断' })).toBeInTheDocument()
  })
})
