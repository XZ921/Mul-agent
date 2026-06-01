import type { CollectorNodeInsight } from '../../utils/taskNodeInsights'
import type { TaskNodeInfo } from '../../types'

export type HeroTone = 'default' | 'active' | 'warning' | 'danger' | 'success'

export type ReadableField = {
  label: string
  value: string
}

export type ReviewPayload = {
  score: number | null
  passed: boolean | null
  requiresHumanIntervention?: boolean | null
  autoRewriteAllowed?: boolean | null
  summary: string | null
  issues: Array<{ type?: string; section?: string; severity?: string; suggestion?: string }>
  revisionPlan: {
    rewriteRequired?: boolean
    summary?: string
    items?: Array<{ type?: string; section?: string; severity?: string; suggestion?: string }>
    rewriteGuidelines?: string[]
  } | null
  nextActions: Array<{
    title?: string
    description?: string
    actionType?: string
    targetNode?: string
    priority?: string
  }>
}

export type TaskActionQueueItem = {
  key: string
  tone: 'error' | 'warning' | 'info'
  title: string
  description: string
  nodeId?: number
  actions: Array<{
    key: string
    label: string
    kind?: 'primary' | 'default' | 'danger'
    onClick: () => void
  }>
}

export type CollectorLaneGroup = Array<
  [
    string,
    Array<{
      node: TaskNodeInfo
      insight: CollectorNodeInsight
    }>,
  ]
>
