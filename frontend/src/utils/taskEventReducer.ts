import type {
  AgentLog,
  AgentOutputEventPayload,
  CollectorNodeInsightData,
  DiagnosisEventPayload,
  DiagnosisItemInfo,
  DiagnosisSectionInfo,
  NodeStatusEventPayload,
  QualityDiagnosisInfo,
  QualityIssue,
  ReportDiagnosisInfo,
  ReportInfo,
  SearchProgressEventPayload,
  TaskEventConnectionStatus,
  TaskInfo,
  TaskNodeInfo,
  TaskSnapshotEventPayload,
  TaskStatusEventPayload,
  TaskStreamEvent,
} from '../types'
import { normalizeReportDiagnosis } from './reportDiagnosis'

const MAX_RUNTIME_LOGS = 200
const MAX_DIAGNOSIS_ITEMS_PER_SECTION = 50

function normalizeStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map((item) => (typeof item === 'string' ? item.trim() : '')).filter(Boolean)
}

function normalizeTaskRuntimeFields(task: TaskInfo | null): TaskInfo | null {
  if (!task) {
    return null
  }
  return {
    ...task,
    // 任务快照可能来自历史缓存或中断恢复，activeNodeNames 偶尔会被污染成字符串/对象。
    // 这里统一收敛成字符串数组，避免后续 UI 把它当数组消费时再次触发白屏。
    activeNodeNames: normalizeStringArray(task.activeNodeNames),
  }
}

function normalizeTaskNodeRuntimeFields(node: TaskNodeInfo): TaskNodeInfo {
  return {
    ...node,
    // 节点运行态会同时混入首屏快照、SSE 回放和历史脏数据。
    // affectedNodeNames 一旦不是数组，详情抽屉里直接 join 会把整页打崩，所以这里先做统一归一化。
    affectedNodeNames: normalizeStringArray(node.affectedNodeNames),
  }
}

function normalizeLogCreatedAt(value: unknown, fallback: string): string {
  if (typeof value === 'string' && value.trim()) {
    return value
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const parsed = new Date(value)
    if (!Number.isNaN(parsed.getTime())) {
      return parsed.toISOString()
    }
  }
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return value.toISOString()
  }
  return fallback
}

function normalizeAgentLogRuntimeFields(log: AgentLog): AgentLog {
  return {
    ...log,
    // 历史日志既可能来自后端接口，也可能来自运行中 SSE 事件补齐。
    // createdAt 一旦不是可比较的字符串，后续按时间排序就会直接触发 localeCompare 异常。
    createdAt: normalizeLogCreatedAt(log.createdAt, new Date().toISOString()),
  }
}

export interface TaskEventRuntimeState {
  task: TaskInfo | null
  nodes: TaskNodeInfo[]
  logs: AgentLog[]
  report: ReportInfo | null
  streamStatus: TaskEventConnectionStatus
  fallbackPollingActive: boolean
  lastEventCursor: string | null
  lastEventAt: string | null
  lastError: string | null
}

export type TaskEventRuntimeAction =
  | {
      type: 'hydrate'
      task: TaskInfo | null
      nodes: TaskNodeInfo[]
      report: ReportInfo | null
      logs: AgentLog[]
    }
  | {
      type: 'stream-status'
      streamStatus: TaskEventConnectionStatus
      fallbackPollingActive: boolean
      lastError?: string | null
      lastEventCursor?: string | null
      lastEventAt?: string | null
    }
  | {
      type: 'apply-event'
      event: TaskStreamEvent<Record<string, unknown>>
    }

export function createInitialTaskEventRuntimeState(): TaskEventRuntimeState {
  return {
    task: null,
    nodes: [],
    logs: [],
    report: null,
    streamStatus: 'idle',
    fallbackPollingActive: false,
    lastEventCursor: null,
    lastEventAt: null,
    lastError: null,
  }
}

/**
 * 任务详情页的实时状态统一通过这个 reducer 合并。
 * 这样 SSE 增量事件、首次快照拉取和轮询兜底就能共用同一套归并规则，避免组件各自拼接状态。
 */
