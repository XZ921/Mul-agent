import type {
  SearchAuditSnapshotInfo,
  SearchAuditTargetInfo,
  CollectorNodeInsightData,
  CollectorSelectedTargetSummary,
  CreateTaskRequest,
  SearchReplaySnapshotInfo,
  SearchExecutionPlanInfo,
  SearchExecutionStepInfo,
  SearchExecutionTraceInfo,
  SearchProgressInfo,
  SelectedTargetInfo,
  SourceCandidateInfo,
  SourceStrategyLaneInfo,
  SourceStrategyOverviewInfo,
  TaskNodeConfigSummary,
  TaskNodeInfo,
  TaskPlanPreviewContract,
  TaskPlanPreviewInfo,
  TaskPlanStageInfo,
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
  searchAudit?: SearchAuditSnapshotInfo | null
  searchProgressSnapshots?: SearchProgressInfo[] | null
  sourceCandidates: SourceCandidateInfo[]
  selectedTargets: SelectedTargetInfo[]
  sourceUrls: string[]
}

export interface SearchReplayView {
  searchAudit: SearchAuditSnapshotInfo | null
  selectedTargets: SelectedTargetInfo[]
  sourceUrls: string[]
}

type CollectorNodeLike = {
  agentType: string
  configSummaryData?: TaskNodeConfigSummary | null
  collectorInsight?: CollectorNodeInsightData | null
  nodeNotes?: string | null
}

type SummaryNodeLike = {
  nodeName: string
  displayName?: string
  configSummary?: string | null
  configSummaryData?: TaskNodeConfigSummary | null
}

function normalizeStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter(Boolean)
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
    // 历史事件或脏数据里 steps 可能不是数组，这里统一兜底，避免渲染时报错。
    steps: normalizeRecordArray<SearchExecutionStepInfo>(raw.steps),
  }
}

function normalizeSearchAuditSnapshot(value: unknown): SearchAuditSnapshotInfo | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const raw = value as SearchAuditSnapshotInfo
  return {
    ...raw,
    executionPlan: normalizeSearchExecutionPlan(raw.executionPlan),
    latestProgress: raw.latestProgress || null,
    progressHistory: normalizeRecordArray<SearchProgressInfo>(raw.progressHistory),
    sourceCandidates: normalizeRecordArray<SourceCandidateInfo>(raw.sourceCandidates)
      .map(normalizeSourceCandidate)
      .filter((candidate): candidate is SourceCandidateInfo => candidate !== null),
    selectedTargets: normalizeRecordArray<SearchAuditTargetInfo>(raw.selectedTargets).map((target) => ({
      ...target,
      candidate: normalizeSourceCandidate(target.candidate as unknown),
    })),
    sourceUrls: normalizeStringArray(raw.sourceUrls),
  }
}

export function getNormalizedSearchAudit(
  value: SearchAuditSnapshotInfo | null | undefined,
): SearchAuditSnapshotInfo | null {
  return normalizeSearchAuditSnapshot(value)
}

function normalizeSelectedTargetFromAudit(value: unknown): SelectedTargetInfo | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const target = value as SearchAuditTargetInfo
  const candidate = normalizeSourceCandidate(target.candidate as unknown)
  if (!candidate?.url) {
    return null
  }
  return {
    url: candidate.url,
    title: candidate.title,
    verified: candidate.verified,
    browserTraceId: candidate.browserTraceId,
    selectionStage: candidate.selectionStage,
    selectionReason: candidate.selectionReason,
    selectionSummary: candidate.selectionSummary,
    trustTier: candidate.trustTier,
    trustTierLabel: candidate.trustTierLabel,
    totalScore: candidate.totalScore,
    rankingReasons: normalizeStringArray(candidate.rankingReasons),
    rankingSummary: candidate.rankingSummary,
    hasPrefetchedPage: Boolean(target.collectedPage?.success),
  }
}

