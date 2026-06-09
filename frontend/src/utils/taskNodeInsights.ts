import type {
  CollectorSelectedTargetSummary,
  CollectorNodeInsightData,
  CreateTaskRequest,
  SearchExecutionPlanInfo,
  SearchExecutionStepInfo,
  SearchExecutionTraceInfo,
  SearchProgressInfo,
  SourceStrategyLaneInfo,
  SourceStrategyOverviewInfo,
  SelectedTargetInfo,
  SourceCandidateInfo,
  TaskPlanPreviewInfo,
  TaskPlanStageInfo,
  TaskNodeInfo,
} from '../types'

export interface CollectorNodeInsight {
  competitorName: string
  sourceType: string
  sourceTypeLabel?: string | null
  sourceScope: string[]
  competitorUrls: string[]
  searchMode?: string
  searchModeLabel?: string | null
  searchQueries: string[]
  browserSearchEnabled: boolean
  verifyResultPage: boolean
  minVerifiedCandidates: number | null
  preferredDomains: string[]
  candidateCount: number
  selectedCount: number
  successCollected: number
  totalCollected: number
  discoveryNotes?: string
  searchProgress: SearchProgressInfo | null
  searchExecutionPlan: SearchExecutionPlanInfo | null
  searchExecutionTrace?: SearchExecutionTraceInfo | null
  searchProgressSnapshots?: SearchProgressInfo[] | null
  sourceCandidates: SourceCandidateInfo[]
  selectedTargets: SelectedTargetInfo[]
}

function normalizeStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  // 后端增量事件、缓存回放和历史脏数据里，字符串数组经常混入 null / undefined / 对象。
  // 这里仅保留真正可渲染的文本，避免把 null 误转成 "null" 后继续传到页面导致渲染异常。
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter(Boolean)
}

function normalizeObjectArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : []
}

function normalizeRecordArray<T extends object>(value: unknown): T[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter((item): item is T => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
}

function normalizeBoolean(value: unknown) {
  return value === true
}

function normalizeNullableNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function normalizeNumber(value: unknown, fallback = 0) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function normalizeSearchExecutionPlan(value: unknown): SearchExecutionPlanInfo | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const raw = value as SearchExecutionPlanInfo
  return {
    ...raw,
    // 运行中的增量事件或历史快照可能把 steps 写成对象/字符串，这里统一收敛成数组，避免页面渲染期白屏。
    steps: normalizeRecordArray<SearchExecutionStepInfo>(raw.steps),
  }
}

function normalizeSourceCandidate(value: unknown): SourceCandidateInfo | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const raw = value as SourceCandidateInfo
  return {
    ...raw,
    matchedSignals: normalizeStringArray(raw.matchedSignals),
    rankingReasons: normalizeStringArray(raw.rankingReasons),
  }
}

function normalizeSelectedTarget(value: unknown): SelectedTargetInfo | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const target = value as CollectorSelectedTargetSummary
  return {
    url: target.url,
    title: target.title,
    verified: target.verified,
    browserTraceId: target.browserTraceId,
    selectionStage: target.selectionStage,
    selectionReason: target.selectionReason,
    selectionSummary: target.selectionSummary || target.targetSelectionSummary,
    trustTier: target.trustTier,
    trustTierLabel: target.trustTierLabel,
    totalScore: target.totalScore,
    rankingReasons: normalizeStringArray(target.rankingReasons),
    rankingSummary: target.rankingSummary,
    hasPrefetchedPage: normalizeBoolean(target.hasPrefetchedPage),
  }
}

function fromBackendInsight(insight: CollectorNodeInsightData | null | undefined): CollectorNodeInsight | null {
  if (!insight) return null
  return {
    competitorName: insight.competitorName || '未命名竞品',
    sourceType: insight.sourceType || 'OFFICIAL',
    sourceTypeLabel: insight.sourceTypeLabel,
    sourceScope: normalizeStringArray(insight.sourceScope),
    competitorUrls: normalizeStringArray(insight.competitorUrls),
    searchMode: insight.searchMode || undefined,
    searchModeLabel: insight.searchModeLabel,
    searchQueries: normalizeStringArray(insight.searchQueries),
    browserSearchEnabled: normalizeBoolean(insight.browserSearchEnabled),
    verifyResultPage: normalizeBoolean(insight.verifyResultPage),
    minVerifiedCandidates: normalizeNullableNumber(insight.minVerifiedCandidates),
    preferredDomains: normalizeStringArray(insight.preferredDomains),
    candidateCount: normalizeNumber(insight.candidateCount),
    selectedCount: normalizeNumber(insight.selectedCount),
    successCollected: normalizeNumber(insight.successCollected),
    totalCollected: normalizeNumber(insight.totalCollected),
    discoveryNotes: insight.discoveryNotes || undefined,
    searchProgress: insight.searchProgress || null,
    searchExecutionPlan: normalizeSearchExecutionPlan(insight.searchExecutionPlan),
    searchExecutionTrace: insight.searchExecutionTrace || null,
    searchProgressSnapshots: normalizeRecordArray<SearchProgressInfo>(insight.searchProgressSnapshots),
    sourceCandidates: normalizeRecordArray<SourceCandidateInfo>(insight.sourceCandidates)
      .map(normalizeSourceCandidate)
      .filter((candidate): candidate is SourceCandidateInfo => candidate !== null),
    selectedTargets: normalizeRecordArray<CollectorSelectedTargetSummary>(insight.selectedTargets)
      .map(normalizeSelectedTarget)
      .filter((target): target is SelectedTargetInfo => target !== null),
  }
}