export function taskEventReducer(
  state: TaskEventRuntimeState,
  action: TaskEventRuntimeAction,
): TaskEventRuntimeState {
  if (action.type === 'hydrate') {
    return {
      ...state,
      task: normalizeTaskRuntimeFields(action.task),
      nodes: action.nodes.map(normalizeTaskNodeRuntimeFields),
      report: action.report
        ? {
            ...action.report,
            // 任务详情既会消费首屏完整报告，也会在运行中持续接 DIAGNOSIS 增量事件。
            // 这里先统一把诊断结构归一化，避免历史脏数据直接把页面渲染打崩。
            reportDiagnosis: normalizeReportDiagnosis(action.report.reportDiagnosis),
          }
        : null,
      logs: action.logs.map(normalizeAgentLogRuntimeFields),
    }
  }

  if (action.type === 'stream-status') {
    return {
      ...state,
      streamStatus: action.streamStatus,
      fallbackPollingActive: action.fallbackPollingActive,
      lastError: action.lastError ?? state.lastError,
      lastEventCursor: action.lastEventCursor ?? state.lastEventCursor,
      lastEventAt: action.lastEventAt ?? state.lastEventAt,
    }
  }

  const nextState = {
    ...state,
    lastEventCursor: action.event.cursor || state.lastEventCursor,
    lastEventAt: action.event.occurredAt || state.lastEventAt,
  }

  switch (action.event.eventType) {
    case 'TASK_SNAPSHOT':
      return applyTaskSnapshotEvent(nextState, action.event.payload as TaskSnapshotEventPayload)
    case 'TASK_STATUS':
      return applyTaskStatusEvent(nextState, action.event.payload as TaskStatusEventPayload)
    case 'NODE_STATUS':
      return applyNodeStatusEvent(nextState, action.event)
    case 'SEARCH_PROGRESS':
      return applySearchProgressEvent(nextState, action.event)
    case 'AGENT_OUTPUT':
      return applyAgentOutputEvent(nextState, action.event)
    case 'DIAGNOSIS':
      return applyDiagnosisEvent(nextState, action.event)
    default:
      return nextState
  }
}

function applyTaskSnapshotEvent(
  state: TaskEventRuntimeState,
  payload: TaskSnapshotEventPayload,
): TaskEventRuntimeState {
  if (!state.task) return state
  return {
    ...state,
    task: {
      ...state.task,
      status: payload.status || state.task.status,
      currentStage: payload.currentStage ?? state.task.currentStage ?? null,
      statusSummary: payload.statusSummary ?? state.task.statusSummary ?? null,
      completedNodes: coalesceNumber(payload.completedNodes, state.task.completedNodes),
      totalNodes: coalesceNumber(payload.totalNodes, state.task.totalNodes),
      waitingRetryNodeCount: coalesceNullableNumber(payload.waitingRetryNodeCount, state.task.waitingRetryNodeCount),
      waitingInterventionNodeCount: coalesceNullableNumber(
        payload.waitingInterventionNodeCount,
        state.task.waitingInterventionNodeCount,
      ),
      compensatedNodeCount: coalesceNullableNumber(payload.compensatedNodeCount, state.task.compensatedNodeCount),
      activeNodeNames: normalizeStringArray(payload.activeNodeNames ?? state.task.activeNodeNames),
      errorMessage: payload.errorMessage ?? state.task.errorMessage,
      snapshotUpdatedAt: payload.updatedAt ?? state.task.snapshotUpdatedAt ?? null,
    },
  }
}

function applyTaskStatusEvent(
  state: TaskEventRuntimeState,
  payload: TaskStatusEventPayload,
): TaskEventRuntimeState {
  if (!state.task) return state
  return {
    ...state,
    task: {
      ...state.task,
      status: payload.status || state.task.status,
      currentStage: payload.currentStage ?? state.task.currentStage ?? null,
      statusSummary: payload.statusSummary ?? state.task.statusSummary ?? null,
      errorMessage: payload.errorMessage ?? state.task.errorMessage,
    },
  }
}

