import { render, screen } from '@testing-library/react'
import type { AuditSummaryInfo } from '../../types'
import AuditSummaryPanel from './AuditSummaryPanel'

describe('AuditSummaryPanel', () => {
  it('renders readable audit summaries for collection and retrieval', () => {
    const auditSummary: AuditSummaryInfo = {
      summary: '采集节点 1 个，已记录轨迹 1 个，检索查询为 Notion AI security。',
      searchAuditSummary: '采集轨迹完整，未触发浏览器降级。',
      taskRagAuditSummary: '检索与 RAG 复用了官网证据。',
      sourceUrls: ['https://docs.notion.so/security'],
    }

    render(<AuditSummaryPanel auditSummary={auditSummary} />)

    expect(screen.getByText('审计摘要')).toBeInTheDocument()
    expect(screen.getByText('采集节点 1 个，已记录轨迹 1 个，检索查询为 Notion AI security。')).toBeInTheDocument()
    expect(screen.getByText('采集轨迹完整，未触发浏览器降级。')).toBeInTheDocument()
    expect(screen.getByText('检索与 RAG 复用了官网证据。')).toBeInTheDocument()
  })

  it('falls back to an empty summary message when audit data is missing', () => {
    render(<AuditSummaryPanel auditSummary={null} />)

    expect(screen.getByText('当前暂无可展示的审计摘要。')).toBeInTheDocument()
  })
})
