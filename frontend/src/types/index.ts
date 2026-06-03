export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: string
  traceId: string
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'STOPPED'

export type NodeStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCESS' | 'FAILED' | 'SKIPPED'

export type AgentType = 'COLLECTOR' | 'EXTRACTOR' | 'ANALYZER' | 'WRITER' | 'REVIEWER'

export interface TaskInfo {
  id: number
  taskName: string
  subjectProduct: string
  competitorNames: string
  competitorUrls: string | null
  analysisDimensions: string | null
  sourceScope: string | null
  status: TaskStatus
  errorMessage: string | null
  totalNodes: number
  completedNodes: number
  createdAt: string
  updatedAt: string
  completedAt: string | null
  canExecute?: boolean
  canResume?: boolean
  canRetry?: boolean
  canStop?: boolean
  canViewReport?: boolean
  interventionSummary?: string | null
}

export interface TaskNodeConfigSummary {
  summaryText?: string | null
  competitorName?: string | null
  sourceType?: string | null
  sourceTypeLabel?: string | null
  searchMode?: string | null
  searchModeLabel?: string | null
  candidateCount?: number | null
  queryCount?: number | null
  stepCount?: number | null
  browserSearchEnabled?: boolean | null
  verificationEnabled?: boolean | null
  minVerifiedCandidates?: number | null
  sourceScope?: string[] | null
  preferredDomains?: string[] | null
  competitorUrls?: string[] | null
  discoveryNotes?: string | null
  mode?: string | null
  reportLanguage?: string | null
  reportTemplate?: string | null
  qualityPolicy?: string | null
  sourceNode?: string | null
  competitorCount?: number | null
  dimensionCount?: number | null
  dimensions?: string[] | null
}

export interface CollectorSelectedTargetSummary {
  url: string
  title?: string
  verified?: boolean
  browserTraceId?: string
  selectionStage?: string
  selectionReason?: string
  hasPrefetchedPage?: boolean
}

export interface CollectorNodeInsightData {
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
  discoveryNotes?: string | null
  searchProgress: SearchProgressInfo | null
  searchExecutionPlan: SearchExecutionPlanInfo | null
  searchExecutionTrace?: SearchExecutionTraceInfo | null
  searchProgressSnapshots?: SearchProgressInfo[] | null
  sourceCandidates: SourceCandidateInfo[]
  selectedTargets: CollectorSelectedTargetSummary[]
}

export interface TaskNodeInfo {
  id: number
  nodeName: string
  displayName: string
  nodeConfig: string | null
  configSummary?: string | null
  configSummaryData?: TaskNodeConfigSummary | null
  collectorInsight?: CollectorNodeInsightData | null
  nodeNotes?: string | null
  allowFailedDependency?: boolean
  agentType: AgentType
  dependsOn: string | null
  required: boolean
  retryable?: boolean
  maxRetries?: number
  retryCount?: number
  status: NodeStatus
  controlState?: 'NONE' | 'TERMINATE_REQUESTED'
  errorMessage: string | null
  interventionReason?: string | null
  executionOrder: number
  inputSummary: string | null
  outputSummary: string | null
  inputData?: string | null
  outputData?: string | null
  startedAt: string | null
  completedAt: string | null
  canRerun?: boolean
  canUpdateConfigAndRerun?: boolean
  affectedNodeCount?: number
  affectedNodeNames?: string[]
  canReuseCheckpoint?: boolean
  canPause?: boolean
  canResumeNode?: boolean
  canSkip?: boolean
  canTerminate?: boolean
  interventionSummary?: string | null
}

export interface SourceCandidateInfo {
  url: string
  title?: string
  sourceType?: string
  discoveryMethod?: string
  reason?: string
  domain?: string
  publishedAt?: string
  relevanceScore?: number
  freshnessScore?: number
  qualityScore?: number
  totalScore?: number
  searchQuery?: string
  searchEngine?: string
  resultRank?: number
  browserTraceId?: string
  verified?: boolean
  verificationReason?: string
  matchedSignals?: string[]
  selectionStage?: string
  selectionReason?: string
}

export interface SearchExecutionStepInfo {
  stepCode: string
  goal: string
  expectedDurationMs: number
  dependency: string
  status?: 'PENDING' | 'RUNNING' | 'SKIPPED' | 'SUCCESS' | 'FAILED'
  message?: string | null
  startedAt?: string | null
  completedAt?: string | null
}

export interface SearchExecutionPlanInfo {
  stage?: string
  steps: SearchExecutionStepInfo[]
}

export interface SearchProgressInfo {
  currentStep?: string
  currentStepCode?: string
  completedSteps?: number
  totalSteps?: number
  progressPercent?: number
  status?: 'PENDING' | 'RUNNING' | 'SKIPPED' | 'SUCCESS' | 'FAILED' | 'DEGRADED' | string
  message?: string | null
  degraded?: boolean
  degradationReason?: string | null
  updatedAt?: string | null
}