export function getSelectedTargetsFromSearchAudit(
  value: SearchAuditSnapshotInfo | null | undefined,
): SelectedTargetInfo[] {
  return normalizeRecordArray<SearchAuditTargetInfo>(normalizeSearchAuditSnapshot(value)?.selectedTargets)
    .map(normalizeSelectedTargetFromAudit)
    .filter((target): target is SelectedTargetInfo => target !== null)
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

export function getSourceCandidatesFromSearchAudit(
  value: SearchAuditSnapshotInfo | null | undefined,
): SourceCandidateInfo[] {
  return normalizeRecordArray<SourceCandidateInfo>(normalizeSearchAuditSnapshot(value)?.sourceCandidates)
    .map(normalizeSourceCandidate)
    .filter((candidate): candidate is SourceCandidateInfo => candidate !== null)
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

/**
 * replay.searchReplays 是任务详情页补齐搜索现场的最后兜底入口。
 * 这里把 replay 中的正式 searchAudit、selectedTargets、sourceUrls 统一归一化，
 * 避免页面层再去关心“字段是来自节点快照、replay 还是 audit 内嵌对象”。
 */
export function getNormalizedSearchReplay(
  replay: SearchReplaySnapshotInfo | null | undefined,
): SearchReplayView | null {
  if (!replay) {
    return null
  }
  const searchAudit = normalizeSearchAuditSnapshot(replay.searchAudit)
  const selectedTargets = normalizeRecordArray<CollectorSelectedTargetSummary>(replay.selectedTargets)
    .map(normalizeSelectedTarget)
    .filter((target): target is SelectedTargetInfo => target !== null)
  return {
    searchAudit,
    selectedTargets: selectedTargets.length > 0
      ? selectedTargets
      : getSelectedTargetsFromSearchAudit(searchAudit),
    sourceUrls: normalizeStringArray(replay.sourceUrls).length > 0
      ? normalizeStringArray(replay.sourceUrls)
      : normalizeStringArray(searchAudit?.sourceUrls),
  }
}

function fromBackendInsight(insight: CollectorNodeInsightData | null | undefined): CollectorNodeInsight | null {
  if (!insight) return null
  const normalizedSearchAudit = normalizeSearchAuditSnapshot(insight.searchAudit)
  const normalizedSourceCandidates = normalizeRecordArray<SourceCandidateInfo>(insight.sourceCandidates)
    .map(normalizeSourceCandidate)
    .filter((candidate): candidate is SourceCandidateInfo => candidate !== null)
  const normalizedSelectedTargets = normalizeRecordArray<CollectorSelectedTargetSummary>(insight.selectedTargets)
    .map(normalizeSelectedTarget)
    .filter((target): target is SelectedTargetInfo => target !== null)
  const normalizedProgressSnapshots = normalizeRecordArray<SearchProgressInfo>(insight.searchProgressSnapshots)
  const normalizedSourceUrls = normalizeStringArray(insight.sourceUrls)

  const sourceCandidates = normalizedSourceCandidates.length > 0
    ? normalizedSourceCandidates
    : (normalizedSearchAudit?.sourceCandidates || [])
  const selectedTargets = normalizedSelectedTargets.length > 0
    ? normalizedSelectedTargets
    : normalizeRecordArray<SearchAuditTargetInfo>(normalizedSearchAudit?.selectedTargets)
      .map(normalizeSelectedTargetFromAudit)
      .filter((target): target is SelectedTargetInfo => target !== null)
  const searchProgressSnapshots = normalizedProgressSnapshots.length > 0
    ? normalizedProgressSnapshots
    : (normalizedSearchAudit?.progressHistory || [])
  const sourceUrls = normalizedSourceUrls.length > 0
    ? normalizedSourceUrls
    : normalizeStringArray(normalizedSearchAudit?.sourceUrls)
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
    candidateCount: sourceCandidates.length > 0 ? sourceCandidates.length : normalizeNumber(insight.candidateCount),
    selectedCount: selectedTargets.length > 0 ? selectedTargets.length : normalizeNumber(insight.selectedCount),
    successCollected: normalizeNumber(insight.successCollected),
    totalCollected: normalizeNumber(insight.totalCollected),
    discoveryNotes: insight.discoveryNotes || undefined,
    searchProgress: insight.searchProgress || normalizedSearchAudit?.latestProgress || null,
    searchExecutionPlan: normalizeSearchExecutionPlan(insight.searchExecutionPlan) || normalizedSearchAudit?.executionPlan || null,
    searchExecutionTrace: insight.searchExecutionTrace || normalizedSearchAudit?.executionTrace || null,
    searchAudit: normalizedSearchAudit,
    searchProgressSnapshots,
    sourceCandidates,
    selectedTargets,
    sourceUrls,
  }
}

export function getCollectorNodeInsight(
  node: Pick<CollectorNodeLike, 'collectorInsight' | 'configSummaryData'>,
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
    searchAudit: null,
    searchProgressSnapshots: [],
    sourceCandidates: [],
    selectedTargets: [],
    sourceUrls: [],
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

function summarizeNodeSummary<T extends SummaryNodeLike>(nodes: T[], fallback: string) {
  const summary = uniqueStrings(
    nodes.map((node) => node.configSummaryData?.summaryText || node.configSummary || node.displayName || node.nodeName),
  )
  return summary[0] || fallback
}

function buildSourceStrategyOverviewFromNodeLikes(nodes: CollectorNodeLike[]): SourceStrategyOverviewInfo {
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

export function buildSourceStrategyOverview(nodes: TaskNodeInfo[]): SourceStrategyOverviewInfo {
  return buildSourceStrategyOverviewFromNodeLikes(nodes)
}

export function buildSourceStrategyOverviewFromPreviewNodes(
  nodes: TaskPlanPreviewContract['nodes'],
): SourceStrategyOverviewInfo {
  return buildSourceStrategyOverviewFromNodeLikes(nodes)
}

export function buildSourceStrategyOverviewFromPreview(
  preview: TaskPlanPreviewContract | null,
): SourceStrategyOverviewInfo {
  if (!preview || !Array.isArray(preview.lanes) || !Array.isArray(preview.nodes)) {
    return {
      competitorCount: 0,
      collectorCount: 0,
      browserSupplementCount: 0,
      verificationCount: 0,
      lanes: [],
    }
  }

  if (preview.lanes.length > 0) {
    return {
      competitorCount: preview.competitorCount,
      collectorCount: preview.collectorCount,
      browserSupplementCount: preview.lanes.filter((lane) => lane.browserSupplementEnabled).length,
      verificationCount: preview.lanes.filter((lane) => lane.verificationEnabled).length,
      lanes: preview.lanes,
    }
  }

  return buildSourceStrategyOverviewFromPreviewNodes(preview.nodes)
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
        ? `围绕 ${request.subjectProduct.trim()} 开展竞品研究`
        : '补充本方产品后，系统会一起校准研究对象与竞品范围',
    },
    {
      key: 'source-strategy',
      title: '规划来源策略',
      summary: overview.lanes.length
        ? `按 ${overview.competitorCount} 个竞品规划资料入口，优先覆盖 ${summarizeItems(uniqueStrings(overview.lanes.flatMap((lane) => lane.sourceScope)), 3)}`
        : '先确认官网入口、资料范围与输出要求，再生成可执行的采集方案',
      detail: overview.verificationCount > 0
        ? `${overview.verificationCount} 个竞品分支会在正式采集前进行结果页验证`
        : '完成来源规划后会自动进入采集阶段',
    },
  ]

  if (overview.collectorCount > 0) {
    stages.push({
      key: 'collect',
      title: '并行采集资料',
      summary: `${overview.collectorCount} 个采集分支会并行收集官网、文档、定价或公开资料`,
      detail: overview.browserSupplementCount > 0
        ? `${overview.browserSupplementCount} 个竞品分支在入口不足时会自动补充网页搜索`
        : '当前方案会优先使用你提供的资料入口与默认来源范围',
    })
  }

  if (extractorNodes.length > 0) {
    stages.push({
      key: 'extract',
      title: '结构化提取',
      summary: summarizeNodeSummary(extractorNodes, '系统会把采集资料整理成统一结构'),
      detail: `共 ${extractorNodes.length} 个提取节点承接采集结果`,
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

export function buildTaskPlanPreviewFromContract(
  preview: TaskPlanPreviewContract | null,
): TaskPlanPreviewInfo {
  if (!preview || !Array.isArray(preview.stages)) {
    return {
      competitorCount: 0,
      collectorCount: 0,
      pipelineCount: 0,
      stages: [],
    }
  }

  return {
    competitorCount: preview.competitorCount,
    collectorCount: preview.collectorCount,
    pipelineCount: preview.pipelineCount,
    stages: preview.stages.map((stage) => ({
      key: stage.key,
      title: stage.title,
      summary: stage.summary,
      detail: stage.detail,
    })),
  }
}
