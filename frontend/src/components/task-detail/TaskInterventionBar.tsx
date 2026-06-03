import { Alert, Button, Card, Space, Tag, Typography } from 'antd'

const { Text } = Typography

export interface TaskInterventionAction {
  key: string
  label: string
  onClick: () => void
  type?: 'primary' | 'default'
  danger?: boolean
  disabled?: boolean
  loading?: boolean
}

interface TaskInterventionBarProps {
  title: string
  description?: string | null
  tone?: 'info' | 'warning' | 'error' | 'success'
  badgeText?: string | null
  actions: TaskInterventionAction[]
}

/**
 * 统一的人工干预操作条。
 * 任务详情页顶部和节点追踪抽屉都会复用它，避免两处分别维护一套按钮显隐与说明文案。
 */
export default function TaskInterventionBar({
  title,
  description,
  tone = 'info',
  badgeText,
  actions,
}: TaskInterventionBarProps) {
  if (!actions.length && !description) {
    return null
  }

  return (
    <Card className="work-card intervention-bar">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          <Text strong>{title}</Text>
          {badgeText && <Tag color={tone === 'error' ? 'red' : tone === 'warning' ? 'orange' : tone === 'success' ? 'green' : 'blue'}>{badgeText}</Tag>}
        </Space>

        {description && <Alert type={tone} showIcon message={description} />}

        {actions.length > 0 && (
          <Space wrap>
            {actions.map((action) => (
              <Button
                key={action.key}
                type={action.type || 'default'}
                danger={action.danger}
                disabled={action.disabled}
                loading={action.loading}
                onClick={action.onClick}
              >
                {action.label}
              </Button>
            ))}
          </Space>
        )}
      </Space>
    </Card>
  )
}