function applyNodeStatusEvent(
  state: TaskEventRuntimeState,
  event: TaskStreamEvent<Record<string, unknown>>,
): TaskEventRuntimeState {
  const payload = event.payload as NodeStatusEventPayload
  const nodeName = event.nodeName || payload.nodeName
  if (!nodeName) return state

  const nodes = updateNodesByName(state.nodes, nodeName, (node) => {
    const nextDisplayName = payload.displayName || node.displayName
    const nextStatus = payload.status || node.status
    const nextControlState = normalizeControlState(payload.controlState, node.controlState)
    const nextErrorMessage = payload.errorMessage ?? node.errorMessage
    const nextFailureCategory = payload.failureCategory ?? node.failureCategory ?? null
    const nextRetryCount = coalesceNumber(payload.retryCount, node.retryCount ?? 0)
    const nextExecutionOrder = coalesceNumber(payload.executionOrder, node.executionOrder)
    const nextStatusSummary = payload.statusSummary ?? node.statusSummary ?? null
    const nextStartedAt = payload.startedAt ?? node.startedAt
    const nextCompletedAt = payload.completedAt ?? node.completedAt
    const nextLastAttemptAt = payload.lastAttemptAt ?? node.lastAttemptAt ?? null
    const nextNextRetryAt = payload.nextRetryAt ?? node.nextRetryAt ?? null
    const nextOutputSummary =
      payload.action === 'NODE_RUNNING'
        ? '节点执行中，等待实时输出...'
        : payload.action === 'NODE_COMPLETED'
          ? node.outputSummary || '节点已完成，可继续查看输出摘要。'
          : node.outputSummary

    if (nextDisplayName === node.displayName
      && nextStatus === node.status
      && nextControlState === node.controlState
      && nextErrorMessage === node.errorMessage
      && nextFailureCategory === (node.failureCategory ?? null)
      && nextRetryCount === (node.retryCount ?? 0)
      && nextExecutionOrder === node.executionOrder
      && nextStatusSummary === (node.statusSummary ?? null)
      && nextStartedAt === node.startedAt
      && nextCompletedAt === node.completedAt
      && nextLastAttemptAt === (node.lastAttemptAt ?? null)
      && nextNextRetryAt === (node.nextRetryAt ?? null)
      && nextOutputSummary === node.outputSummary) {
      return node
    }

    return {
      ...node,
      displayName: nextDisplayName,
      status: nextStatus,
      controlState: nextControlState,
      errorMessage: nextErrorMessage,
      failureCategory: nextFailureCategory,
      retryCount: nextRetryCount,
      executionOrder: nextExecutionOrder,
      statusSummary: nextStatusSummary,
      startedAt: nextStartedAt,
      completedAt: nextCompletedAt,
      lastAttemptAt: nextLastAttemptAt,
      nextRetryAt: nextNextRetryAt,
      outputSummary: nextOutputSummary,
    }
  })

  const task = state.task
    ? {
        ...state.task,
        completedNodes: nodes.filter((node) => isTerminalNodeStatus(node.status)).length,
        activeNodeNames: nodes
          .filter((node) => node.status === 'RUNNING' || node.status === 'PAUSED')
          .map((node) => node.nodeName),
        canViewReport:
          state.task.canViewReport
          || nodes.some(
            (node) =>
              (node.nodeName === 'write_report' || node.nodeName === 'rewrite_report') && node.status === 'SUCCESS',
          ),
      }
    : null

  return {
    ...state,
    task,
    nodes,
  }
}

function applySearchProgressEvent(
  state: TaskEventRuntimeState,
  event: TaskStreamEvent<Record<string, unknown>>,
): TaskEventRuntimeState {
  const payload = event.payload as SearchProgressEventPayload
  const nodeName = event.nodeName || payload.nodeName
  if (!nodeName) return state

  return {
    ...state,
    nodes: updateNodesByName(state.nodes, nodeName, (node) => {
      const collectorInsight = ensureCollectorInsight(node)
      const nextSearchProgress = payload.searchProgress ?? collectorInsight.searchProgress ?? null
      const nextSearchExecutionTrace =
        payload.searchExecutionTrace ?? collectorInsight.searchExecutionTrace ?? null
      const nextSearchAudit = payload.searchAudit ?? collectorInsight.searchAudit ?? null
      const nextAttemptedTargets = payload.attemptedTargets ?? collectorInsight.attemptedTargets ?? []
      const nextDiscardedCandidates = payload.discardedCandidates ?? collectorInsight.discardedCandidates ?? []
      const nextSearchReplayTimeline = payload.replayTimeline ?? collectorInsight.searchReplayTimeline ?? []
      const nextSearchProgressSnapshots =
        payload.searchProgressSnapshots ?? collectorInsight.searchProgressSnapshots ?? []
      const nextSelectedTargets = payload.selectedTargets ?? collectorInsight.selectedTargets ?? []
      const nextSourceUrls = payload.sourceUrls ?? collectorInsight.sourceUrls ?? []
      const nextSelectedCount = nextSelectedTargets.length > 0
        ? nextSelectedTargets.length
        : collectorInsight.selectedCount

      if (nextSearchProgress === collectorInsight.searchProgress
        && nextSearchExecutionTrace === collectorInsight.searchExecutionTrace
        && nextSearchAudit === collectorInsight.searchAudit
        && nextAttemptedTargets === collectorInsight.attemptedTargets
        && nextDiscardedCandidates === collectorInsight.discardedCandidates
        && nextSearchReplayTimeline === collectorInsight.searchReplayTimeline
        && nextSearchProgressSnapshots === collectorInsight.searchProgressSnapshots
        && nextSelectedTargets === collectorInsight.selectedTargets
        && nextSourceUrls === collectorInsight.sourceUrls
        && nextSelectedCount === collectorInsight.selectedCount) {
        return node
      }

      return {
        ...node,
        collectorInsight: {
          ...collectorInsight,
          searchProgress: nextSearchProgress,
          searchExecutionTrace: nextSearchExecutionTrace,
          searchAudit: nextSearchAudit,
          attemptedTargets: nextAttemptedTargets,
          discardedCandidates: nextDiscardedCandidates,
          searchReplayTimeline: nextSearchReplayTimeline,
          searchProgressSnapshots: nextSearchProgressSnapshots,
          selectedTargets: nextSelectedTargets,
          sourceUrls: nextSourceUrls,
          selectedCount: nextSelectedCount,
        },
      }
    }),
  }
}

