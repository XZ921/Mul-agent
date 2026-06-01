import { Card, Collapse, Descriptions } from 'antd'

type TaskConfigPanelProps = {
  items: Array<{ label: string; value: string }>
}

export default function TaskConfigPanel({ items }: TaskConfigPanelProps) {
  return (
    <Card className="work-card task-config-card">
      <Collapse
        items={[
          {
            key: 'task-config',
            label: '任务配置与输入',
            children: (
              <Descriptions column={1} bordered size="small" className="readable-descriptions">
                {items.map((field) => (
                  <Descriptions.Item key={field.label} label={field.label}>
                    {field.value}
                  </Descriptions.Item>
                ))}
              </Descriptions>
            ),
          },
        ]}
      />
    </Card>
  )
}
