import { Card, Space, Tag, Typography } from 'antd'
import type { ConversationRetrievalEvidence } from '../../types'
import {
  compactConversationUrl,
  getConversationSourceCategoryText,
} from '../../utils/conversationPresentation'

const { Paragraph, Text } = Typography

interface RetrievalEvidenceCardProps {
  evidence: ConversationRetrievalEvidence
}

export default function RetrievalEvidenceCard({ evidence }: RetrievalEvidenceCardProps) {
  return (
    <Card size="small" className="conversation-detail-card" title={evidence.title || '来源材料'}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Tag color="blue">{getConversationSourceCategoryText(evidence.sourceCategory)}</Tag>
        {evidence.snippet && <Paragraph style={{ marginBottom: 0 }}>{evidence.snippet}</Paragraph>}
        {evidence.sourceUrl && (
          <a href={evidence.sourceUrl} target="_blank" rel="noreferrer">
            {evidence.title || compactConversationUrl(evidence.sourceUrl)}
          </a>
        )}
        {evidence.evidenceId && <Text type="secondary">证据编号：{evidence.evidenceId}</Text>}
      </Space>
    </Card>
  )
}
