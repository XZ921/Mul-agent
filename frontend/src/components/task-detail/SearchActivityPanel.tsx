import { Alert, Progress, Space, Tag, Typography } from 'antd'
import type { CollectorNodeInsight } from '../../utils/taskNodeInsights'
import { getTaskEventStreamStatusText } from '../../utils/display'
import { progressStatusTag, stepStatusTag } from './shared'

const { Text } = Typography

export default function SearchActivityPanel({
  insight,
  streamStatus,
  fallbackPollingActive = false,
  lastEventAt,
}: {
  insight: CollectorNodeInsight
  streamStatus?: 'idle' | 'connecting' | 'open' | 'fallback' | 'closed'
  fallbackPollingActive?: boolean
  lastEventAt?: string | null
}) {
  const progress = insight.searchProgress
  const plan = insight.searchExecutionPlan

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {streamStatus && (
        <Alert
          type={fallbackPollingActive ? 'warning' : streamStatus === 'open' ? 'success' : 'info'}
          showIcon
          message={getTaskEventStreamStatusText(streamStatus, fallbackPollingActive)}
          description={lastEventAt ? `最近一次实时事件：${lastEventAt}` : '当前面板会随着任务事件流持续刷新。'}
        />
      )}

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
