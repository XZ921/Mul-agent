import { Alert, Button, Card, Space, Tag, Typography } from 'antd'
import type { TaskActionQueueItem } from './types'

const { Text } = Typography

type TaskActionQueueProps = {
  items: TaskActionQueueItem[]
  actionLoading: boolean
  overflowCount?: number
}

export default function TaskActionQueue({ items, actionLoading, overflowCount = 0 }: TaskActionQueueProps) {
  return (
    <Card title="异常与待办" className="work-card">
      {items.length > 0 ? (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div className="action-queue">
            {items.map((item) => (
              <div className={`action-queue-item action-queue-item-${item.tone}`} key={item.key}>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag color={item.tone === 'error' ? 'red' : item.tone === 'warning' ? 'orange' : 'blue'}>
                      {item.tone === 'error' ? '高优先级' : item.tone === 'warning' ? '待处理' : '运行提示'}
                    </Tag>
                    <Text strong>{item.title}</Text>
                  </Space>
                  <Text type="secondary">{item.description}</Text>
                  <Space wrap>
                    {item.actions.map((action) => (
                      <Button
                        danger={action.kind === 'danger'}
                        key={action.key}
                        loading={actionLoading}
                        onClick={action.onClick}
                        type={action.kind === 'primary' ? 'primary' : 'default'}
                      >
                        {action.label}
                      </Button>
                    ))}
                  </Space>
                </Space>
              </div>
            ))}
          </div>

          {overflowCount > 0 && (
            <Alert
              type="info"
              showIcon
              message={`还有 ${overflowCount} 项待办未在此展开`}
              description="顶部待办区只展示最值得优先处理的项目，其余项可在下方节点追踪里查看完整上下文。"
            />
          )}
        </Space>
      ) : (
        <Alert
          showIcon
          type="success"
          message="当前没有阻塞型待办"
          description="节点状态正常时，这里会保持简洁，让用户把注意力留给 DAG 总览与业务结果。"
        />
      )}
    </Card>
  )
}
