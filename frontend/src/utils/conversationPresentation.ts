import type {
  ConversationActionConfirmationRequest,
  ConversationIntentDecision,
  ConversationMessageRequest,
  ConversationOrchestrationDecisionSummary,
  ConversationPageType,
  ConversationTaskActionExecutionResult,
  ConversationTaskActionPreview,
} from '../types'

export interface ConversationEntryUrlInput {
  pageType: ConversationPageType
  taskId?: number | null
  reportId?: number | null
  taskName?: string | null
  reportTitle?: string | null
}

export interface ConversationEntryContext {
  pageType: ConversationPageType
  taskId: number | null
  reportId: number | null
  taskName: string | null
  reportTitle: string | null
  contextLabel: string
  contextName: string
  title: string
  description: string
  sampleQuestions: string[]
}

/**
 * 统一把来源页上下文编码成深链，保证任务创建、任务详情和报告页都进入同一个对话入口。
 */
export function buildConversationEntryUrl(input: ConversationEntryUrlInput) {
  const params = new URLSearchParams()
  params.set('pageType', input.pageType)
  if (input.taskId != null) {
    params.set('taskId', String(input.taskId))
  }
  if (input.reportId != null) {
    params.set('reportId', String(input.reportId))
  }
  if (input.taskName?.trim()) {
    params.set('taskName', input.taskName.trim())
  }
  if (input.reportTitle?.trim()) {
    params.set('reportTitle', input.reportTitle.trim())
  }
  return `/conversation?${params.toString()}`
}

/**
 * 对话页只消费少量稳定上下文字段，避免把来源页面的内部状态整包透传进统一入口。
 */
export function readConversationEntryContext(searchParams: URLSearchParams): ConversationEntryContext {
  const rawPageType = searchParams.get('pageType')
  const pageType = normalizeConversationPageType(rawPageType)
  const taskId = parseOptionalNumber(searchParams.get('taskId'))
  const reportId = parseOptionalNumber(searchParams.get('reportId'))
  const taskName = normalizeOptionalText(searchParams.get('taskName'))
  const reportTitle = normalizeOptionalText(searchParams.get('reportTitle'))

  if (pageType === 'TASK_DETAIL') {
    return {
      pageType,
      taskId,
      reportId,
      taskName,
      reportTitle,
      contextLabel: '任务详情上下文',
      contextName: taskName || (taskId == null ? '当前任务' : `任务 #${taskId}`),
      title: '围绕当前任务继续追问',
      description: '这里会优先解释任务卡点、下一步建议与可继续动作，不会把内部事件直接变成首屏术语。',
      sampleQuestions: [
        '这个任务现在卡在哪里？',
        '系统建议我下一步做什么？',
        '如果从某个节点继续，会影响哪些后续步骤？',
      ],
    }
  }

  if (pageType === 'REPORT') {
    return {
      pageType,
      taskId,
      reportId,
      taskName,
      reportTitle,
      contextLabel: '报告阅读上下文',
      contextName: reportTitle || taskName || (reportId == null ? '当前报告' : `报告 #${reportId}`),
      title: '围绕当前报告继续追问',
      description: '这里会优先解释报告结论、修订建议和证据来源，帮助你从阅读直接进入处理。',
      sampleQuestions: [
        '这条结论的证据来自哪里？',
        '当前报告最需要优先修的是什么？',
        '如果补证据，建议先从哪一类来源开始？',
      ],
    }
  }

  return {
    pageType: 'TASK_CREATE',
    taskId,
    reportId,
    taskName,
    reportTitle,
    contextLabel: '任务草稿上下文',
    contextName: taskName || '当前任务草稿',
    title: '围绕任务草稿继续完善',
    description: '这里会优先帮助你补齐任务目标、竞品对象和分析重点，再给出下一步入口。',
    sampleQuestions: [
      '帮我把任务重点放在定价和 AI 能力上。',
      '把竞品范围改成 Notion AI 和 Glean。',
      '我现在还能补哪些关键信息？',
    ],
  }
}

/**
 * 页面层统一在这里组装请求体，避免多个组件各自拼 pageType、taskId 和 reportId。
 */
export function buildConversationMessageRequest(
  context: ConversationEntryContext,
  sessionId: number | null,
  message: string,
): ConversationMessageRequest {
  return {
    sessionId,
    taskId: context.taskId,
    reportId: context.reportId,
    pageType: context.pageType,
    message,
  }
}

export function getConversationDecisionSummary(decision?: ConversationIntentDecision | null) {
  if (!decision) {
    return '系统会结合当前页面上下文，先解释问题，再决定是否需要预览后续动作。'
  }
  if (decision.mode === 'TASK_ACTION' && decision.requiresConfirmation) {
    return '当前会先给你看动作预览、影响范围和确认条件，确认后才会继续执行。'
  }
  if (decision.mode === 'RESEARCH') {
    return decision.requiresConfirmation
      ? '当前会先给出补证建议与来源回指，再由你决定是否继续触发后续动作。'
      : '当前会优先解释证据缺口与补证方向。'
  }
  if (decision.mode === 'TASK_FORM') {
    return '当前会先围绕任务草稿补齐关键信息，再决定下一步。'
  }
  if (decision.mode === 'CLARIFICATION') {
    return '当前上下文还不完整，系统会先向你确认关键问题，避免误执行。'
  }
  if (decision.highRiskAction) {
    return '这次提问涉及高影响动作，系统会先给出预览与确认提示。'
  }
  if (decision.requiresConfirmation) {
    return '这次提问需要先展示预览，再决定是否继续进入后续动作。'
  }
  return '这次提问更偏向解释与澄清，系统会优先回答原因、现状与建议。'
}

