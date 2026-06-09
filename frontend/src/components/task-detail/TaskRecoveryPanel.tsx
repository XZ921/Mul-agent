import { Alert, Card, Empty, Space, Typography } from 'antd'
import type { TaskInfo, TaskNodeInfo } from '../../types'
import { getNodeDisplayName } from '../../utils/display'
import TaskInterventionBar from './TaskInterventionBar'

const { Paragraph, Text } = Typography

type TaskRecoveryPanelProps = {
  task: TaskInfo
  nodes: TaskNodeInfo[]
  actionLoading: boolean
  onResumeTask: () => void
  onRetryTask: () => void
  onResumeNode: (nodeName: string) => void
  onRerunNode: (nodeName: string) => void
  onOpenConfigEditor: (node: TaskNodeInfo) => void
  onOpenTrace: (nodeId: number) => void
}

function buildNodeSummaryItems(node: TaskNodeInfo) {
  const items = [
    node.status === 'PAUSED'
      ? {
          label: '何时使用',
          value:
            node.interventionSummary || '当这个节点只是被人工暂停、现在希望继续后续链路时，优先使用恢复节点。',
        }
      : node.rerunActionSummary
        ? { label: '何时使用', value: node.rerunActionSummary }
        : null,
    node.impactSummary ? { label: '影响范围', value: node.impactSummary } : null,
    node.checkpointSummary ? { label: '检查点复用', value: node.checkpointSummary } : null,
    node.configRerunActionSummary ? { label: '改配置后重跑', value: node.configRerunActionSummary } : null,
    node.replayEntrySummary ? { label: '追踪与回放', value: node.replayEntrySummary } : null,
  ]

  return items.filter((item): item is { label: string; value: string } => Boolean(item?.label && item?.value))
}

export default function TaskRecoveryPanel({
  task,
  nodes,
  actionLoading,
  onResumeTask,
  onRetryTask,
  onResumeNode,
  onRerunNode,
  onOpenConfigEditor,
  onOpenTrace,
}: TaskRecoveryPanelProps) {
  const pausedNodes = nodes.filter((node) => node.status === 'PAUSED' && (node.canResumeNode || node.canSkip))
  const rerunnableNodes = nodes.filter((node) => node.canRerun || node.canUpdateConfigAndRerun)
  const actionableNodes = [...rerunnableNodes, ...pausedNodes.filter((node) => !rerunnableNodes.some((item) => item.id === node.id))]

  const taskSummaryItems = [
    task.resumeAdvice ? { label: '恢复执行', value: task.resumeAdvice } : null,
    task.retryAdvice ? { label: '整任务重置', value: task.retryAdvice } : null,
    task.replayEntrySummary ? { label: '追踪与回放', value: task.replayEntrySummary } : null,
  ].filter((item): item is { label: string; value: string } => Boolean(item?.label && item?.value))

  return (
    <Card className="work-card" title="恢复与人工干预">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div>
          <Text strong>把“恢复、重跑、改配置后继续”放回业务语境里</Text>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            先看何时该用哪个动作、会影响哪些节点、是否会保留检查点，再决定是否操作；原始输入输出和事件细节仍统一放在节点追踪与高级诊断里。
          </Paragraph>
        </div>

        {(task.canResume || task.canRetry || taskSummaryItems.length > 0) && (
          <TaskInterventionBar
            title="任务级恢复"
            description={task.interventionSummary || '这里集中说明整任务恢复、整任务重置与继续执行的差异。'}
            tone={task.status === 'FAILED' ? 'error' : task.status === 'STOPPED' ? 'warning' : 'info'}
            badgeText={task.status}
            summaryItems={taskSummaryItems}
            actions={[
              ...(task.canResume
                ? [
                    {
                      key: 'resume-task',
                      label: '恢复执行',
                      type: 'primary' as const,
                      loading: actionLoading,
                      onClick: onResumeTask,
                    },
                  ]
                : []),
              ...(task.canRetry
                ? [
                    {
                      key: 'retry-task',
                      label: '整任务重置',
                      loading: actionLoading,
                      onClick: onRetryTask,
                    },
                  ]
                : []),
            ]}
          />
        )}

        {actionableNodes.length > 0 ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {/**
             * 这里把节点级动作和问题上下文绑定在同一个卡片里，
             * 避免用户先记内部状态机，再去猜“恢复 / 重跑 / 改配置后继续”分别该点哪里。
             */}
            {actionableNodes.map((node) => (
              <TaskInterventionBar
                key={node.id}
                title={node.status === 'PAUSED' ? `${getNodeDisplayName(node)}：恢复处理` : `${getNodeDisplayName(node)}：局部重跑处理`}
                description={node.interventionSummary || node.errorMessage || '请先确认本节点是否真的是当前阻塞点，再决定下一步。'}
                tone={node.status === 'FAILED' ? 'error' : node.status === 'PAUSED' ? 'warning' : 'info'}
                badgeText={node.status}
                summaryItems={buildNodeSummaryItems(node)}
                actions={[
                  {
                    key: `trace-${node.id}`,
                    label: '查看追踪与回放',
                    onClick: () => onOpenTrace(node.id),
                  },
                  ...(node.canResumeNode
                    ? [
                        {
                          key: `resume-${node.id}`,
                          label: '恢复节点',
                          type: 'primary' as const,
                          loading: actionLoading,
                          onClick: () => onResumeNode(node.nodeName),
                        },
                      ]
                    : []),
                  ...(node.canRerun
                    ? [
                        {
                          key: `rerun-${node.id}`,
                          label: '从该节点重跑',
                          type: 'primary' as const,
                          loading: actionLoading,
                          onClick: () => onRerunNode(node.nodeName),
                        },
                      ]
                    : []),
                  ...(node.canUpdateConfigAndRerun
                    ? [
                        {
                          key: `config-rerun-${node.id}`,
                          label: '修改配置后重跑',
                          loading: actionLoading,
                          onClick: () => onOpenConfigEditor(node),
                        },
                      ]
                    : []),
                ]}
              />
            ))}
          </Space>
        ) : (
          <Alert
            type="success"
            showIcon
            message="当前没有额外的恢复或人工干预动作"
            description={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当任务出现暂停、失败或需要局部重跑时，这里会给出正式操作视图。" />}
          />
        )}
      </Space>
    </Card>
  )
}