function applyAgentOutputEvent(
  state: TaskEventRuntimeState,
  event: TaskStreamEvent<Record<string, unknown>>,
): TaskEventRuntimeState {
  const payload = event.payload as AgentOutputEventPayload
  const nodeName = event.nodeName || null
  const syntheticLogId = buildSyntheticLogId(event.cursor)
  const nextLog: AgentLog = {
    id: syntheticLogId,
    taskId: event.taskId,
    nodeId: state.nodes.find((node) => node.nodeName === nodeName)?.id ?? null,
    nodeName,
    agentType: normalizeAgentType(payload.agentType),
    agentName: payload.agentName || 'Agent',
    status: payload.status || 'RUNNING',
    modelName: null,
    durationMs: payload.durationMs ?? null,
    reasoningSummary: payload.reasoningSummary ?? null,
    promptUsed: null,
    inputData: null,
    outputData: payload.outputData ?? null,
    tokenUsage: null,
    errorMessage: payload.errorMessage ?? null,
    traceId: event.cursor || null,
    needsHumanIntervention: payload.status === 'FAILED',
    createdAt: normalizeLogCreatedAt(payload.createdAt, event.occurredAt || new Date().toISOString()),
    eventCursor: event.cursor || null,
  }

  const logs = appendLogIfNeeded(state.logs, nextLog)
  const nodes = !nodeName
    ? state.nodes
    : updateNodesByName(state.nodes, nodeName, (node) => {
        const nextOutputData = payload.outputData ?? node.outputData
        const nextOutputSummary = payload.reasoningSummary ?? node.outputSummary
        const nextErrorMessage = payload.errorMessage ?? node.errorMessage
        if (nextOutputData === node.outputData
          && nextOutputSummary === node.outputSummary
          && nextErrorMessage === node.errorMessage) {
          return node
        }
        const mergedNode: TaskNodeInfo = {
          ...node,
          outputData: nextOutputData,
          outputSummary: nextOutputSummary,
          errorMessage: nextErrorMessage,
        }
        return mergeCollectorOutputIntoNode(mergedNode, payload.outputData)
      })

  return {
    ...state,
    logs,
    nodes,
  }
}