export interface SearchExecutionTraceInfo {
  traceVersion?: string
  searchMode?: string
  searchQueries?: string[]
  fallbackOrder?: string[]
  plannedCandidateCount?: number
  verifiedCandidateCount?: number
  supplementedCandidateCount?: number
  selectedCandidateCount?: number
  selectedUrls?: string[]
  supplementMethod?: string
  browserSearchEngine?: string
  browserTraceId?: string
  browserExecutedQueries?: string[]
  browserSearchSummary?: string
  providerFallbackUsed?: boolean
  searchTimeoutMillis?: number
  searchElapsedMillis?: number
  circuitBroken?: boolean
  degraded?: boolean
  degradationReason?: string
  browserBlockedReason?: string
  browserBlockedCount?: number
  fallbackDecision?: string
  recoveryCheckpoint?: string
  recoveryAdvice?: string
  resumedFromCheckpoint?: boolean
  checkpointSource?: string
  runtimePolicy?: {
    verifyResultPage?: boolean
    maxRetries?: number
    minIntervalMillis?: number
    maxSearchesPerTask?: number
    pageTimeoutMillis?: number
    maxOpenResultPages?: number
    userAgents?: string[]
    blockedSignals?: string[]
    recoveryHint?: string
  }
  generatedAt?: string
}

export interface SelectedTargetInfo {
  url: string
  title?: string
  verified?: boolean
  browserTraceId?: string
  selectionStage?: string
  selectionReason?: string
  hasPrefetchedPage?: boolean
}

export interface AgentLog {
  id: number
  taskId: number
  nodeId: number | null
  agentType: AgentType
  agentName: string
  status: NodeStatus
  modelName: string | null
  durationMs: number | null
  reasoningSummary: string | null
  promptUsed: string | null
  inputData: string | null
  outputData: string | null
  tokenUsage: string | null
  errorMessage: string | null
  traceId: string | null
  needsHumanIntervention: boolean
  createdAt: string
}

export interface EvidenceInfo {
  evidenceId: string
  title: string
  url: string
  contentSnippet: string | null
  competitorName: string
  collectedAt: string
  sourceType?: string
  discoveryMethod?: string
  sourceDomain?: string
  discoveryReason?: string
  publishedAt?: string
  sourceScore?: number
  verified?: boolean
  verificationReason?: string
  searchQuery?: string
  searchEngine?: string
  resultRank?: number
  browserTraceId?: string
  selectionReason?: string
  selectionStage?: string
  matchedSignals?: string[]
  pageMetadata?: Record<string, unknown>
}

export interface CollectorSearchAuditInfo {
  nodeName: string
  nodeStatus?: NodeStatus | null
  competitorName?: string | null
  sourceType?: string | null
  traceRecorded?: boolean | null
  auditMessage?: string | null
  supplementMethod?: string | null
  resumedFromCheckpoint?: boolean | null
  checkpointSource?: string | null
  degraded?: boolean | null
  degradationReason?: string | null
  providerFallbackUsed?: boolean | null
  fallbackDecision?: string | null
  browserTraceId?: string | null
  browserBlockedReason?: string | null
  browserBlockedCount?: number | null
  recoveryCheckpoint?: string | null
  plannedCandidateCount?: number | null
  verifiedCandidateCount?: number | null
  supplementedCandidateCount?: number | null
  selectedCandidateCount?: number | null
  selectedUrls?: string[]
  errorMessage?: string | null
}

export interface SearchAuditOverviewInfo {
  collectorNodeCount?: number | null
  traceRecordedCount?: number | null
  checkpointRecoveredCount?: number | null
  degradedCount?: number | null
  providerFallbackCount?: number | null
  browserBlockedCount?: number | null
  plannedCandidateCount?: number | null
  verifiedCandidateCount?: number | null
  supplementedCandidateCount?: number | null
  selectedCandidateCount?: number | null
  collectors?: CollectorSearchAuditInfo[]
}

export interface SectionEvidenceCoverageInfo {
  sectionKey: string
  sectionTitle: string
  totalFields: number
  traceableFields: number
  missingEvidenceFields: number
  emptyFields: number
  missingFields: string[]
}

export interface CompetitorEvidenceCoverageInfo {
  competitorName: string
  totalFields: number
  traceableFields: number
  missingEvidenceFields: number
  emptyFields: number
  missingSections: string[]
}

export interface EvidenceCoverageOverviewInfo {
  totalFields: number
  traceableFields: number
  missingEvidenceFields: number
  emptyFields: number
  sections: SectionEvidenceCoverageInfo[]
  competitors: CompetitorEvidenceCoverageInfo[]
}

