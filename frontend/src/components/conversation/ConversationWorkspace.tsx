import { Button, Card, Col, List, Row, Space, Tag, Typography } from 'antd'
import type { ConversationActionConfirmationRequest } from '../../types'
import type { ConversationEntryContext } from '../../utils/conversationPresentation'
import ConversationComposer from './ConversationComposer'
import ConversationMessageList, { type ConversationTimelineItem } from './ConversationMessageList'

const { Paragraph, Text } = Typography

interface ConversationWorkspaceProps {
  context: ConversationEntryContext
  messages: ConversationTimelineItem[]
  submitting: boolean
  onSend: (message: string) => Promise<boolean>
  onConfirmAction: (confirmationRequest: ConversationActionConfirmationRequest) => Promise<boolean>
}

export default function ConversationWorkspace({
  context,
  messages,
  submitting,
  onSend,
  onConfirmAction,
}: ConversationWorkspaceProps) {
  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={15}>
        <Card className="work-card conversation-context-card">
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Space wrap>
              <Tag color="blue">统一入口</Tag>
              <Tag color="geekblue">{context.contextLabel}</Tag>
            </Space>
            <div>
              <Text strong>{context.title}</Text>
              <Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 8 }}>
                {context.description}
              </Paragraph>
            </div>
            <Card size="small" className="conversation-detail-card" title="当前上下文">
              <Paragraph style={{ marginBottom: 0 }}>{context.contextName}</Paragraph>
            </Card>
          </Space>
        </Card>

        <div style={{ marginTop: 16 }}>
          <ConversationMessageList
            messages={messages}
            submitting={submitting}
            onConfirmAction={onConfirmAction}
          />
        </div>

        <div style={{ marginTop: 16 }}>
          <ConversationComposer submitting={submitting} onSubmit={onSend} />
        </div>
      </Col>

      <Col xs={24} lg={9}>
        <Card className="work-card" title="你可以这样问">
          <List
            dataSource={context.sampleQuestions}
            renderItem={(item) => (
              <List.Item>
                <Button type="link" onClick={() => void onSend(item)} disabled={submitting}>
                  {item}
                </Button>
              </List.Item>
            )}
          />
        </Card>
      </Col>
    </Row>
  )
}