function applyDiagnosisEvent(
  state: TaskEventRuntimeState,
  event: TaskStreamEvent<Record<string, unknown>>,
): TaskEventRuntimeState {
  const payload = event.payload as DiagnosisEventPayload
  const nodeName = event.nodeName || payload.nodeName
  const serializedPayload = JSON.stringify(
    {
      passed: payload.passed ?? false,
      score: payload.score ?? null,
      summary: payload.summary ?? null,
      requiresHumanIntervention: payload.requiresHumanIntervention ?? false,
      diagnoses: payload.diagnoses ?? [],
      issues: payload.issues ?? [],
    },
    null,
    2,
  )

  const nodes = nodeName
    ? updateNodesByName(state.nodes, nodeName, (node) => {
        const nextOutputSummary = payload.summary ?? node.outputSummary
        if (serializedPayload === node.outputData && nextOutputSummary === node.outputSummary) {
          return node
        }
        return {
          ...node,
          outputData: serializedPayload,
          outputSummary: nextOutputSummary,
        }
      })
    : state.nodes

  const diagnosis = buildDiagnosisFromEvent(event)
  const normalizedCurrentDiagnosis = normalizeReportDiagnosis(state.report?.reportDiagnosis)
  const normalizedIncomingDiagnosis = normalizeReportDiagnosis(diagnosis)
  return {
    ...state,
    nodes,
    report: state.report
      ? {
          ...state.report,
          reportDiagnosis: mergeDiagnosisReport(normalizedCurrentDiagnosis, normalizedIncomingDiagnosis),
        }
      : {
          id: -1,
          taskId: event.taskId,
          title: '实时诊断',
          content: '',
          summary: null,
          qualityScore: payload.score ?? null,
          qualityPassed: payload.passed ?? false,
          qualityIssues: payload.issues ?? [],
          initialReview: null,
          revisionPlan: null,
          rewriteApplied: false,
          finalReview: null,
          evidenceCount: 0,
          evidences: [],
          searchAuditOverview: null,
          evidenceCoverageOverview: null,
          competitorKnowledges: [],
          reportDiagnosis: normalizedIncomingDiagnosis,
          createdAt: event.occurredAt || new Date().toISOString(),
          updatedAt: event.occurredAt || new Date().toISOString(),
        },
  }
}

/**
 * 只有部分事件携带了足够的上下文做真正的增量归并。
 * 例如节点完成、诊断刷新后，前端仍建议补拉一次快照，避免遗漏服务端动态聚合字段。
 */
export function shouldRefreshTaskSnapshotAfterEvent(event: TaskStreamEvent<Record<string, unknown>>) {
  return (
    event.eventType === 'DIAGNOSIS'
    || (event.eventType === 'NODE_STATUS'
      && ((event.payload as NodeStatusEventPayload).action === 'NODE_COMPLETED'
        || (event.payload as NodeStatusEventPayload).action === 'NODE_FAILED'))
  )
}

function buildDiagnosisFromEvent(
  event: TaskStreamEvent<Record<string, unknown>>,
): ReportDiagnosisInfo {
  const payload = event.payload as DiagnosisEventPayload
  const issues = payload.issues ?? []
  const diagnoses = payload.diagnoses ?? []
  const evidenceGapCount = issues.filter((issue) => isEvidenceIssue(issue)).length
  const blockerCount = diagnoses.filter((item) => item.level === 'BLOCKER').length
  const sectionMap = new Map<string, DiagnosisSectionInfo>()

  diagnoses.forEach((diagnosis) => {
    const sectionKey = diagnosis.section || 'general'
    const current = sectionMap.get(sectionKey) || {
      section: sectionKey,
      evidenceInsufficient: false,
      sourceUrls: [],
      repairSuggestions: [],
      diagnoses: [],
    }
    current.evidenceInsufficient = current.evidenceInsufficient || isEvidenceIssue(diagnosis)
    current.sourceUrls = uniqueStrings([...(current.sourceUrls || []), ...(diagnosis.sourceUrls || [])])
    current.repairSuggestions = uniqueStrings([
      ...(current.repairSuggestions || []),
      diagnosis.repairSuggestion || null,
    ])
    current.diagnoses = [
      ...current.diagnoses,
      {
        reviewStage: 'INITIAL_REVIEW',
        diagnosis,
        evidenceReferences: [],
      } satisfies DiagnosisItemInfo,
    ].slice(-MAX_DIAGNOSIS_ITEMS_PER_SECTION)
    sectionMap.set(sectionKey, current)
  })

  if (sectionMap.size === 0) {
    const sectionKey = event.nodeName || 'general'
    sectionMap.set(sectionKey, {
      section: sectionKey,
      evidenceInsufficient: evidenceGapCount > 0,
      sourceUrls: uniqueStrings(flatMapSourceUrls(issues)),
      repairSuggestions: uniqueStrings(issues.map((item) => item.suggestion)),
      diagnoses: [],
    })
  }

  return {
    diagnosisCount: diagnoses.length || issues.length,
    blockerCount,
    evidenceGapCount,
    sourceUrls: uniqueStrings([
      ...flatMapSourceUrls(diagnoses),
      ...flatMapSourceUrls(issues),
    ]),
    sections: Array.from(sectionMap.values()),
    nextActions: [],
    contentEvidences: [],
  }
}

