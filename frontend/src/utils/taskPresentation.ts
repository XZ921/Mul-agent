import type { TaskEventConnectionStatus, TaskInfo, TaskListSummary } from '../types'

export interface TaskListOverviewSummary extends TaskListSummary {}

export interface TaskListOverview {
  tone: 'danger' | 'active' | 'success' | 'default'
  badgeText: string
  heroDescription: string
  entryHint: string
  summary: TaskListOverviewSummary
}

export interface TaskDetailEntryGuidance {
  message: string
  description: string
}

export function safeParseStringArray(value: string | string[] | null | undefined) {
  if (Array.isArray(value)) {
    return value.map((item) => item.trim()).filter(Boolean)
  }
  if (!value || !value.trim()) {
    return []
  }
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed)
      ? parsed
          .filter((item): item is string => typeof item === 'string')
          .map((item) => item.trim())
          .filter(Boolean)
      : []
  } catch {
    return []
  }
}

export function formatDiagnosticJson(value: string | object | null | undefined) {
  if (value == null || value === '') return ''
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function getTaskProgressPercent(task: Pick<TaskInfo, 'completedNodes' | 'totalNodes'>) {
  return task.totalNodes ? Math.round((task.completedNodes / task.totalNodes) * 100) : 0
}

export function getRealtimeConnectionLabel(
  status: TaskEventConnectionStatus,
  fallbackPollingActive = false,
) {
  if (fallbackPollingActive || status === 'fallback') {
    return '实时进度通道暂时不可用，当前改为自动刷新'
  }
  if (status === 'open') {
    return '实时进度通道已连接，后续状态会持续同步到页面'
  }
  if (status === 'connecting') {
    return '正在连接实时进度通道'
  }
  if (status === 'closed') {
    return '实时进度通道已关闭'
  }
  return '等待连接实时进度通道'
}

export function getRealtimeConnectionFallbackDetail() {
  return '实时进度通道暂时不可用，系统已切换为自动刷新，并会继续尝试恢复实时同步。'
}

export function getTaskDetailEntryGuidance(context: 'list' | 'report'): TaskDetailEntryGuidance {
  if (context === 'report') {
    return {
      message: '需要实时观察节点推进时，请返回任务详情页',
      description: '任务详情页会持续展示实时进度、搜索活动、日志流和诊断回流；本页仍聚焦报告阅读与修订处理。',
    }
  }

  return {
    message: '需要深入排查时，请进入任务详情页',
    description: '任务详情页会持续展示节点推进、搜索活动和诊断更新；列表页只保留轻量观察与任务入口。',
  }
}

export function buildTaskListOverview(tasksOrSummary: TaskInfo[] | TaskListOverviewSummary): TaskListOverview {
  const summary = Array.isArray(tasksOrSummary)
    ? buildTaskListSummary(tasksOrSummary)
    : tasksOrSummary
  const { failed, running, success } = summary

  if (failed > 0) {
    return {
      tone: 'danger',
      badgeText: '存在待处理任务',
      heroDescription: `当前有 ${failed} 个任务需要优先处理，建议先进入详情页查看阻塞原因和下一步动作。`,
      entryHint: '任务详情页会持续同步实时进度、节点推进和诊断更新；列表页只保留轻量观察与任务入口。',
      summary,
    }
  }

  if (running > 0) {
    return {
      tone: 'active',
      badgeText: '任务推进中',
      heroDescription: `当前有 ${running} 个任务正在推进，建议从详情页持续观察任务阶段和关键问题。`,
      entryHint: '任务详情页会持续同步实时进度、节点推进和诊断更新；列表页只保留轻量观察与任务入口。',
      summary,
    }
  }

  if (success > 0) {
    return {
      tone: 'success',
      badgeText: '已有结果可查看',
      heroDescription: `最近已有 ${success} 个任务完成，可以直接查看报告结论或继续复盘证据。`,
      entryHint: '当你需要回看实时进度、执行依据或诊断结论时，可从这里进入任务详情页或报告页继续处理。',
      summary,
    }
  }

  return {
    tone: 'default',
    badgeText: '当前任务态稳定',
    heroDescription: '从这里创建新任务、查看执行进展，并在需要时进入详情页处理问题。',
    entryHint: '任务详情页会提供实时进度、节点摘要和诊断入口；列表页优先承担统一入口和轻量总览。',
    summary,
  }
}

function buildTaskListSummary(tasks: TaskInfo[]): TaskListOverviewSummary {
  const total = tasks.length
  return {
    total,
    running: tasks.filter((task) => task.status === 'RUNNING').length,
    success: tasks.filter((task) => task.status === 'SUCCESS').length,
    failed: tasks.filter((task) => task.status === 'FAILED').length,
    stopped: tasks.filter((task) => task.status === 'STOPPED').length,
    avgProgress: total
      ? Math.round(tasks.reduce((sum, task) => sum + getTaskProgressPercent(task), 0) / total)
      : 0,
  }
}
