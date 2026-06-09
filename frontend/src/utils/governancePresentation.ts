export interface GovernanceDecisionLike {
  decisionCode?: string | null
  summary?: string | null
  recommendedAction?: string | null
  sourceUrls?: string[] | null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

function readGovernanceDecision(error: unknown): GovernanceDecisionLike | null {
  const root = asRecord(error)
  const response = asRecord(root?.response)
  const data = asRecord(response?.data)
  const detail = asRecord(data?.data)
  if (detail?.governanceBlocked !== true) return null
  const decision = asRecord(detail.governanceDecision)
  if (!decision) return null
  return {
    decisionCode: typeof decision.decisionCode === 'string' ? decision.decisionCode : null,
    summary: typeof decision.summary === 'string' ? decision.summary : null,
    recommendedAction: typeof decision.recommendedAction === 'string' ? decision.recommendedAction : null,
    sourceUrls: Array.isArray(decision.sourceUrls) ? decision.sourceUrls.filter((item): item is string => typeof item === 'string') : [],
  }
}

function decisionReasonText(decision: GovernanceDecisionLike) {
  if (decision.decisionCode === 'CONNECTOR_BUSY') return '连接器正在处理其他任务，当前入口需要等待运行槽位释放。'
  if (decision.decisionCode === 'BLOCKED_QUOTA_EXCEEDED') return '组织配额或并发额度暂时不足，当前操作需要等待释放后再继续。'
  return decision.summary || '当前操作受到组织级治理限制。'
}

function fallbackErrorMessage(error: unknown, fallback: string) {
  const root = asRecord(error)
  const response = asRecord(root?.response)
  const data = asRecord(response?.data)
  if (typeof data?.message === 'string' && data.message.trim()) return data.message
  if (error instanceof Error && error.message.trim()) return error.message
  return fallback
}

/**
 * 将后端 governanceDecision 转换为主路径可读提示。
 * 这里刻意不拼接 quotaKey、blockingOwner、leaseToken 等底层字段，
 * 只展示原因、可重试条件、下一步建议和 sourceUrls，避免用户被内部占位细节干扰。
 */
export function buildGovernanceActionFailureMessage(error: unknown, actionLabel: string, fallback: string) {
  const decision = readGovernanceDecision(error)
  if (!decision) {
    return `${actionLabel}：${fallbackErrorMessage(error, fallback)}`
  }

  const parts = [
    `${actionLabel}：${decisionReasonText(decision)}`,
    `下一步：${decision.recommendedAction || '请稍后重试，或联系操作者查看当前治理运行摘要。'}`,
  ]
  if (decision.sourceUrls?.length) {
    parts.push(`来源：${decision.sourceUrls.slice(0, 2).join('、')}`)
  }
  return parts.join(' ')
}
