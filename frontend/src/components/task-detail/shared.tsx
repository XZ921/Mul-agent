import dayjs from 'dayjs'
import { Tag } from 'antd'
import type { NodeStatus, TaskInfo, TaskNodeInfo, TaskStatus } from '../../types'
import { getNodeStatusText, getTaskStatusText } from '../../utils/display'
import { getCollectorNodeInsight } from '../../utils/taskNodeInsights'
import type { HeroTone, ReviewPayload } from './types'

const nodeStatusColorMap: Record<NodeStatus, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  PAUSED: 'warning',
  SUCCESS: 'success',
  FAILED: 'error',
  SKIPPED: 'default',
}

const taskStatusColorMap: Record<TaskStatus, string> = {
  PENDING: 'gold',
  RUNNING: 'blue',
  SUCCESS: 'green',
  STOPPED: 'orange',
  FAILED: 'red',
}

export function isTerminalNodeStatus(status: NodeStatus) {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'SKIPPED'
}

export function getNodeNoticeType(status: NodeStatus) {
  if (status === 'FAILED') return 'error' as const
  if (status === 'SKIPPED' || status === 'PAUSED') return 'warning' as const
  return 'info' as const
}

export function statusTag(status: NodeStatus) {
  return <Tag color={nodeStatusColorMap[status]}>{getNodeStatusText(status)}</Tag>
}

export function taskStatusTag(status: TaskStatus) {
  return <Tag color={taskStatusColorMap[status]}>{getTaskStatusText(status)}</Tag>
}

export function pretty(value?: string | null) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

export function formatScore(value: unknown) {
  if (typeof value !== 'number') return null
  return value.toFixed(3)
}

export function formatDurationMs(value: unknown) {
  if (typeof value !== 'number' || Number.isNaN(value)) return null
  if (value >= 1000) {
    return `${(value / 1000).toFixed(value % 1000 === 0 ? 0 : 1)} 秒`
  }
  return `${value} ms`
}

export function isPresent(value: unknown) {
  return value !== null && value !== undefined && value !== ''
}

