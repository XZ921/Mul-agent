import { Alert, Button, Card, Progress, Space, Tag, Typography } from 'antd'
import { FileTextOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import type { TaskInfo } from '../../types'
import { taskStatusTag } from './shared'
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
  actionLoading,
  onExecute,
  onStop,
  onResume,
  onRetry,
  onViewReport,
}: TaskStatusHeroProps) {
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
                  : task.interventionSummary || '系统会在任务执行中持续展示节点状态、人工干预能力与证据风险提示。'}
            </Text>
          </div>
          <Space wrap className="hero-actions">
            {task.canExecute && (
              <Button type="primary" icon={<PlayCircleOutlined />} loading={actionLoading} onClick={onExecute}>
                {task.status === 'FAILED' ? '重新执行' : '开始执行'}
              </Button>
            )}
            {task.canStop && (
              <Button danger loading={actionLoading} onClick={onStop}>
                停止任务
              </Button>
            )}
            {task.canResume && (
              <Button type="primary" loading={actionLoading} onClick={onResume}>
                恢复执行
              </Button>
            )}
            {task.canRetry && (
              <Button icon={<ReloadOutlined />} loading={actionLoading} onClick={onRetry}>
                重置任务
              </Button>
            )}
            <Button icon={<FileTextOutlined />} disabled={!task.canViewReport} onClick={onViewReport}>
              查看报告
            </Button>
          </Space>
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
