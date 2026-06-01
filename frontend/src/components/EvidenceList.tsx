import { Empty, List, Space, Tag, Typography } from 'antd'
import { LinkOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import type { EvidenceInfo } from '../types'

const { Paragraph, Text } = Typography

function discoveryMethodMeta(method?: string) {
  if (method === 'BROWSER_PREVIEW') {
    return { color: 'blue', label: '浏览器预览补源' }
  }
  if (method === 'SERP_API') {
    return { color: 'green', label: 'SerpAPI 补源' }
  }
  if (method === 'SEARCH') {
    return { color: 'geekblue', label: '搜索补源' }
  }
  if (method === 'BROWSER') {
    return { color: 'cyan', label: '浏览器补源' }
  }
  return { color: 'default', label: '启发式补源' }
}

function metadataText(value: unknown) {
  return value == null || value === '' ? null : String(value)
}

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
          {(() => {
            const metadata = item.pageMetadata
            const discoveryMethod = item.discoveryMethod ?? (metadata?.discoveryMethod == null ? undefined : String(metadata.discoveryMethod))
            const discoveryMethodDisplay = discoveryMethodMeta(discoveryMethod)
            const verified = item.verified ?? (metadata?.verified as boolean | undefined)
            const verificationReason = item.verificationReason ?? metadataText(metadata?.verificationReason)
            const selectionReason = item.selectionReason ?? metadataText(metadata?.selectionReason)
            const selectionStage = item.selectionStage ?? metadataText(metadata?.selectionStage)
            const searchQuery = item.searchQuery ?? metadataText(metadata?.searchQuery)
            const searchEngine = item.searchEngine ?? metadataText(metadata?.searchEngine)
            const resultRank = item.resultRank ?? (typeof metadata?.resultRank === 'number' ? metadata.resultRank : null)
            const sourceType = item.sourceType ?? metadataText(metadata?.sourceType)
            const discoveryReason = item.discoveryReason ?? metadataText(metadata?.reason)
            const publishedAt = item.publishedAt ?? metadataText(metadata?.publishedAt)
            const sourceScore = item.sourceScore ?? (typeof metadata?.totalScore === 'number' ? metadata.totalScore : null)
            return (
          <div className="evidence-item">
            <Space size={[4, 4]} wrap>
              <Tag color="blue" className="mono-tag">
                {item.evidenceId}
              </Tag>
              <Tag>{item.competitorName}</Tag>
              {Boolean(sourceType) && <Tag color="cyan">{sourceType}</Tag>}
              {Boolean(discoveryMethod) && (
                <Tag color={discoveryMethodDisplay.color}>
                  {discoveryMethodDisplay.label}
                </Tag>
              )}
              {verified === true && <Tag color="green">验证通过</Tag>}
              {verified === false && <Tag color="red">验证未通过</Tag>}
              {Boolean(selectionStage) && <Tag>{selectionStage}</Tag>}
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
                “{item.contentSnippet}”
              </Paragraph>
            )}
            {(Boolean(discoveryReason)
              || Boolean(publishedAt)
              || sourceScore != null
              || Boolean(verificationReason)
              || Boolean(selectionReason)
              || Boolean(searchQuery)
              || Boolean(searchEngine)
              || resultRank != null) && (
              <Space size={[4, 4]} wrap>
                {Boolean(discoveryReason) && <Text type="secondary">{`来源说明：${discoveryReason}`}</Text>}
                {Boolean(publishedAt) && (
                  <Text type="secondary">{`发布时间：${publishedAt}`}</Text>
                )}
                {sourceScore != null && (
                  <Text type="secondary">{`优先级：${String(sourceScore)}`}</Text>
                )}
                {verificationReason && <Text type="secondary">{`验证结论：${verificationReason}`}</Text>}
                {selectionReason && <Text type="secondary">{`选源理由：${selectionReason}`}</Text>}
                {searchQuery && <Text type="secondary">{`Query：${searchQuery}`}</Text>}
                {searchEngine && <Text type="secondary">{`搜索引擎：${searchEngine}`}</Text>}
                {resultRank != null && <Text type="secondary">{`结果排名：${resultRank}`}</Text>}
              </Space>
            )}
            <Text type="secondary" className="small-text">
              采集时间：{dayjs(item.collectedAt).format('YYYY-MM-DD HH:mm:ss')}
            </Text>
          </div>
            )
          })()}
        </List.Item>
      )}
    />
  )
}
