import { DownloadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, List, Space, Typography } from 'antd'
import type { ReportExportInfo } from '../../types'

const { Text } = Typography

type DeliveryExportPanelProps = {
  deliveryExports: ReportExportInfo[]
  onOpenTextExport: () => void
  onOpenHtmlExport: () => void
}

/**
 * 正式导出面板承载导出入口与最近导出记录，
 * 让报告主路径优先展示交付动作和导出回放，而不是把导出渲染细节散落在页面组件内。
 */
export default function DeliveryExportPanel({
  deliveryExports,
  onOpenTextExport,
  onOpenHtmlExport,
}: DeliveryExportPanelProps) {
  return (
    <Card title="正式导出" className="work-card">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Text type="secondary">
          这里集中展示正式导出入口和最近导出记录，方便先确认交付状态，再决定导出格式。
        </Text>
        <Space wrap>
          <Button aria-label="导出文本版" icon={<DownloadOutlined />} onClick={onOpenTextExport}>
            导出文本版
          </Button>
          <Button aria-label="导出网页版" icon={<DownloadOutlined />} onClick={onOpenHtmlExport}>
            导出网页版
          </Button>
        </Space>

        {deliveryExports.length > 0 ? (
          <List
            size="small"
            bordered
            header="最近正式导出"
            dataSource={deliveryExports}
            renderItem={(item) => (
              <List.Item>
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Text strong>{`${item.exportFormat} · v${item.exportVersion}`}</Text>
                  {item.exportSummary && <Text>{`导出摘要：${item.exportSummary}`}</Text>}
                </Space>
              </List.Item>
            )}
          />
        ) : (
          <Alert
            type="info"
            showIcon
            message="当前还没有正式导出记录"
            description="导出后，这里会保留版本、摘要和来源追溯入口。"
          />
        )}
      </Space>
    </Card>
  )
}
