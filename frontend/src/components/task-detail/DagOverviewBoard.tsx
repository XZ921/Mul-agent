import { Card, Space, Typography } from 'antd'
import type { TaskNodeInfo } from '../../types'
import { getNodeDisplayName, getSourceTypeText } from '../../utils/display'
import type { CollectorLaneGroup } from './types'

const { Text } = Typography

type DagOverviewBoardProps = {
  collectorLaneGroups: CollectorLaneGroup
  pipelineNodes: TaskNodeInfo[]
  getNodeHeadline: (node: TaskNodeInfo) => string
  onSelectNode: (nodeId: number) => void
}

export default function DagOverviewBoard({
  collectorLaneGroups,
  pipelineNodes,
  getNodeHeadline,
  onSelectNode,
}: DagOverviewBoardProps) {
  return (
    <Card title="DAG 总览" className="work-card">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Text type="secondary">
          先看并行采集分支，再看汇聚后的抽取、分析、写作与质检主链路。点击节点可直接进入追踪与人工干预。
        </Text>

        <div className="dag-lane-board">
          {collectorLaneGroups.map(([competitor, group]) => (
            <div className="dag-lane" key={competitor}>
              <div className="dag-lane-header">
                <Text strong>{competitor}</Text>
                <Text type="secondary">{`${group.length} 个采集节点`}</Text>
              </div>
              <div className="dag-node-row">
                {group.map(({ node, insight }) => (
                  <button
                    className={`dag-node-pill dag-node-pill-${node.status.toLowerCase()}`}
                    key={node.id}
                    onClick={() => onSelectNode(node.id)}
                    type="button"
                  >
                    <span className="dag-node-pill-title">{getSourceTypeText(insight.sourceType)}</span>
                    <span className="dag-node-pill-meta">{getNodeHeadline(node)}</span>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>

        <div className="dag-connector">全部采集结果将汇聚到统一分析主链路</div>

        <div className="dag-pipeline">
          {pipelineNodes.map((node) => (
            <button
              className={`dag-pipeline-node dag-node-pill-${node.status.toLowerCase()}`}
              key={node.id}
              onClick={() => onSelectNode(node.id)}
              type="button"
            >
              <span className="dag-node-pill-title">{getNodeDisplayName(node)}</span>
              <span className="dag-node-pill-meta">{getNodeHeadline(node)}</span>
            </button>
          ))}
        </div>
      </Space>
    </Card>
  )
}
