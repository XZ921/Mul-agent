import { Empty, List, Space, Tag, Typography } from 'antd'
import { LinkOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import type { EvidenceInfo } from '../types'

const { Paragraph, Text } = Typography

interface Props {
  evidences: EvidenceInfo[]
}

export default function EvidenceList({ evidences }: Props) {
  if (!evidences || evidences.length === 0) {
    return <Empty description="暂无证据来源" />
  }

  return (
    <List
      dataSource={evidences}
      renderItem={(item) => (
        <List.Item>
          <div className="evidence-item">
            <Space size={[4, 4]} wrap>
              <Tag color="blue" className="mono-tag">{item.evidenceId}</Tag>
              <Tag>{item.competitorName}</Tag>
            </Space>
            <Paragraph ellipsis={{ rows: 1 }} className="evidence-title">
              {item.title}
            </Paragraph>
            <Space align="start" className="evidence-url">
              <LinkOutlined />
              <a href={item.url} target="_blank" rel="noopener noreferrer">
                {item.url}
              </a>
            </Space>
            {item.contentSnippet && (
              <Paragraph type="secondary" ellipsis={{ rows: 3 }} className="evidence-snippet">
                "{item.contentSnippet}"
              </Paragraph>
            )}
            <Text type="secondary" className="small-text">
              采集时间: {dayjs(item.collectedAt).format('YYYY-MM-DD HH:mm:ss')}
            </Text>
          </div>
        </List.Item>
      )}
    />
  )
}
