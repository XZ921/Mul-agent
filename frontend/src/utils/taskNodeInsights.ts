import type {
  CollectorNodeInsightData,
  SearchExecutionPlanInfo,
  SearchExecutionTraceInfo,
  SearchProgressInfo,
  SelectedTargetInfo,
  SourceCandidateInfo,
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

function fromBackendInsight(insight: CollectorNodeInsightData | null | undefined): CollectorNodeInsight | null {
  if (!insight) return null
  return {
    competitorName: insight.competitorName || '未命名竞品',
    sourceType: insight.sourceType || 'OFFICIAL',
    sourceTypeLabel: insight.sourceTypeLabel,
    sourceScope: insight.sourceScope || [],
    competitorUrls: insight.competitorUrls || [],
    searchMode: insight.searchMode || undefined,
    searchModeLabel: insight.searchModeLabel,
    searchQueries: insight.searchQueries || [],
    browserSearchEnabled: insight.browserSearchEnabled === true,
    verifyResultPage: insight.verifyResultPage === true,
    minVerifiedCandidates: typeof insight.minVerifiedCandidates === 'number' ? insight.minVerifiedCandidates : null,
    preferredDomains: insight.preferredDomains || [],
    candidateCount: insight.candidateCount ?? 0,
    selectedCount: insight.selectedCount ?? 0,
    successCollected: insight.successCollected ?? 0,
    totalCollected: insight.totalCollected ?? 0,
    discoveryNotes: insight.discoveryNotes || undefined,
    searchProgress: insight.searchProgress || null,
    searchExecutionPlan: insight.searchExecutionPlan || null,
    searchExecutionTrace: insight.searchExecutionTrace || null,
    searchProgressSnapshots: insight.searchProgressSnapshots || [],
    sourceCandidates: insight.sourceCandidates || [],
    selectedTargets: (insight.selectedTargets || []).map((target) => ({
      url: target.url,
      title: target.title,
      verified: target.verified,
      browserTraceId: target.browserTraceId,
      selectionStage: target.selectionStage,
      selectionReason: target.selectionReason,
      hasPrefetchedPage: target.hasPrefetchedPage,
    })),
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
    sourceScope: summary?.sourceScope || [],
    competitorUrls: summary?.competitorUrls || [],
    searchMode: summary?.searchMode || undefined,
    searchModeLabel: summary?.searchModeLabel,
    searchQueries: [],
    browserSearchEnabled: summary?.browserSearchEnabled === true,
    verifyResultPage: summary?.verificationEnabled === true,
    minVerifiedCandidates: typeof summary?.minVerifiedCandidates === 'number' ? summary.minVerifiedCandidates : null,
    preferredDomains: summary?.preferredDomains || [],
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