export function displayValue(value: unknown) {
  if (value == null || value === '') return '未提供'
  if (Array.isArray(value)) {
    const readable = value.map((item) => (typeof item === 'string' ? item : JSON.stringify(item))).filter(Boolean)
    return readable.length ? readable.join('、') : '未提供'
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  return String(value)
}

export function stepStatusTag(status?: string) {
  if (status === 'SUCCESS') return <Tag color="green">已完成</Tag>
  if (status === 'RUNNING') return <Tag color="blue">执行中</Tag>
  if (status === 'SKIPPED') return <Tag color="gold">已跳过</Tag>
  if (status === 'FAILED') return <Tag color="red">失败</Tag>
  return <Tag>待执行</Tag>
}

export function progressStatusTag(status?: string) {
  if (status === 'SUCCESS') return <Tag color="green">已完成</Tag>
  if (status === 'RUNNING') return <Tag color="blue">执行中</Tag>
  if (status === 'SKIPPED') return <Tag color="gold">已跳过</Tag>
  if (status === 'FAILED') return <Tag color="red">失败</Tag>
  if (status === 'DEGRADED') return <Tag color="orange">已降级</Tag>
  return <Tag>{status || '未知状态'}</Tag>
}

export function discoveryMethodTag(method?: string) {
  if (method === 'BROWSER') return <Tag color="cyan">浏览器补源</Tag>
  if (method === 'BROWSER_PREVIEW') return <Tag color="blue">浏览器预览补源</Tag>
  if (method === 'SERP_API') return <Tag color="green">SerpAPI 补源</Tag>
  if (method === 'SEARCH') return <Tag color="geekblue">搜索补源</Tag>
  if (method === 'HEURISTIC') return <Tag color="default">启发式补源</Tag>
  if (method === 'CONFIG') return <Tag color="purple">配置直给</Tag>
  return <Tag>{method || '未知来源'}</Tag>
}

export function supplementMethodTag(method?: string) {
  if (method === 'BROWSER') return <Tag color="cyan">浏览器补源</Tag>
  if (method === 'HTTP_FALLBACK') return <Tag color="gold">HTTP 回退补源</Tag>
  if (method === 'NONE') return <Tag>未触发补源</Tag>
  return <Tag>{method || '未知方式'}</Tag>
}

export function searchModeText(mode?: string) {
  if (mode === 'HYBRID') return '混合模式：优先浏览器补源，必要时回退 HTTP'
  if (mode === 'BROWSER_ONLY') return '仅浏览器模式：只允许浏览器补源'
  if (mode === 'HTTP_ONLY') return '仅 HTTP 模式：跳过浏览器补源'
  if (mode === 'HEURISTIC_ONLY') return '仅启发式模式：只使用规划候选'
  return mode || '未提供'
}

export function candidateStageColor(stage?: string) {
  if (stage === 'SELECTED') return 'green'
  if (stage === 'VERIFIED') return 'blue'
  if (stage === 'BROWSER') return 'cyan'
  if (stage === 'HTTP') return 'gold'
  if (stage === 'PLANNED') return 'default'
  if (stage === 'DISCARDED') return 'red'
  if (stage === 'UNSTAGED') return 'default'
  return 'default'
}

export function stageLabel(stage?: string) {
  if (stage === 'SELECTED') return '已选中'
  if (stage === 'VERIFIED') return '已验证'
  if (stage === 'BROWSER') return '运行期补源'
  if (stage === 'HTTP') return 'HTTP 回退候选'
  if (stage === 'PLANNED') return '规划候选'
  if (stage === 'DISCARDED') return '已淘汰'
  if (stage === 'UNSTAGED') return '未分组候选'
  return stage || '未分组'
}

export function readableAgentType(agentType?: string) {
  if (agentType === 'COLLECTOR') return '信息采集智能体'
  if (agentType === 'EXTRACTOR') return '结构化抽取智能体'
  if (agentType === 'ANALYZER') return '竞品分析智能体'
  if (agentType === 'WRITER') return '报告撰写智能体'
  if (agentType === 'REVIEWER') return '质量评审智能体'
  return agentType || '未知智能体'
}

export function isEvidenceNode(node: TaskNodeInfo) {
  return node.nodeName.startsWith('collect_sources') || node.nodeName === 'extract_schema'
}

export function isReviewNode(node: TaskNodeInfo) {
  return node.agentType === 'REVIEWER'
}

export function actionPriorityColor(priority?: string) {
  if (priority === 'HIGH') return 'red'
  if (priority === 'MEDIUM') return 'orange'
  return 'blue'
}

export function actionTypeText(actionType?: string) {
  if (actionType === 'SUPPLEMENT_EVIDENCE') return '补充证据'
  if (actionType === 'RERUN_NODE') return '重跑节点'
  if (actionType === 'REWRITE_CLAIM') return '改写结论'
  if (actionType === 'MANUAL_REVIEW') return '人工复核'
  return actionType || '后续动作'
}

function parseJson(value?: string | null) {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

export function parseReviewPayload(value?: string | null): ReviewPayload | null {
  const parsed = parseJson(value) as
      | {
          score?: number
          passed?: boolean
          requiresHumanIntervention?: boolean
          autoRewriteAllowed?: boolean
          summary?: string
          issues?: Array<{ type?: string; section?: string; severity?: string; suggestion?: string }>
          revisionPlan?: {
          rewriteRequired?: boolean
          summary?: string
          items?: Array<{ type?: string; section?: string; severity?: string; suggestion?: string }>
          rewriteGuidelines?: string[]
        }
        nextActions?: Array<{
          title?: string
          description?: string
          actionType?: string
          targetNode?: string
          priority?: string
        }>
      }
    | null

  if (!parsed) return null
  return {
    score: typeof parsed.score === 'number' ? parsed.score : null,
    passed: typeof parsed.passed === 'boolean' ? parsed.passed : null,
    requiresHumanIntervention:
      typeof parsed.requiresHumanIntervention === 'boolean' ? parsed.requiresHumanIntervention : null,
    autoRewriteAllowed: typeof parsed.autoRewriteAllowed === 'boolean' ? parsed.autoRewriteAllowed : null,
    summary: parsed.summary || null,
    issues: Array.isArray(parsed.issues) ? parsed.issues : [],
    revisionPlan: parsed.revisionPlan || null,
    nextActions: Array.isArray(parsed.nextActions) ? parsed.nextActions : [],
  }
}

export function isEvidenceRiskIssue(issue: { type?: string; severity?: string; section?: string } | null | undefined) {
  if (!issue) return false
  const type = String(issue.type || '').toLowerCase()
  return type.includes('evidence') || type.includes('claim') || type.includes('证据') || type.includes('支撑')
}

export function getTaskStageLabel(task: TaskInfo, nodes: TaskNodeInfo[]) {
  if (task.status === 'SUCCESS') return '已完成，可查看报告'
  if (nodes.some((node) => node.status === 'PAUSED')) return '等待人工恢复'

  const runningNode = nodes.find((node) => node.status === 'RUNNING')
  if (runningNode?.agentType === 'COLLECTOR') return '采集执行中'
  if (runningNode?.nodeName === 'extract_schema') return '结构化抽取中'
  if (runningNode?.nodeName === 'analyze_competitors') return '竞品分析中'
  if (runningNode?.nodeName === 'write_report' || runningNode?.nodeName === 'rewrite_report') return '报告生成中'
  if (runningNode?.nodeName === 'quality_check' || runningNode?.nodeName === 'quality_check_final') return '质量评审中'

  if (task.status === 'FAILED') return '执行失败，等待修复'
  if (task.status === 'STOPPED') return '已停止，等待恢复或重跑'
  if (task.status === 'RUNNING') return '任务执行中'
  return '等待启动'
}

export function getTaskHeroTone(task: TaskInfo, nodes: TaskNodeInfo[]): HeroTone {
  if (task.status === 'FAILED') return 'danger'
  if (task.status === 'SUCCESS') return 'success'
  if (nodes.some((node) => node.status === 'PAUSED')) return 'warning'
  if (task.status === 'RUNNING') return 'active'
  return 'default'
}

export function getNodeDurationText(node: TaskNodeInfo) {
  if (!node.startedAt) return '未开始'
  const startedAt = dayjs(node.startedAt)
  const endedAt = node.completedAt ? dayjs(node.completedAt) : dayjs()
  const durationMs = Math.max(endedAt.diff(startedAt, 'millisecond'), 0)
  if (durationMs < 1000) return `${durationMs} ms`
  if (durationMs < 60_000) return `${(durationMs / 1000).toFixed(durationMs >= 10_000 ? 0 : 1)} 秒`
  return `${Math.round(durationMs / 60_000)} 分钟`
}

export function getCollectorHeadline(node: TaskNodeInfo) {
  const insight = getCollectorNodeInsight(node)
  if (node.status === 'PAUSED') return '已暂停，等待人工恢复'
  if (node.status === 'FAILED') return node.errorMessage || '采集失败，请检查候选与抓取结果'
  if (node.status === 'RUNNING') {
    const progress = insight.searchProgress
    return progress?.currentStep
      ? `${progress.currentStep} · ${progress.progressPercent ?? 0}%`
      : '正在搜索、补源或抓取页面'
  }
  if (node.outputSummary) return node.outputSummary
  return `候选 ${insight.candidateCount} 条 · 选中 ${insight.selectedCount} 条 · 成功 ${insight.successCollected}/${insight.totalCollected}`
}

export function getReviewerHeadline(node: TaskNodeInfo) {
  const review = parseReviewPayload(node.outputData)
  if (!review) return node.outputSummary || node.errorMessage || '等待评审'
  if (review.passed === false) {
    return `未通过 · ${review.score ?? '-'} / 100 · ${review.issues.length} 个问题`
  }
  if (review.passed === true) {
    return `已通过 · ${review.score ?? '-'} / 100`
  }
  return review.summary || node.outputSummary || '评审结果待生成'
}

export function getNodeHeadline(node: TaskNodeInfo) {
  if (node.agentType === 'COLLECTOR') return getCollectorHeadline(node)
  if (node.agentType === 'REVIEWER') return getReviewerHeadline(node)
  if (node.status === 'PAUSED') return '已暂停，等待人工恢复'
  if (node.interventionReason) return node.interventionReason
  if (node.outputSummary) return node.outputSummary
  if (node.errorMessage) return node.errorMessage
  if (node.configSummaryData?.summaryText) return node.configSummaryData.summaryText
  if (node.configSummary) return node.configSummary
  return '等待执行'
}
