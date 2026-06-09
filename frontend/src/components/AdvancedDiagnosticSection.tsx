import { useState } from 'react'
import { Button, Card, Space, Typography } from 'antd'

const { Paragraph, Text } = Typography

type AdvancedDiagnosticSectionProps = {
  title?: string
  summary?: string
  entryLabel?: string
  collapseLabel?: string
  defaultOpen?: boolean
  children: React.ReactNode
}

export default function AdvancedDiagnosticSection({
  title = '高级诊断',
  summary,
  entryLabel = '进入高级诊断',
  collapseLabel = '收起高级诊断',
  defaultOpen = false,
  children,
}: AdvancedDiagnosticSectionProps) {
  const [expanded, setExpanded] = useState(defaultOpen)

  return (
    <Card size="small" className="advanced-diagnostic-section">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div className="advanced-diagnostic-header">
          <div>
            <Text strong>{title}</Text>
            {summary && (
              <Paragraph type="secondary" className="advanced-diagnostic-summary">
                {summary}
              </Paragraph>
            )}
          </div>
          <Button type="link" onClick={() => setExpanded((value) => !value)}>
            {expanded ? collapseLabel : entryLabel}
          </Button>
        </div>

        {expanded && <div className="advanced-diagnostic-body">{children}</div>}
      </Space>
    </Card>
  )
}
