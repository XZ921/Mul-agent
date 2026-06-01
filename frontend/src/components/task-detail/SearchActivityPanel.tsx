import { Progress, Space, Tag, Typography } from 'antd'
import type { CollectorNodeInsight } from '../../utils/taskNodeInsights'
import { progressStatusTag, stepStatusTag } from './shared'

const { Text } = Typography

export default function SearchActivityPanel({ insight }: { insight: CollectorNodeInsight }) {
  const progress = insight.searchProgress
  const plan = insight.searchExecutionPlan

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {progress && (
        <>
          <Progress
            percent={progress.progressPercent ?? 0}
            size="small"
            status={progress.status === 'FAILED' ? 'exception' : undefined}
          />
          <Space wrap>
            {progressStatusTag(progress.status)}
            <Text strong>{progress.currentStep || '搜索计划执行中'}</Text>
            {progress.degraded && <Tag color="orange">已降级</Tag>}
          </Space>
          {progress.message && <Text type="secondary">{progress.message}</Text>}
        </>
      )}

      {plan?.steps?.length ? (
        <div className="search-step-rail">
          {plan.steps.map((step) => (
            <div className="search-step-chip" key={step.stepCode}>
              <Space wrap size={4}>
                {stepStatusTag(step.status)}
                <Text>{step.goal || step.stepCode}</Text>
              </Space>
            </div>
          ))}
        </div>
      ) : null}
    </Space>
  )
}
