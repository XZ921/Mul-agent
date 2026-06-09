import { Card, Descriptions, Space, Typography } from 'antd'
import AdvancedDiagnosticSection from '../AdvancedDiagnosticSection'

const { Paragraph, Text } = Typography

type TaskConfigPanelProps = {
  items: Array<{ label: string; value: string }>
}

export default function TaskConfigPanel({ items }: TaskConfigPanelProps) {
  // 任务详情首屏已经有状态与问题区，这里只保留“回忆任务目标与范围”所需的前几项摘要，
  // 其余完整参数放到显式展开里，避免再次把页面带回“配置总览器”。
  const summaryItems = items.slice(0, 4)
  const hasFullParameters = items.length > summaryItems.length

  return (
    <Card className="work-card task-config-card">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div>
          <Text strong>任务输入摘要</Text>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            先确认这次任务围绕谁展开、分析到哪里；需要复核完整创建参数时，再查看补充信息。
          </Paragraph>
        </div>

        <Descriptions column={1} bordered size="small" className="readable-descriptions">
          {summaryItems.map((field) => (
            <Descriptions.Item key={field.label} label={field.label}>
              {field.value}
            </Descriptions.Item>
          ))}
        </Descriptions>

        {hasFullParameters && (
          <AdvancedDiagnosticSection
            title="完整任务参数"
            summary="创建时间与完整任务参数只在需要复核任务输入时查看。"
            entryLabel="查看完整任务参数"
            collapseLabel="收起任务参数"
          >
            <Descriptions column={1} bordered size="small" className="readable-descriptions">
              {items.map((field) => (
                <Descriptions.Item key={`full-${field.label}`} label={field.label}>
                  {field.value}
                </Descriptions.Item>
              ))}
            </Descriptions>
          </AdvancedDiagnosticSection>
        )}
      </Space>
    </Card>
  )
}
