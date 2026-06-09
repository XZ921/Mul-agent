import { Alert, Card, Space, Tag, Typography } from 'antd'
import type { ConversationIntentDecision } from '../../types'
import { getConversationDecisionSummary } from '../../utils/conversationPresentation'

const { Paragraph, Text } = Typography

interface IntentDecisionCardProps {
  decision: ConversationIntentDecision
}

/**
 * 这里刻意不把内部 mode / intentType 当成一级标题，
 * 而是先告诉用户“系统为什么会这样理解你的问题”。
 */
export default function IntentDecisionCard({ decision }: IntentDecisionCardProps) {
  return (
    <Card size="small" className="conversation-detail-card" title="系统为何这样理解你的问题">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Paragraph style={{ marginBottom: 0 }}>
          {decision.decisionReason || '系统会结合当前页面上下文，优先解释原因、现状与建议。'}
        </Paragraph>
        <Alert type="info" showIcon message={getConversationDecisionSummary(decision)} />
        <Space wrap>
          {decision.highRiskAction && <Tag color="orange">涉及高影响动作</Tag>}
          {decision.requiresConfirmation && <Tag color="gold">会先给出预览与确认</Tag>}
          {!decision.highRiskAction && !decision.requiresConfirmation && <Tag color="blue">优先解释与澄清</Tag>}
        </Space>
        {decision.intentType && (
          <Text type="secondary">当前判断会继续围绕你的问题给出解释与下一步建议。</Text>
        )}
      </Space>
    </Card>
  )
}