export function getCollectorNodeInsight(
  node: Pick<TaskNodeInfo, 'collectorInsight' | 'configSummaryData'>,
): CollectorNodeInsight {
  const backendInsight = fromBackendInsight(node.collectorInsight)
  if (backendInsight) {
    return backendInsight
  }
  const summary = node.configSummaryData

  return {
    competitorName: summary?.competitorName || '未命名竞品',
    sourceType: summary?.sourceType || 'OFFICIAL',
    sourceTypeLabel: summary?.sourceTypeLabel,
    sourceScope: normalizeStringArray(summary?.sourceScope),
    competitorUrls: normalizeStringArray(summary?.competitorUrls),
    searchMode: summary?.searchMode || undefined,
    searchModeLabel: summary?.searchModeLabel,
    searchQueries: [],
    browserSearchEnabled: summary?.browserSearchEnabled === true,
    verifyResultPage: summary?.verificationEnabled === true,
    minVerifiedCandidates: typeof summary?.minVerifiedCandidates === 'number' ? summary.minVerifiedCandidates : null,
    preferredDomains: normalizeStringArray(summary?.preferredDomains),
    candidateCount: summary?.candidateCount ?? 0,
    selectedCount: 0,
    successCollected: 0,
    totalCollected: 0,
    discoveryNotes: summary?.discoveryNotes || undefined,
    searchProgress: null,
    searchExecutionPlan: null,
    searchExecutionTrace: null,
    searchProgressSnapshots: [],
    sourceCandidates: [],
    selectedTargets: [],
  }
}

export function getDependencyNames(dependsOn?: string | null) {
  if (!dependsOn) return []
  try {
    const parsed = JSON.parse(dependsOn)
    return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : []
  } catch {
    return []
  }
}

function uniqueStrings(values: Array<string | null | undefined>) {
  const seen = new Set<string>()
  const result: string[] = []
  values.forEach((value) => {
    if (typeof value !== 'string') {
      return
    }
    const normalized = value.trim()
    if (!normalized || seen.has(normalized)) {
      return
    }
    seen.add(normalized)
    result.push(normalized)
  })
  return result
}

function summarizeItems(items: string[], limit = 3) {
  if (!items.length) {
    return '待补充'
  }
  if (items.length <= limit) {
    return items.join('、')
  }
  return `${items.slice(0, limit).join('、')} 等 ${items.length} 项`
}

function summarizeNodeSummary(nodes: TaskNodeInfo[], fallback: string) {
  const summary = uniqueStrings(
    nodes.map((node) => node.configSummaryData?.summaryText || node.configSummary || node.displayName || node.nodeName),
  )
  return summary[0] || fallback
}

export function buildSourceStrategyOverview(nodes: TaskNodeInfo[]): SourceStrategyOverviewInfo {
  const collectorNodes = nodes.filter((node) => node.agentType === 'COLLECTOR')
  const grouped = new Map<string, SourceStrategyLaneInfo>()

  collectorNodes.forEach((node) => {
    const insight = getCollectorNodeInsight(node)
    const summary = node.configSummaryData
    const competitorName = summary?.competitorName || insight.competitorName || '未命名竞品'
    const lane = grouped.get(competitorName) || {
      competitorName,
      branchCount: 0,
      sourceLabels: [],
      sourceScope: [],
      entryUrlCount: 0,
      candidateCount: 0,
      queryCount: 0,
      browserSupplementEnabled: false,
      verificationEnabled: false,
      minVerifiedCandidates: null,
      preferredDomains: [],
      notes: [],
    }

    lane.branchCount += 1
    // 首屏来源标签优先使用已翻译的用户文案，只有完全缺失时才回退到底层枚举值。
    lane.sourceLabels = uniqueStrings([
      ...lane.sourceLabels,
      summary?.sourceTypeLabel || insight.sourceTypeLabel || insight.sourceType,
    ])
    lane.sourceScope = uniqueStrings([
      ...lane.sourceScope,
      ...normalizeStringArray(summary?.sourceScope),
      ...insight.sourceScope,
    ])
    lane.entryUrlCount += normalizeStringArray(summary?.competitorUrls).length || insight.competitorUrls.length
    lane.candidateCount += summary?.candidateCount ?? insight.candidateCount
    lane.queryCount += summary?.queryCount ?? insight.searchQueries.length
    lane.browserSupplementEnabled = lane.browserSupplementEnabled || (summary?.browserSearchEnabled ?? insight.browserSearchEnabled)
    lane.verificationEnabled = lane.verificationEnabled || (summary?.verificationEnabled ?? insight.verifyResultPage)
    lane.minVerifiedCandidates = Math.max(
      lane.minVerifiedCandidates ?? 0,
      summary?.minVerifiedCandidates ?? insight.minVerifiedCandidates ?? 0,
    ) || null
    lane.preferredDomains = uniqueStrings([
      ...lane.preferredDomains,
      ...normalizeStringArray(summary?.preferredDomains),
      ...insight.preferredDomains,
    ])
    lane.notes = uniqueStrings([
      ...lane.notes,
      summary?.discoveryNotes,
      insight.discoveryNotes,
      node.nodeNotes,
    ])
    grouped.set(competitorName, lane)
  })

  const lanes = Array.from(grouped.values())
  return {
    competitorCount: lanes.length,
    collectorCount: collectorNodes.length,
    browserSupplementCount: lanes.filter((lane) => lane.browserSupplementEnabled).length,
    verificationCount: lanes.filter((lane) => lane.verificationEnabled).length,
    lanes,
  }
}

