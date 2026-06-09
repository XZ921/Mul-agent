import { Button, Card, Input, Space, Typography } from 'antd'
import { useState } from 'react'

const { Text } = Typography
const { TextArea } = Input

interface ConversationComposerProps {
  submitting: boolean
  onSubmit: (message: string) => Promise<boolean>
}

export default function ConversationComposer({
  submitting,
  onSubmit,
}: ConversationComposerProps) {
  const [value, setValue] = useState('')

  /**
   * 发送动作统一在这里做去空白与并发保护，
   * 避免页面层和按钮事件各自维护一套提交规则。
   */
  const handleSubmit = async () => {
    const normalized = value.trim()
    if (!normalized || submitting) {
      return
    }
    const submitted = await onSubmit(normalized)
    if (submitted) {
      setValue('')
    }
  }

  return (
    <Card className="work-card conversation-composer-card">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Text strong>继续提问</Text>
        <TextArea
          value={value}
          rows={4}
          maxLength={600}
          showCount
          placeholder="输入你想继续追问的问题"
          onChange={(event) => setValue(event.target.value)}
          onPressEnter={(event) => {
            if (event.shiftKey) {
              return
            }
            event.preventDefault()
            void handleSubmit()
          }}
        />
        <div className="conversation-composer-actions">
          <Text type="secondary">按 Enter 发送，Shift + Enter 换行。</Text>
          <Button
            type="primary"
            loading={submitting}
            aria-label="发送"
            onClick={() => void handleSubmit()}
          >
            发送
          </Button>
        </div>
      </Space>
    </Card>
  )
}
