import { Alert, Space, Typography } from 'antd'
import type { TaskPlanPreviewInfo } from '../../types'

const { Text } = Typography

interface TaskPlanPreviewBoardProps {
  plan: TaskPlanPreviewInfo
  hasReadyPreview: boolean
}

export default function TaskPlanPreviewBoard({
  plan,
  hasReadyPreview,
}: TaskPlanPreviewBoardProps) {
  if (!hasReadyPreview) {
    return (
      <Alert
        type="info"
        showIcon
        message="这里会先展示执行计划"
        description="系统会在任务启动前给出从来源规划、并行采集到分析、报告和复核的完整路径，帮助你先确认节奏再开始执行。"
      />
    )
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div className="plan-preview-hero">
        <div className="plan-preview-stat">
          <span className="plan-preview-value">{plan.competitorCount}</span>
          <span className="plan-preview-label">竞品泳道</span>
        </div>
        <div className="plan-preview-stat">
          <span className="plan-preview-value">{plan.collectorCount}</span>
          <span className="plan-preview-label">采集节点</span>
        </div>
        <div className="plan-preview-stat">
          <span className="plan-preview-value">{plan.pipelineCount}</span>
          <span className="plan-preview-label">后续处理节点</span>
        </div>
      </div>

      <Alert
        type="info"
        showIcon
        message="系统会先规划来源，再并行采集，最后完成分析、报告与复核"
        description="这里用业务可读的阶段说明帮助你判断这次任务会怎么做，而不是直接展示节点配置和底层执行脚本。"
      />

      <div className="preview-step-list">
        {plan.stages.map((stage, index) => (
          <div className="preview-step-item" key={stage.key}>
            <Text strong>{`${index + 1}. ${stage.title}`}</Text>
            <Text type="secondary">{stage.summary}</Text>
            {stage.detail && <Text type="secondary">{stage.detail}</Text>}
          </div>
        ))}
      </div>
    </Space>
  )
}
