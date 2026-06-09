import { Alert, Card, Empty, Space, Typography } from 'antd'
import type {
  ConversationActionConfirmationRequest,
  ConversationResponse,
} from '../../types'
import IntentDecisionCard from './IntentDecisionCard'
import RetrievalEvidenceCard from './RetrievalEvidenceCard'
import TaskActionPreviewCard from './TaskActionPreviewCard'
import TaskActionExecutionCard from './TaskActionExecutionCard'

const { Paragraph, Text } = Typography

export interface ConversationTimelineItem {
  id: string
  role: 'user' | 'assistant'
  content: string
  response?: ConversationResponse | null
}

interface ConversationMessageListProps {
  messages: ConversationTimelineItem[]
  submitting: boolean
  onConfirmAction: (confirmationRequest: ConversationActionConfirmationRequest) => Promise<boolean>
}

export default function ConversationMessageList({
  messages,
  submitting,
  onConfirmAction,
}: ConversationMessageListProps) {
  if (messages.length === 0) {
    return (
      <Card className="work-card">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="还没有对话记录。你可以直接提问任务卡点、报告依据或草稿修改建议。"
        />
      </Card>
    )
  }

  return (
    <div className="conversation-message-list">
      {messages.map((message) => {
        const response = message.response
        const sourceUrls = (response?.sourceUrls || []).filter(Boolean)
        return (
          <Card
            key={message.id}
            className={`work-card conversation-message-card conversation-message-card-${message.role}`}
          >
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Text strong>{message.role === 'user' ? '你的问题' : '助手回答'}</Text>
              <Paragraph style={{ marginBottom: 0 }}>{message.content}</Paragraph>
              {response?.currentStage && (
                <Alert type="info" showIcon message={`当前阶段：${response.currentStage}`} />
              )}
              {response?.statusSummary && (
                <Alert type="info" showIcon message={response.statusSummary} />
              )}
              {response?.intentDecision && <IntentDecisionCard decision={response.intentDecision} />}
              {response?.taskActionPreview && (
                <TaskActionPreviewCard
                  preview={response.taskActionPreview}
                  confirmationRequest={response.intentDecision?.confirmationRequest ?? null}
                  onConfirm={onConfirmAction}
                />
              )}
              {response?.taskActionExecution && (
                <TaskActionExecutionCard execution={response.taskActionExecution} />
              )}
              {response?.retrievalEvidences && response.retrievalEvidences.length > 0 && (
                <div className="conversation-evidence-grid">
                  {response.retrievalEvidences.map((evidence, index) => (
                    <RetrievalEvidenceCard
                      key={evidence.evidenceId || evidence.sourceUrl || `${message.id}-evidence-${index}`}
                      evidence={evidence}
                    />
                  ))}
                </div>
              )}
              {sourceUrls.length > 0 && (!response?.retrievalEvidences || response.retrievalEvidences.length === 0) && (
                <Card size="small" className="conversation-detail-card" title="来源链接">
                  <Space direction="vertical" size={6}>
                    {sourceUrls.map((url) => (
                      <a key={url} href={url} target="_blank" rel="noreferrer">
                        {url}
                      </a>
                    ))}
                  </Space>
                </Card>
              )}
            </Space>
          </Card>
        )
      })}
      {submitting && (
        <Card className="work-card conversation-message-card conversation-message-card-assistant">
          <Text type="secondary">系统正在整理解释、动作建议和来源依据...</Text>
        </Card>
      )}
    </div>
  )
}
