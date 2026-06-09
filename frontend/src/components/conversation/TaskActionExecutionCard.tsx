import { Alert, Card, Descriptions, Space, Tag, Typography } from 'antd'
import type { ConversationTaskActionExecutionResult } from '../../types'
import {
  getConversationAuditStatusText,
  getConversationExecutionStatusText,
  getConversationExecutionTone,
} from '../../utils/conversationPresentation'

const { Paragraph } = Typography

interface TaskActionExecutionCardProps {
  execution: ConversationTaskActionExecutionResult
}

/**
 * 执行结果单独用结果卡片呈现，
 * 避免用户只能从原始 DTO 字段里自己判断动作是否真正进入执行与审计链路。
 */
export default function TaskActionExecutionCard({ execution }: TaskActionExecutionCardProps) {
  return (
    <Card size="small" className="conversation-detail-card" title="执行结果">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          <Tag color={execution.executionStatus === 'SUBMITTED' ? 'green' : execution.executionStatus === 'FAILED' ? 'red' : 'blue'}>
            {getConversationExecutionStatusText(execution.executionStatus)}
          </Tag>
          <Tag color={execution.auditStatus === 'RECORDED' ? 'geekblue' : execution.auditStatus === 'FAILED' ? 'red' : 'default'}>
            {getConversationAuditStatusText(execution.auditStatus)}
          </Tag>
        </Space>
        {execution.executionMessage && <Paragraph style={{ marginBottom: 0 }}>{execution.executionMessage}</Paragraph>}
        <Alert
          type={getConversationExecutionTone(execution)}
          showIcon
          message={getConversationExecutionStatusText(execution.executionStatus)}
          description={getConversationAuditStatusText(execution.auditStatus)}
        />
        <Descriptions size="small" column={1} bordered>
          {execution.targetNodeName && (
            <Descriptions.Item label="执行目标">{execution.targetNodeName}</Descriptions.Item>
          )}
          {execution.previewDecisionId != null && (
            <Descriptions.Item label="预览记录">{String(execution.previewDecisionId)}</Descriptions.Item>
          )}
          {execution.auditDecisionId != null && (
            <Descriptions.Item label="执行审计">{String(execution.auditDecisionId)}</Descriptions.Item>
          )}
        </Descriptions>
      </Space>
    </Card>
  )
}
