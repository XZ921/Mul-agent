import { Alert, Button, Card, Descriptions, Space, Tag, Typography } from 'antd'
import type {
  ConversationActionConfirmationRequest,
  ConversationTaskActionPreview,
} from '../../types'
import {
  compactConversationUrl,
  getConversationActionHint,
  getConversationConfirmationMessage,
  getConversationEvidenceStateText,
  getConversationImpactScopeText,
  getConversationOrchestrationDecisionText,
  getConversationRiskText,
} from '../../utils/conversationPresentation'

const { Paragraph } = Typography

interface TaskActionPreviewCardProps {
  preview: ConversationTaskActionPreview
  confirmationRequest?: ConversationActionConfirmationRequest | null
  onConfirm: (confirmationRequest: ConversationActionConfirmationRequest) => Promise<boolean>
}

/**
 * 动作预览始终用“影响范围 + 确认提示”的表达方式承载，
 * 避免用户把它误解成系统已经直接执行了某个动作。
 */
export default function TaskActionPreviewCard({
  preview,
  confirmationRequest,
  onConfirm,
}: TaskActionPreviewCardProps) {
  const sourceUrls = (preview.sourceUrls || []).filter(Boolean)
  const orchestrationDecision = preview.orchestrationDecision
  const confirmationMessage = getConversationConfirmationMessage(confirmationRequest, preview)
  const canConfirm = Boolean(preview.requiresConfirmation && confirmationRequest)

  return (
    <Card size="small" className="conversation-detail-card" title="下一步建议">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          <Tag color={preview.riskLevel === 'HIGH' ? 'red' : preview.riskLevel === 'MEDIUM' ? 'orange' : 'blue'}>
            {getConversationRiskText(preview.riskLevel)}
          </Tag>
          {preview.requiresConfirmation && <Tag color="gold">需要你先确认</Tag>}
          {orchestrationDecision?.requiresHumanIntervention && <Tag color="magenta">需要人工介入</Tag>}
        </Space>
        <Paragraph strong style={{ marginBottom: 0 }}>
          {preview.title || '当前建议已准备好'}
        </Paragraph>
        {preview.actionSummary && <Paragraph style={{ marginBottom: 0 }}>{preview.actionSummary}</Paragraph>}
        {orchestrationDecision && (
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="编排决策">
              {getConversationOrchestrationDecisionText(orchestrationDecision)}
            </Descriptions.Item>
            {orchestrationDecision.triggerNodeName && (
              <Descriptions.Item label="触发节点">
                {orchestrationDecision.triggerNodeName}
              </Descriptions.Item>
            )}
            {orchestrationDecision.reason && (
              <Descriptions.Item label="原因">
                {orchestrationDecision.reason}
              </Descriptions.Item>
            )}
            <Descriptions.Item label="证据状态">
              {getConversationEvidenceStateText(orchestrationDecision.evidenceState)}
            </Descriptions.Item>
          </Descriptions>
        )}
        {(preview.impactSummary || confirmationRequest?.impactScope || confirmationMessage) && (
          <Descriptions size="small" column={1} bordered>
            {preview.impactSummary && (
              <Descriptions.Item label="影响范围">{preview.impactSummary}</Descriptions.Item>
            )}
            {confirmationRequest?.impactScope && (
              <Descriptions.Item label="影响对象">
                {getConversationImpactScopeText(confirmationRequest.impactScope)}
              </Descriptions.Item>
            )}
            <Descriptions.Item label="确认条件">{confirmationMessage}</Descriptions.Item>
          </Descriptions>
        )}
        <Alert type="warning" showIcon message={getConversationActionHint(preview)} />
        {canConfirm && confirmationRequest && (
          <Button type="primary" danger onClick={() => void onConfirm(confirmationRequest)}>
            确认执行这个动作
          </Button>
        )}
        {sourceUrls.length > 0 && (
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            {sourceUrls.map((url) => (
              <a key={url} href={url} target="_blank" rel="noreferrer">
                {compactConversationUrl(url)}
              </a>
            ))}
          </Space>
        )}
      </Space>
    </Card>
  )
}
