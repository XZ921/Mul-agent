import { Alert, Card, Progress, Space, Tag, Typography } from 'antd'
import type { TaskInfo } from '../../types'
import { getTaskEventStreamStatusText } from '../../utils/display'
import { taskStatusTag } from './shared'
import TaskInterventionBar from './TaskInterventionBar'
import type { HeroTone } from './types'

const { Text } = Typography

type TaskStatusHeroProps = {
  task: TaskInfo
  heroTone: HeroTone
  taskStageLabel: string
  completedNodeCount: number
  progressPercent: number
  pendingActionCount: number
  reviewRiskTotal: number
  activeCollectorCount: number
  streamStatus: 'idle' | 'connecting' | 'open' | 'fallback' | 'closed'
  fallbackPollingActive: boolean
  streamLastError?: string | null
  streamLastEventAt?: string | null
  actionLoading: boolean
  onExecute: () => void
  onStop: () => void
  onResume: () => void
  onRetry: () => void
  onViewReport: () => void
}

export default function TaskStatusHero({
  task,
  heroTone,
  taskStageLabel,
  completedNodeCount,
  progressPercent,
  pendingActionCount,
  reviewRiskTotal,
  activeCollectorCount,
  streamStatus,
  fallbackPollingActive,
  streamLastError,
  streamLastEventAt,
  actionLoading,
  onExecute,
  onStop,
  onResume,
  onRetry,
  onViewReport,
}: TaskStatusHeroProps) {
  const streamStatusText = getTaskEventStreamStatusText(streamStatus, fallbackPollingActive)

  return (
    <Card className={`work-card hero-card hero-tone-${heroTone}`}>
      <Space direction="vertical" size={20} style={{ width: '100%' }}>
        <div className="hero-shell">
          <div className="hero-copy">
            <Space wrap>
              {taskStatusTag(task.status)}
              <Tag
                color={
                  heroTone === 'danger'
                    ? 'red'
                    : heroTone === 'success'
                      ? 'green'
                      : heroTone === 'warning'
                        ? 'orange'
                        : 'blue'
                }
              >
                {taskStageLabel}
              </Tag>
              <Text type="secondary">{`${completedNodeCount}/${task.totalNodes} 节点已完成`}</Text>
            </Space>
            <div className="hero-title">
              {task.status === 'SUCCESS'
                ? '任务已完成，报告与节点成果均可复用'
                : task.status === 'FAILED'
                  ? '当前任务存在阻塞，需要人工修复后继续'
                  : taskStageLabel}
            </div>
            <Text type="secondary" className="hero-description">
              {pendingActionCount > 0
                ? `当前有 ${pendingActionCount} 个节点状态需要重点关注，建议优先处理失败、暂停或终止请求中的节点。`
                : reviewRiskTotal > 0
                  ? `当前识别到 ${reviewRiskTotal} 个证据风险问题，建议先前往报告页处理后再继续终审。`
                  : task.interventionSummary || '这里会持续同步任务阶段、关键变化和可继续处理的入口。'}
            </Text>
          </div>
          <div className="hero-actions">
            <TaskInterventionBar
              title="主要动作"
              description="这里集中放置继续执行、停止任务和查看报告等关键操作。"
              badgeText={pendingActionCount > 0 ? `待处理 ${pendingActionCount}` : '运行正常'}
              statusHint={streamStatusText}
              tone={pendingActionCount > 0 ? 'warning' : task.status === 'FAILED' ? 'error' : 'info'}
              actions={[
                ...(task.canExecute
                  ? [{
                      key: 'execute',
                      label: task.status === 'FAILED' ? '重新执行' : '开始执行',
                      type: 'primary' as const,
                      loading: actionLoading,
                      onClick: onExecute,
                    }]
                  : []),
                ...(task.canStop
                  ? [{
                      key: 'stop',
                      label: '停止任务',
                      danger: true,
                      loading: actionLoading,
                      onClick: onStop,
                    }]
                  : []),
                ...(task.canResume
                  ? [{
                      key: 'resume',
                      label: '恢复执行',
                      type: 'primary' as const,
                      loading: actionLoading,
                      onClick: onResume,
                    }]
                  : []),
                ...(task.canRetry
                  ? [{
                      key: 'retry',
                      label: '重置任务',
                      loading: actionLoading,
                      onClick: onRetry,
                    }]
                  : []),
                {
                  key: 'report',
                  label: '查看报告',
                  disabled: !task.canViewReport,
                  onClick: onViewReport,
                },
              ]}
            />
          </div>
        </div>

        <div className="hero-stats">
          <div className="hero-stat">
            <span className="hero-stat-value">{`${progressPercent}%`}</span>
            <span className="hero-stat-label">整体完成度</span>
          </div>
          <div className="hero-stat">
            <span className="hero-stat-value">{pendingActionCount}</span>
            <span className="hero-stat-label">节点待处理项</span>
          </div>
          <div className="hero-stat">
            <span className="hero-stat-value">{reviewRiskTotal}</span>
            <span className="hero-stat-label">证据风险问题</span>
          </div>
          <div className="hero-stat">
            <span className="hero-stat-value">{activeCollectorCount}</span>
            <span className="hero-stat-label">活跃采集节点</span>
          </div>
        </div>

        <Progress percent={progressPercent} status={task.status === 'FAILED' || task.status === 'STOPPED' ? 'exception' : undefined} />

        <Alert
          type={fallbackPollingActive ? 'warning' : streamStatus === 'open' ? 'success' : 'info'}
          showIcon
          message={streamStatusText}
          description={
            streamLastError
              || (streamLastEventAt
                ? `最近一次状态更新时间：${streamLastEventAt}`
                : '首次进入页面会先载入当前快照，后续状态会自动同步到页面。')
          }
        />

        {task.errorMessage && (
          <Alert
            type={task.status === 'STOPPED' ? 'warning' : 'error'}
            showIcon
            message={task.status === 'STOPPED' ? '任务已停止' : '任务执行异常'}
            description={task.errorMessage}
          />
        )}
      </Space>
    </Card>
  )
}