function buildPlanStages(
  request: Pick<CreateTaskRequest, 'taskName' | 'subjectProduct'>,
  overview: SourceStrategyOverviewInfo,
  nodes: TaskNodeInfo[],
): TaskPlanStageInfo[] {
  const pipelineNodes = nodes.filter((node) => node.agentType !== 'COLLECTOR')
  const extractorNodes = pipelineNodes.filter((node) => node.agentType === 'EXTRACTOR')
  const analyzerNodes = pipelineNodes.filter((node) => node.agentType === 'ANALYZER')
  const writerNodes = pipelineNodes.filter((node) => node.agentType === 'WRITER')
  const reviewerNodes = pipelineNodes.filter((node) => node.agentType === 'REVIEWER')

  const stages: TaskPlanStageInfo[] = [
    {
      key: 'goal',
      title: '明确任务目标',
      summary: request.taskName?.trim() || '待补充分析主题',
      detail: request.subjectProduct?.trim()
        ? `围绕 ${request.subjectProduct.trim()} 展开竞品研究`
        : '补充本方产品后，系统会一起校准研究对象与竞品范围',
    },
    {
      key: 'source-strategy',
      title: '规划来源策略',
      summary: overview.lanes.length
        ? `按 ${overview.competitorCount} 个竞品拆分资料入口，优先覆盖 ${summarizeItems(uniqueStrings(overview.lanes.flatMap((lane) => lane.sourceScope)), 3)}`
        : '先确认官网入口、资料范围与输出要求，再生成可执行的采集方案',
      detail: overview.verificationCount > 0
        ? `${overview.verificationCount} 个竞品分支会在正式采集前做结果页验证`
        : '完成来源规划后会自动进入采集阶段',
    },
  ]

  if (overview.collectorCount > 0) {
    stages.push({
      key: 'collect',
      title: '并行采集资料',
      summary: `${overview.collectorCount} 个采集分支会并行收集官网、文档、定价或公开资料`,
      detail: overview.browserSupplementCount > 0
        ? `${overview.browserSupplementCount} 个竞品分支在入口不足时会自动补充网页检索`
        : '当前方案会优先使用你提供的资料入口与默认来源范围',
    })
  }

  if (extractorNodes.length > 0) {
    stages.push({
      key: 'extract',
      title: '结构化提炼',
      summary: summarizeNodeSummary(extractorNodes, '系统会把采集资料整理成统一结构'),
      detail: `共 ${extractorNodes.length} 个提炼节点承接采集结果`,
    })
  }

  if (analyzerNodes.length > 0) {
    stages.push({
      key: 'analyze',
      title: '汇总分析',
      summary: summarizeNodeSummary(analyzerNodes, '系统会统一汇总各竞品信息并完成对比分析'),
      detail: `共 ${analyzerNodes.length} 个分析节点进入主链路`,
    })
  }

  if (writerNodes.length > 0 || reviewerNodes.length > 0) {
    stages.push({
      key: 'deliver',
      title: '输出与复核',
      summary: writerNodes.length > 0
        ? summarizeNodeSummary(writerNodes, '系统会生成可交付报告')
        : '系统会继续进入交付阶段',
      detail: reviewerNodes.length > 0
        ? `${reviewerNodes.length} 个质量复核节点会检查结论是否可交付`
        : '当前流程不包含额外复核节点',
    })
  }

  return stages
}

export function buildTaskPlanPreview(
  request: Pick<CreateTaskRequest, 'taskName' | 'subjectProduct'>,
  nodes: TaskNodeInfo[],
): TaskPlanPreviewInfo {
  const overview = buildSourceStrategyOverview(nodes)
  const pipelineCount = nodes.filter((node) => node.agentType !== 'COLLECTOR').length

  return {
    competitorCount: overview.competitorCount,
    collectorCount: overview.collectorCount,
    pipelineCount,
    stages: buildPlanStages(request, overview, nodes),
  }
}
