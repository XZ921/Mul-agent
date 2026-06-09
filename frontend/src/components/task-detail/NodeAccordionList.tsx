import { Alert, Button, Card, Collapse, Descriptions, Space, Tag, Typography } from 'antd'
import type { TaskNodeInfo } from '../../types'
import { getAgentTypeText, getNodeDisplayName, getNodeNameLabel } from '../../utils/display'
import { getCollectorNodeInsight, getDependencyNames } from '../../utils/taskNodeInsights'
import SearchActivityPanel from './SearchActivityPanel'
import {
  getNodeActionSummary,
  getNodeDurationText,
  getNodeHandlingReason,
  getNodeHeadline,
  getNodeNoticeType,
  getNodeSituationSummary,
  statusTag,
} from './shared'

const { Text } = Typography

type NodeAccordionListProps = {
  nodes: TaskNodeInfo[]
  defaultExpandedNodeKeys: string[]
  actionLoading: boolean
  streamStatus: 'idle' | 'connecting' | 'open' | 'fallback' | 'closed'
  fallbackPollingActive: boolean
  lastEventAt?: string | null
  onSelectNode: (nodeId: number) => void
  onResumeNode: (nodeName: string) => void
  onTerminateNode: (nodeName: string) => void
  onRerunNode: (nodeName: string) => void
}

export default function NodeAccordionList({
  nodes,
  defaultExpandedNodeKeys,
  actionLoading,
  streamStatus,
  fallbackPollingActive,
  lastEventAt,
  onSelectNode,
  onResumeNode,
  onTerminateNode,
  onRerunNode,
}: NodeAccordionListProps) {
  return (
    <Card className="work-card" styles={{ body: { padding: 0 } }}>
      <Collapse
        className="node-accordion"
        defaultActiveKey={defaultExpandedNodeKeys}
        items={nodes.map((node) => {
          const collectorInsight = node.agentType === 'COLLECTOR' ? getCollectorNodeInsight(node) : null
          const dependencyNames = getDependencyNames(node.dependsOn)
          const configSummary = node.configSummaryData?.summaryText || node.configSummary || '暂无配置摘要'
          const situationSummary = getNodeSituationSummary(node)
          const handlingReason = getNodeHandlingReason(node)
          const actionSummary = getNodeActionSummary(node)

          return {
            key: String(node.id),
            label: (
              <div className="node-accordion-label">
                <div className="node-accordion-main">
                  <Space wrap>
                    {statusTag(node.status)}
                    <Text strong>{getNodeDisplayName(node)}</Text>
                    <Text type="secondary">{getAgentTypeText(node.agentType)}</Text>
                    {node.controlState === 'TERMINATE_REQUESTED' && <Tag color="orange">已请求终止</Tag>}
                  </Space>
                  <Text type="secondary" className="node-accordion-summary">
                    {getNodeHeadline(node)}
                  </Text>
                </div>
                <div className="node-accordion-meta">
                  <Text type="secondary">{getNodeDurationText(node)}</Text>
                </div>
              </div>
            ),
            extra: (
              <Space onClick={(event) => event.stopPropagation()} size={8} wrap>
                <Button type="link" onClick={() => onSelectNode(node.id)}>
                  查看追踪
                </Button>
                {node.canResumeNode ? (
                  <Button type="link" loading={actionLoading} onClick={() => onResumeNode(node.nodeName)}>
                    恢复节点
                  </Button>
                ) : node.canTerminate && node.status === 'RUNNING' ? (
                  <Button type="link" danger loading={actionLoading} onClick={() => onTerminateNode(node.nodeName)}>
                    请求终止
                  </Button>
                ) : node.canRerun ? (
                  <Button type="link" loading={actionLoading} onClick={() => onRerunNode(node.nodeName)}>
                    从该节点重跑
                  </Button>
                ) : null}
              </Space>
            ),
            children: (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {(node.errorMessage || node.interventionSummary) && (
                  <Alert
                    type={node.errorMessage ? getNodeNoticeType(node.status) : 'info'}
                    showIcon
                    message={node.errorMessage || '节点干预说明'}
                    description={node.interventionSummary || undefined}
                  />
                )}

                <Descriptions column={1} size="small" bordered className="readable-descriptions">
                  <Descriptions.Item label="现在怎么了">{situationSummary}</Descriptions.Item>
                  <Descriptions.Item label="为什么需要处理">{handlingReason}</Descriptions.Item>
                  <Descriptions.Item label="可执行动作">{actionSummary}</Descriptions.Item>
                  <Descriptions.Item label="这一步的目标">{configSummary}</Descriptions.Item>
                  <Descriptions.Item label="节点定位">
                    {`${getNodeNameLabel(node.nodeName)} / ${getAgentTypeText(node.agentType)}`}
                  </Descriptions.Item>
                  <Descriptions.Item label="依赖背景">
                    {dependencyNames.length > 0 ? dependencyNames.join('、') : '无上游依赖'}
                  </Descriptions.Item>
                </Descriptions>

                {collectorInsight && (
                  <Card size="small" type="inner" title="搜索与采集进度">
                    <Space direction="vertical" size={10} style={{ width: '100%' }}>
                      <div className="collector-inline-metrics">
                        <span>{`范围 ${collectorInsight.sourceScope.join('、') || '未指定'}`}</span>
                        <span>{`候选 ${collectorInsight.candidateCount} 条`}</span>
                        <span>{`选中 ${collectorInsight.selectedCount} 条`}</span>
                        <span>{`采集成功 ${collectorInsight.successCollected}/${collectorInsight.totalCollected}`}</span>
                        {collectorInsight.browserSearchEnabled && <span>浏览器补源已开启</span>}
                      </div>
                      <SearchActivityPanel
                        insight={collectorInsight}
                        streamStatus={streamStatus}
                        fallbackPollingActive={fallbackPollingActive}
                        lastEventAt={lastEventAt}
                      />
                    </Space>
                  </Card>
                )}
              </Space>
            ),
          }
        })}
      />
    </Card>
  )
}