export interface QualityIssue {
  type: string
  section: string
  severity: 'ERROR' | 'WARNING' | 'INFO'
  level?: 'BLOCKER' | 'MAJOR' | 'MINOR' | string
  dimensionCode?: string | null
  dimensionName?: string | null
  evidenceBasis?: string | null
  evidenceIds?: string[]
  sourceUrls?: string[]
  suggestion: string
}

export interface QualityDimensionInfo {
  code: string
  name: string
  description?: string | null
  evaluationStandard?: string | null
  score?: number | null
  maxScore?: number | null
  status?: string | null
}

export interface QualityDiagnosisInfo {
  dimensionCode?: string | null
  dimensionName?: string | null
  type: string
  section: string
  severity?: 'ERROR' | 'WARNING' | 'INFO' | string
  level?: 'BLOCKER' | 'MAJOR' | 'MINOR' | string
  title?: string | null
  detail?: string | null
  evidenceBasis?: string | null
  evidenceIds?: string[]
  sourceUrls?: string[]
  repairSuggestion?: string | null
}

export interface ReviewNextAction {
  title: string
  description: string
  actionType: 'SUPPLEMENT_EVIDENCE' | 'RERUN_NODE' | 'REWRITE_CLAIM' | 'MANUAL_REVIEW' | string
  targetNode: string | null
  priority: 'HIGH' | 'MEDIUM' | 'LOW' | string
}

export interface RevisionItem {
  type: string
  section: string
  severity: 'ERROR' | 'WARNING' | 'INFO'
  suggestion: string
}

export interface RevisionPlanInfo {
  rewriteRequired: boolean
  summary: string | null
  items: RevisionItem[]
  rewriteGuidelines: string[]
}

export interface ReviewCheckpointInfo {
  nodeName: string
  nodeStatus: NodeStatus
  score: number | null
  passed: boolean | null
  requiresHumanIntervention?: boolean | null
  autoRewriteAllowed?: boolean | null
  summary: string | null
  dimensions?: QualityDimensionInfo[]
  diagnoses?: QualityDiagnosisInfo[]
  issues: QualityIssue[]
  nextActions: ReviewNextAction[]
}

export interface CompetitorKnowledgeInfo {
  competitorName: string
  officialUrl: string | null
  summary: string | null
  positioning: string | null
  targetUsers: string[]
  pricing: Record<string, unknown>
  sourceUrls: string[]
  evidenceCoverage: Record<string, unknown>
}

export interface EvidenceReferenceInfo {
  evidenceId?: string | null
  title?: string | null
  url?: string | null
  competitorName?: string | null
  sourceType?: string | null
  contentSnippet?: string | null
}

export interface ContentEvidenceFragmentInfo {
  stage?: string | null
  competitorName?: string | null
  fieldName?: string | null
  evidenceId?: string | null
  sourceUrl?: string | null
  title?: string | null
  snippet?: string | null
  issueFlags?: string[]
  evidence?: EvidenceReferenceInfo | null
}

export interface DiagnosisItemInfo {
  reviewStage: 'REPORT' | 'INITIAL_REVIEW' | 'FINAL_REVIEW' | string
  diagnosis: QualityDiagnosisInfo
  evidenceReferences: EvidenceReferenceInfo[]
}

export interface DiagnosisSectionInfo {
  section: string
  evidenceInsufficient?: boolean | null
  sourceUrls?: string[]
  repairSuggestions?: string[]
  diagnoses: DiagnosisItemInfo[]
}

export interface ReportDiagnosisInfo {
  diagnosisCount?: number | null
  blockerCount?: number | null
  evidenceGapCount?: number | null
  sourceUrls?: string[]
  contentEvidences?: ContentEvidenceFragmentInfo[]
  sections: DiagnosisSectionInfo[]
  nextActions?: ReviewNextAction[]
}

export interface ReportInfo {
  id: number
  taskId: number
  title: string
  content: string
  summary: string | null
  qualityScore: number | null
  qualityPassed: boolean
  qualityIssues: QualityIssue[]
  initialReview: ReviewCheckpointInfo | null
  revisionPlan: RevisionPlanInfo | null
  rewriteApplied: boolean
  finalReview: ReviewCheckpointInfo | null
  evidenceCount: number
  evidences: EvidenceInfo[]
  searchAuditOverview?: SearchAuditOverviewInfo | null
  evidenceCoverageOverview?: EvidenceCoverageOverviewInfo | null
  competitorKnowledges: CompetitorKnowledgeInfo[]
  reportDiagnosis?: ReportDiagnosisInfo | null
  createdAt: string
  updatedAt: string
}

export interface AnalysisSchema {
  id: number
  name: string
  description: string | null
  dimensions: string
  isPreset: boolean
  createdAt: string
}

export interface CreateTaskRequest {
  taskName: string
  subjectProduct: string
  competitorNames: string[]
  competitorUrls?: string[]
  analysisDimensions?: string[]
  sourceScope?: string[]
  reportLanguage?: string
  reportTemplate?: string
  schemaId?: number
}