export function getConversationRiskText(riskLevel?: string | null) {
  if (riskLevel === 'HIGH') return '高影响'
  if (riskLevel === 'MEDIUM') return '中影响'
  if (riskLevel === 'LOW') return '低影响'
  return '需人工判断'
}

export function getConversationEvidenceStateText(evidenceState?: string | null) {
  if (evidenceState === 'MISSING_SOURCE') return '缺少可回指来源'
  if (evidenceState === 'SUFFICIENT') return '证据充分'
  if (evidenceState === 'INSUFFICIENT') return '证据不足'
  if (evidenceState === 'CONFLICTING') return '证据冲突'
  return evidenceState || '待确认'
}

export function getConversationOrchestrationDecisionText(
  decision?: ConversationOrchestrationDecisionSummary | null,
) {
  if (!decision) {
    return '无最近编排决策摘要'
  }
  const decisionType = (decision.decisionType || '').toUpperCase()
  const actionType = (decision.actionType || '').toUpperCase()
  if (decisionType === 'WAIT_FOR_HUMAN' || actionType === 'MANUAL_REVIEW') {
    return '等待人工介入'
  }
  if (decisionType === 'APPEND_DYNAMIC_BRANCH' || actionType === 'SUPPLEMENT_EVIDENCE') {
    return '建议先补充证据'
  }
  if (
    decisionType === 'REWRITE_ONLY'
    || actionType === 'REWRITE_SECTION'
    || actionType === 'REWRITE_CLAIM'
  ) {
    return '建议重写当前结论'
  }
  if (decisionType === 'NO_ACTION' || actionType === 'NO_ACTION') {
    return '当前无需额外动作'
  }
  return `${decision.decisionType || '编排决策'} / ${decision.actionType || '待确认动作'}`
}

export function getConversationActionHint(preview?: ConversationTaskActionPreview | null) {
  if (!preview) {
    return '当前没有额外动作建议。'
  }
  if (preview.confirmationHint?.trim()) {
    return preview.confirmationHint.trim()
  }
  if (preview.requiresConfirmation) {
    return '系统会先给出预览，再由你确认是否继续。'
  }
  return '当前建议会在统一入口内继续完成说明、确认或执行，不需要再跳回旧入口。'
}

export function getConversationImpactScopeText(impactScope?: string | null) {
  if (impactScope === 'CURRENT_NODE_AND_DOWNSTREAM') return '当前节点及其下游链路'
  if (impactScope === 'TASK_EXECUTION') return '当前任务执行链路'
  if (impactScope === 'TASK_EVIDENCE_CHAIN') return '当前任务的证据与补证链路'
  if (impactScope === 'NONE') return '仅解释，不直接影响任务状态'
  return '需要结合当前上下文人工确认'
}

export function getConversationConfirmationMessage(
  confirmationRequest?: ConversationActionConfirmationRequest | null,
  preview?: ConversationTaskActionPreview | null,
) {
  if (confirmationRequest?.confirmationMessage?.trim()) {
    return confirmationRequest.confirmationMessage.trim()
  }
  return getConversationActionHint(preview)
}

export function getConversationExecutionStatusText(status?: string | null) {
  if (status === 'SUBMITTED') return '已提交执行'
  if (status === 'FAILED') return '执行失败'
  if (status === 'BLOCKED') return '执行被阻断'
  if (status === 'SKIPPED') return '本次未执行'
  return status || '等待执行结果'
}

export function getConversationAuditStatusText(status?: string | null) {
  if (status === 'RECORDED') return '审计已记录'
  if (status === 'PENDING') return '审计待记录'
  if (status === 'FAILED') return '审计记录失败'
  return status || '审计状态待确认'
}

export function getConversationExecutionTone(
  execution?: ConversationTaskActionExecutionResult | null,
) {
  if (execution?.executionStatus === 'SUBMITTED') {
    return 'success' as const
  }
  if (execution?.executionStatus === 'FAILED' || execution?.auditStatus === 'FAILED') {
    return 'error' as const
  }
  return 'info' as const
}

export function getConversationSourceCategoryText(sourceCategory?: string | null) {
  if (sourceCategory === 'DOCS') return '产品文档'
  if (sourceCategory === 'OFFICIAL') return '官网'
  if (sourceCategory === 'PRICING') return '定价页面'
  if (sourceCategory === 'NEWS') return '资讯文章'
  if (sourceCategory === 'REVIEW') return '公开测评'
  return sourceCategory || '来源材料'
}

export function compactConversationUrl(url: string) {
  return url.replace(/^https?:\/\//, '').replace(/\/+$/, '')
}

function normalizeConversationPageType(value: string | null): ConversationPageType {
  if (value === 'TASK_DETAIL' || value === 'REPORT') {
    return value
  }
  return 'TASK_CREATE'
}

function normalizeOptionalText(value: string | null) {
  return value && value.trim() ? value.trim() : null
}

function parseOptionalNumber(value: string | null) {
  if (!value?.trim()) {
    return null
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
