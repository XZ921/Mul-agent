import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReportExportInfo } from '../../types'
import DeliveryExportPanel from './DeliveryExportPanel'

describe('DeliveryExportPanel', () => {
  it('renders export actions and recent export records', async () => {
    const user = userEvent.setup()
    const openTextExport = vi.fn()
    const openHtmlExport = vi.fn()
    const deliveryExports: ReportExportInfo[] = [
      {
        id: 91,
        taskId: 42,
        exportVersion: 3,
        exportFormat: 'MARKDOWN',
        exportStatus: 'REGISTERED',
        exportSummary: '当前报告暂不可正式交付，存在 1 个阻塞问题。',
        sourceUrls: ['https://docs.notion.so/security'],
        createdAt: '2026-06-04T15:20:00',
      },
      {
        id: 92,
        taskId: 42,
        exportVersion: 2,
        exportFormat: 'HTML',
        exportStatus: 'REGISTERED',
        exportSummary: '网页正式版已保留审计摘要与证据入口。',
        sourceUrls: ['https://docs.notion.so/security'],
        createdAt: '2026-06-04T15:15:00',
      },
    ]

    render(
      <DeliveryExportPanel
        deliveryExports={deliveryExports}
        onOpenTextExport={openTextExport}
        onOpenHtmlExport={openHtmlExport}
      />,
    )

    expect(screen.getByText('正式导出')).toBeInTheDocument()
    expect(screen.getByText('这里集中展示正式导出入口和最近导出记录，方便先确认交付状态，再决定导出格式。')).toBeInTheDocument()
    expect(screen.getByText('MARKDOWN · v3')).toBeInTheDocument()
    expect(screen.getByText('HTML · v2')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '导出文本版' }))
    await user.click(screen.getByRole('button', { name: '导出网页版' }))

    expect(openTextExport).toHaveBeenCalledTimes(1)
    expect(openHtmlExport).toHaveBeenCalledTimes(1)
  })

  it('shows an empty export message when no formal export record exists', () => {
    render(
      <DeliveryExportPanel
        deliveryExports={[]}
        onOpenTextExport={vi.fn()}
        onOpenHtmlExport={vi.fn()}
      />,
    )

    expect(screen.getByText('当前还没有正式导出记录')).toBeInTheDocument()
    expect(screen.getByText('导出后，这里会保留版本、摘要和来源追溯入口。')).toBeInTheDocument()
  })
})