function mergeDiagnosisReport(
  current: ReportDiagnosisInfo | null | undefined,
  incoming: ReportDiagnosisInfo | null | undefined,
): ReportDiagnosisInfo {
  const safeIncoming = normalizeReportDiagnosis(incoming)
  if (!safeIncoming) {
    return normalizeReportDiagnosis(current) || {
      diagnosisCount: 0,
      blockerCount: 0,
      evidenceGapCount: 0,
      sourceUrls: [],
      sections: [],
      nextActions: [],
      contentEvidences: [],
    }
  }

  const safeCurrent = normalizeReportDiagnosis(current)
  if (!safeCurrent) {
    return safeIncoming
  }

  const sectionsByKey = new Map<string, DiagnosisSectionInfo>()
  ;[...safeCurrent.sections, ...safeIncoming.sections].forEach((section) => {
    const currentSection = sectionsByKey.get(section.section)
    if (!currentSection) {
      sectionsByKey.set(section.section, section)
      return
    }
    sectionsByKey.set(section.section, {
      ...currentSection,
      evidenceInsufficient: currentSection.evidenceInsufficient || section.evidenceInsufficient,
      sourceUrls: uniqueStrings([...(currentSection.sourceUrls || []), ...(section.sourceUrls || [])]),
      repairSuggestions: uniqueStrings([
        ...(currentSection.repairSuggestions || []),
        ...(section.repairSuggestions || []),
      ]),
      // DIAGNOSIS 事件可能持续回流，这里只保留最近窗口，避免章节明细无限膨胀。
      diagnoses: [...currentSection.diagnoses, ...section.diagnoses].slice(-MAX_DIAGNOSIS_ITEMS_PER_SECTION),
    })
  })

  return {
    ...safeCurrent,
    diagnosisCount: Math.max(safeCurrent.diagnosisCount ?? 0, safeIncoming.diagnosisCount ?? 0),
    blockerCount: Math.max(safeCurrent.blockerCount ?? 0, safeIncoming.blockerCount ?? 0),
    evidenceGapCount: Math.max(safeCurrent.evidenceGapCount ?? 0, safeIncoming.evidenceGapCount ?? 0),
    sourceUrls: uniqueStrings([...(safeCurrent.sourceUrls || []), ...(safeIncoming.sourceUrls || [])]),
    sections: Array.from(sectionsByKey.values()),
    nextActions: safeCurrent.nextActions && safeCurrent.nextActions.length > 0
      ? safeCurrent.nextActions
      : (safeIncoming.nextActions || []),
    contentEvidences: safeCurrent.contentEvidences && safeCurrent.contentEvidences.length > 0
      ? safeCurrent.contentEvidences
      : (safeIncoming.contentEvidences || []),
  }
}

function appendLogIfNeeded(logs: AgentLog[], nextLog: AgentLog) {
  if (logs.some((log) => log.eventCursor && log.eventCursor === nextLog.eventCursor)) {
    return logs
  }
  // 长任务下 AGENT_OUTPUT 事件会非常密集，日志只保留最近窗口，避免内存和排序开销持续增长。
  return [normalizeAgentLogRuntimeFields(nextLog), ...logs.map(normalizeAgentLogRuntimeFields)]
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    .slice(0, MAX_RUNTIME_LOGS)
}

/**
 * 高频 SSE 事件下，如果目标节点不存在，或者 updater 最终没有带来任何字段变化，
 * 就直接复用原始 nodes 数组，避免 DAG、节点列表等所有依赖 nodes 的组件无意义重渲染。
 */
function updateNodesByName(
  nodes: TaskNodeInfo[],
  nodeName: string,
  updater: (node: TaskNodeInfo) => TaskNodeInfo,
) {
  const targetIndex = nodes.findIndex((node) => node.nodeName === nodeName)
  if (targetIndex < 0) {
    return nodes
  }

  const currentNode = nodes[targetIndex]
  const nextNode = updater(currentNode)
  if (nextNode === currentNode) {
    return nodes
  }

  const nextNodes = nodes.slice()
  nextNodes[targetIndex] = nextNode
  return nextNodes
}

