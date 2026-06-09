import { Alert, Card, Space, Typography } from 'antd'
import type { AuditSummaryInfo } from '../../types'

const { Text } = Typography

type AuditSummaryPanelProps = {
  auditSummary: AuditSummaryInfo | null
}

/**
 * 审计摘要面板负责把后端返回的采集审计与检索审计摘要整理成主路径可读信息，
 * 避免报告页继续内联堆叠审计渲染逻辑，方便单独测试和后续扩展。
 */
export default function AuditSummaryPanel({ auditSummary }: AuditSummaryPanelProps) {
  return (
    <Card title="审计摘要" className="work-card">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Text>{auditSummary?.summary || '当前暂无可展示的审计摘要。'}</Text>
        {auditSummary?.searchAuditSummary && (
          <Alert type="info" showIcon message="采集审计" description={auditSummary.searchAuditSummary} />
        )}
        {auditSummary?.taskRagAuditSummary && (
          <Alert type="info" showIcon message="检索与 RAG 审计" description={auditSummary.taskRagAuditSummary} />
        )}
      </Space>
    </Card>
  )
}