function mergeCollectorOutputIntoNode(node: TaskNodeInfo, outputData?: string | null) {
  if (node.agentType !== 'COLLECTOR' || !outputData) {
    return node
  }
  let output: Record<string, unknown> | null = null
  try {
    output = JSON.parse(outputData) as Record<string, unknown>
  } catch {
    return node
  }

  const collectorInsight = ensureCollectorInsight(node)

  return {
    ...node,
    collectorInsight: {
      ...collectorInsight,
      competitorName:
        (typeof output.competitor === 'string' && output.competitor)
        || collectorInsight.competitorName
        || node.configSummaryData?.competitorName
        || '未命名竞品',
      sourceType:
        (typeof output.sourceType === 'string' && output.sourceType)
        || collectorInsight.sourceType
        || node.configSummaryData?.sourceType
        || 'OFFICIAL',
      searchQueries: normalizeArray(output.searchQueries, collectorInsight.searchQueries),
      discoveryNotes:
        (typeof output.discoveryNotes === 'string' && output.discoveryNotes)
        || collectorInsight.discoveryNotes
        || null,
      successCollected: coalesceNumber(output.successCollected, collectorInsight.successCollected),
      totalCollected: coalesceNumber(output.totalCollected, collectorInsight.totalCollected),
      candidateCount: Array.isArray(output.sourceCandidates)
        ? output.sourceCandidates.length
        : collectorInsight.candidateCount,
      selectedCount: Array.isArray(output.selectedTargets)
        ? output.selectedTargets.length
        : collectorInsight.selectedCount,
      sourceCandidates: Array.isArray(output.sourceCandidates)
        ? (output.sourceCandidates as CollectorNodeInsightData['sourceCandidates'])
        : collectorInsight.sourceCandidates,
      selectedTargets: Array.isArray(output.selectedTargets)
        ? (output.selectedTargets as CollectorNodeInsightData['selectedTargets'])
        : collectorInsight.selectedTargets,
      searchProgress:
        (output.searchProgress as CollectorNodeInsightData['searchProgress']) ?? collectorInsight.searchProgress ?? null,
      searchExecutionPlan:
        (output.searchExecutionPlan as CollectorNodeInsightData['searchExecutionPlan']) ?? collectorInsight.searchExecutionPlan ?? null,
      searchExecutionTrace:
        (output.searchExecutionTrace as CollectorNodeInsightData['searchExecutionTrace']) ?? collectorInsight.searchExecutionTrace ?? null,
      searchAudit:
        (output.searchAudit as CollectorNodeInsightData['searchAudit']) ?? collectorInsight.searchAudit ?? null,
      attemptedTargets: Array.isArray(output.attemptedTargets)
        ? normalizeObjectArray<NonNullable<CollectorNodeInsightData['attemptedTargets']>[number]>(output.attemptedTargets)
        : collectorInsight.attemptedTargets,
      discardedCandidates: Array.isArray(output.discardedCandidates)
        ? normalizeObjectArray<NonNullable<CollectorNodeInsightData['discardedCandidates']>[number]>(output.discardedCandidates)
        : collectorInsight.discardedCandidates,
      searchReplayTimeline: Array.isArray(output.searchReplayTimeline)
        ? normalizeObjectArray<NonNullable<CollectorNodeInsightData['searchReplayTimeline']>[number]>(output.searchReplayTimeline)
        : Array.isArray(output.replayTimeline)
          ? normalizeObjectArray<NonNullable<CollectorNodeInsightData['searchReplayTimeline']>[number]>(output.replayTimeline)
          : collectorInsight.searchReplayTimeline,
      searchProgressSnapshots:
        (output.searchProgressSnapshots as CollectorNodeInsightData['searchProgressSnapshots'])
        ?? collectorInsight.searchProgressSnapshots
        ?? [],
      sourceUrls: Array.isArray(output.sourceUrls)
        ? normalizeArray(output.sourceUrls, collectorInsight.sourceUrls ?? [])
        : collectorInsight.sourceUrls,
    },
  }
}

/**
 * SSE 增量事件可能先于完整快照抵达，这里为采集洞察补齐默认结构，保证后续归并始终是稳定对象。
 */
function ensureCollectorInsight(node: TaskNodeInfo): CollectorNodeInsightData {
  const existing = node.collectorInsight
  return {
    competitorName: existing?.competitorName || node.configSummaryData?.competitorName || '未命名竞品',
    sourceType: existing?.sourceType || node.configSummaryData?.sourceType || 'OFFICIAL',
    sourceTypeLabel: existing?.sourceTypeLabel || node.configSummaryData?.sourceTypeLabel || null,
    sourceScope: existing?.sourceScope ?? node.configSummaryData?.sourceScope ?? [],
    competitorUrls: existing?.competitorUrls ?? node.configSummaryData?.competitorUrls ?? [],
    searchMode: existing?.searchMode || node.configSummaryData?.searchMode || undefined,
    searchModeLabel: existing?.searchModeLabel || node.configSummaryData?.searchModeLabel || null,
    searchQueries: existing?.searchQueries ?? [],
    browserSearchEnabled: existing?.browserSearchEnabled ?? Boolean(node.configSummaryData?.browserSearchEnabled),
    verifyResultPage: existing?.verifyResultPage ?? Boolean(node.configSummaryData?.verificationEnabled),
    minVerifiedCandidates: existing?.minVerifiedCandidates ?? node.configSummaryData?.minVerifiedCandidates ?? null,
    preferredDomains: existing?.preferredDomains ?? node.configSummaryData?.preferredDomains ?? [],
    candidateCount: existing?.candidateCount ?? node.configSummaryData?.candidateCount ?? 0,
    selectedCount: existing?.selectedCount ?? 0,
    successCollected: existing?.successCollected ?? 0,
    totalCollected: existing?.totalCollected ?? 0,
    discoveryNotes: existing?.discoveryNotes || node.configSummaryData?.discoveryNotes || null,
    searchProgress: existing?.searchProgress ?? null,
    searchExecutionPlan: existing?.searchExecutionPlan ?? null,
    searchExecutionTrace: existing?.searchExecutionTrace ?? null,
    searchAudit: existing?.searchAudit ?? null,
    attemptedTargets: existing?.attemptedTargets ?? [],
    discardedCandidates: existing?.discardedCandidates ?? [],
    searchReplayTimeline: existing?.searchReplayTimeline ?? [],
    searchProgressSnapshots: existing?.searchProgressSnapshots ?? [],
    sourceCandidates: existing?.sourceCandidates ?? [],
    selectedTargets: existing?.selectedTargets ?? [],
    sourceUrls: existing?.sourceUrls ?? [],
  }
}

function normalizeAgentType(agentType?: string | null): AgentLog['agentType'] {
  if (agentType === 'COLLECTOR'
    || agentType === 'EXTRACTOR'
    || agentType === 'ANALYZER'
    || agentType === 'WRITER'
    || agentType === 'REVIEWER') {
    return agentType
  }
  return 'COLLECTOR'
}

function buildSyntheticLogId(cursor?: string | null) {
  if (!cursor) return Date.now()
  const parts = cursor.split('-')
  const suffix = Number(parts[parts.length - 1])
  return Number.isFinite(suffix) ? -suffix : Date.now()
}

function coalesceNumber(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function coalesceNullableNumber(value: unknown, fallback: number | null | undefined) {
  return typeof value === 'number' && Number.isFinite(value) ? value : (fallback ?? null)
}

function normalizeControlState(
  value: unknown,
  fallback: TaskNodeInfo['controlState'],
): TaskNodeInfo['controlState'] {
  if (value === 'NONE' || value === 'TERMINATE_REQUESTED') {
    return value
  }
  return fallback
}

function normalizeArray(value: unknown, fallback: string[]) {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : fallback
}

function normalizeObjectArray<T extends object>(value: unknown): T[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter((item): item is T => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
}

function uniqueStrings(values: Array<string | null | undefined>) {
  return Array.from(new Set(values.map((value) => value?.trim()).filter(Boolean) as string[]))
}

function flatMapSourceUrls(values: Array<QualityDiagnosisInfo | QualityIssue>) {
  return values.flatMap((item) => item.sourceUrls || [])
}

function isEvidenceIssue(item: { type?: string | null; sourceUrls?: string[] | null; evidenceIds?: string[] | null }) {
  const type = (item.type || '').toLowerCase()
  return (
    type.includes('evidence')
    || type.includes('citation')
    || (item.sourceUrls || []).length > 0
    || (item.evidenceIds || []).length > 0
  )
}

function isTerminalNodeStatus(status: TaskNodeInfo['status']) {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'COMPENSATED' || status === 'SKIPPED'
}
