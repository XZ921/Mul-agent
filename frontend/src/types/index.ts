export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: string
  traceId: string
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'STOPPED'

export type NodeStatus =
  | 'PENDING'
  | 'READY'
  | 'DISPATCHED'
  | 'RUNNING'
  | 'WAITING_RETRY'
  | 'WAITING_INTERVENTION'
  | 'COMPENSATED'
  | 'PAUSED'
  | 'SUCCESS'
  | 'FAILED'
  | 'SKIPPED'

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
  statusSummary?: string | null
  totalNodes: number
  completedNodes: number
  waitingRetryNodeCount?: number | null
  waitingInterventionNodeCount?: number | null
  compensatedNodeCount?: number | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
  canExecute?: boolean
  canResume?: boolean
  canRetry?: boolean
  canStop?: boolean
  canViewReport?: boolean
  interventionSummary?: string | null
  resumeAdvice?: string | null
  retryAdvice?: string | null
  replayEntrySummary?: string | null
  currentStage?: string | null
  activeNodeNames?: string[] | null
  snapshotUpdatedAt?: string | null
  eventStreamPath?: string | null
  currentPlanVersionId?: number | null
  currentPlanVersion?: number | null
}

export interface TaskListSummary {
  total: number
  running: number
  success: number
  failed: number
  stopped: number
  avgProgress: number
}

export interface TaskListPageData {
  items: TaskInfo[]
  attentionItems: TaskInfo[]
  summary: TaskListSummary
  pageNum: number
  pageSize: number
  total: number
  totalPages: number
}

export interface TaskNodeConfigSummary {
  summaryText?: string | null
  competitorName?: string | null
  sourceType?: string | null
  sourceFamilyKey?: string | null
  sourceFamilyRole?: string | null
  primaryTools?: string[] | null
  auxiliaryTools?: string[] | null
  queryTemplates?: string[] | null
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
  targetSelectionSummary?: string
  selectionSummary?: string
  trustTier?: string
  trustTierLabel?: string
  totalScore?: number
  rankingReasons?: string[]
  rankingSummary?: string
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
  searchAudit?: SearchAuditSnapshotInfo | null
  attemptedTargets?: SearchAuditTargetInfo[] | null
  discardedCandidates?: SourceCandidateInfo[] | null
  searchReplayTimeline?: SearchReplayTimelineItemInfo[] | null
  searchProgressSnapshots?: SearchProgressInfo[] | null
  sourceCandidates: SourceCandidateInfo[]
  selectedTargets: CollectorSelectedTargetSummary[]
  sourceUrls?: string[] | null
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
  failureCategory?: string | null
  status: NodeStatus
  controlState?: 'NONE' | 'TERMINATE_REQUESTED'
  errorMessage: string | null
  interventionReason?: string | null
  executionOrder: number
  inputSummary: string | null
  outputSummary: string | null
  statusSummary?: string | null
  inputData?: string | null
  outputData?: string | null
  startedAt: string | null
  completedAt: string | null
  lastAttemptAt?: string | null
  nextRetryAt?: string | null
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
  rerunActionSummary?: string | null
  configRerunActionSummary?: string | null
  impactSummary?: string | null
  checkpointSummary?: string | null
  replayEntrySummary?: string | null
  eventKey?: string | null
  planVersionId?: number | null
  planVersion?: number | null
  branchKey?: string | null
  dynamicNode?: boolean | null
  originNodeName?: string | null
}

export type TaskEventType =
  | 'CONNECTED'
  | 'TASK_SNAPSHOT'
  | 'TASK_STATUS'
  | 'NODE_STATUS'
  | 'SEARCH_PROGRESS'
  | 'AGENT_OUTPUT'
  | 'DIAGNOSIS'

export type TaskEventConnectionStatus = 'idle' | 'connecting' | 'open' | 'fallback' | 'closed'

export interface TaskStreamEvent<TPayload = Record<string, unknown>> {
  cursor?: string | null
  taskId: number
  eventType: TaskEventType
  nodeName?: string | null
  occurredAt?: string | null
  payload: TPayload
}

export interface ReplayTimelineEvent {
  eventId: string
  taskId: number
  planVersionId?: number | null
  planVersion?: number | null
  branchKey?: string | null
  nodeName?: string | null
  eventType: string
  summary: string
  occurredAt?: string | null
  sourceUrls: string[]
}

export interface ReplayNodeSummary {
  nodeId: number
  nodeName: string
  displayName: string
  status: string
  planVersionId?: number | null
  branchKey?: string | null
  latestAttemptNo?: number | null
  failureCategory?: string | null
  issueSummary?: string | null
  recoveryHint?: string | null
  checkpointSummary?: string | null
  sourceUrls: string[]
}

export interface TaskRecoveryWindow {
  windowScope: string
  planVersionId?: number | null
  branchKey?: string | null
  boundaryNodeNames: string[]
  replayableEventIds: string[]
  windowStartAt?: string | null
  windowEndAt?: string | null
}

export interface TaskRecoveryReleasePolicy {
  releaseTaskExecutionLock: boolean
  releaseNodeExecutionLocks: boolean
  releaseReason?: string | null
}

export interface TaskRecoveryAuditTrail {
  decisionSource?: string | null
  planVersionId?: number | null
  triggerEventId?: string | null
  latestAttemptId?: number | null
  latestAttemptNo?: number | null
}

export interface TaskRecoveryAdvice {
  recommendedAction: string
  summary: string
  blockingNodeNames: string[]
  recommendedCheckpointId?: number | null
  recommendedCheckpointKey?: string | null
  resumeSupported: boolean
  recoveryWindow?: TaskRecoveryWindow | null
  releasePolicy?: TaskRecoveryReleasePolicy | null
  auditTrail?: TaskRecoveryAuditTrail | null
  sourceUrls: string[]
}

export interface RecoveryCheckpointResponse {
  id: number
  taskId: number
  planVersionId?: number | null
  planVersion?: number | null
  checkpointKey: string
  checkpointType: string
  nodeName: string
  summary: string
  payloadSnapshot?: string | null
  createdAt?: string | null
  sourceUrls: string[]
}

export interface ReplayPlanVersionSummary {
  planVersionId: number
  planVersion?: number | null
  parentPlanId?: number | null
  branchKey?: string | null
  planType?: string | null
  triggerNodeName?: string | null
  active: boolean
  createdAt?: string | null
  sourceUrls: string[]
}

export interface ReplayIntegrationEntryPoint {
  entryKey: string
  readinessStatus: string
  targetTaskKey?: string | null
  summary: string
  sourceUrls: string[]
}

export interface TaskReplayResponse {
  taskId: number
  currentPlanVersionId?: number | null
  timeline: ReplayTimelineEvent[]
  nodeSummaries: ReplayNodeSummary[]
  recoveryAdvice?: TaskRecoveryAdvice | null
  recoveryCheckpoints: RecoveryCheckpointResponse[]
  planVersions: ReplayPlanVersionSummary[]
  searchReplays?: SearchReplaySnapshotInfo[] | null
  integrationEntryPoints?: ReplayIntegrationEntryPoint[]
  sourceUrls: string[]
}

export interface TaskSnapshotEventPayload {
  status?: TaskStatus | null
  currentStage?: string | null
  statusSummary?: string | null
  completedNodes?: number | null
  totalNodes?: number | null
  waitingRetryNodeCount?: number | null
  waitingInterventionNodeCount?: number | null
  compensatedNodeCount?: number | null
  activeNodeNames?: string[] | null
  errorMessage?: string | null
  updatedAt?: string | null
}

export interface TaskStatusEventPayload {
  status?: TaskStatus | null
  currentStage?: string | null
  statusSummary?: string | null
  errorMessage?: string | null
}

export interface NodeStatusEventPayload {
  action?: string | null
  nodeName?: string | null
  displayName?: string | null
  status?: NodeStatus | null
  controlState?: 'NONE' | 'TERMINATE_REQUESTED' | string | null
  errorMessage?: string | null
  failureCategory?: string | null
  retryCount?: number | null
  executionOrder?: number | null
  statusSummary?: string | null
  startedAt?: string | null
  completedAt?: string | null
  lastAttemptAt?: string | null
  nextRetryAt?: string | null
}

export type ConversationPageType = 'TASK_CREATE' | 'TASK_DETAIL' | 'REPORT'

export interface ConversationActionConfirmationRequest {
  actionType?: string | null
  targetType?: string | null
  targetId?: string | null
  confirmationTitle?: string | null
  confirmationMessage?: string | null
  impactScope?: string | null
  impactSummary?: string | null
  riskLevel?: string | null
}

export interface ConversationMessageRequest {
  sessionId?: number | null
  taskId?: number | null
  reportId?: number | null
  pageType: ConversationPageType | string
  message: string
  executeConfirmedAction?: boolean | null
  confirmationRequest?: ConversationActionConfirmationRequest | null
}

export interface ConversationIntentDecision {
  decisionId?: number | null
  mode?: string | null
  intentType?: string | null
  decisionReason?: string | null
  highRiskAction?: boolean | null
  requiresConfirmation?: boolean | null
  riskLevel?: string | null
  impactScope?: string | null
  confirmationRequest?: ConversationActionConfirmationRequest | null
}

export interface ConversationFormDraft {
  draftId?: number | null
  taskName?: string | null
  subjectProduct?: string | null
  competitorNames?: string[]
  analysisDimensions?: string[]
  sourceScope?: string[]
  changeSummary?: string | null
  previewSummary?: string | null
}

export interface ConversationTaskActionPreview {
  actionType?: string | null
  taskId?: number | null
  targetNodeName?: string | null
  title?: string | null
  actionSummary?: string | null
  impactSummary?: string | null
  riskLevel?: string | null
  requiresConfirmation?: boolean | null
  confirmationHint?: string | null
  executable?: boolean | null
  sourceUrls?: string[]
}

export interface ConversationRetrievalEvidence {
  evidenceId?: string | null
  title?: string | null
  snippet?: string | null
  sourceCategory?: string | null
  sourceUrl?: string | null
}

export interface ConversationTaskActionExecutionResult {
  actionType?: string | null
  taskId?: number | null
  targetNodeName?: string | null
  executionStatus?: string | null
  executionMessage?: string | null
  previewDecisionId?: number | null
  auditDecisionId?: number | null
  auditStatus?: string | null
}

export interface ConversationClarificationOption {
  slotName?: string | null
  optionValue?: string | null
  label?: string | null
  description?: string | null
}

export interface ConversationClarificationSummary {
  clarificationType?: string | null
  question?: string | null
  reason?: string | null
  missingSlots?: string[]
  options?: ConversationClarificationOption[]
}

export interface ConversationResponse {
  sessionId?: number | null
  mode?: string | null
  answer: string
  currentStage?: string | null
  statusSummary?: string | null
  taskRagContextSummary?: string | null
  sourceUrls: string[]
  intentDecision?: ConversationIntentDecision | null
  formDraft?: ConversationFormDraft | null
  taskActionPreview?: ConversationTaskActionPreview | null
  taskActionExecution?: ConversationTaskActionExecutionResult | null
  clarification?: ConversationClarificationSummary | null
  retrievalEvidences: ConversationRetrievalEvidence[]
}

export interface SearchProgressEventPayload {
  contractType?: string | null
  nodeName?: string | null
  searchProgress?: SearchProgressInfo | null
  searchExecutionTrace?: SearchExecutionTraceInfo | null
  searchProgressSnapshots?: SearchProgressInfo[] | null
  searchAudit?: SearchAuditSnapshotInfo | null
  attemptedTargets?: SearchAuditTargetInfo[] | null
  discardedCandidates?: SourceCandidateInfo[] | null
  replayTimeline?: SearchReplayTimelineItemInfo[] | null
  selectedTargets?: CollectorSelectedTargetSummary[] | null
  sourceUrls?: string[] | null
}

export interface AgentOutputEventPayload {
  agentType?: AgentType | null
  agentName?: string | null
  status?: NodeStatus | null
  reasoningSummary?: string | null
  outputData?: string | null
  errorMessage?: string | null
  durationMs?: number | null
  createdAt?: string | null
}

export interface DiagnosisEventPayload {
  nodeName?: string | null
  passed?: boolean | null
  score?: number | null
  summary?: string | null
  requiresHumanIntervention?: boolean | null
  diagnoses?: QualityDiagnosisInfo[] | null
  issues?: QualityIssue[] | null
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
  trustTier?: string
  trustTierLabel?: string
  rankingReasons?: string[]
  rankingSummary?: string
  selectionStage?: string
  selectionReason?: string
  selectionSummary?: string
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

export interface SearchAuditTargetInfo {
  candidate?: SourceCandidateInfo | null
  collectedPage?: {
    url?: string | null
    title?: string | null
    content?: string | null
    snippet?: string | null
    competitorName?: string | null
    sourceType?: string | null
    success?: boolean | null
  } | null
}

export interface SearchReplayTimelineItemInfo {
  stepCode?: string | null
  stepName?: string | null
  status?: string | null
  message?: string | null
  completedSteps?: number | null
  totalSteps?: number | null
  progressPercent?: number | null
  candidateCount?: number | null
  attemptedCount?: number | null
  selectedCount?: number | null
  discardedCount?: number | null
  degraded?: boolean | null
  degradationReason?: string | null
  sourceUrls?: string[] | null
  updatedAt?: string | null
}

export interface SearchAuditSnapshotInfo {
  executionTrace?: SearchExecutionTraceInfo | null
  executionPlan?: SearchExecutionPlanInfo | null
  latestProgress?: SearchProgressInfo | null
  progressHistory?: SearchProgressInfo[] | null
  replayTimeline?: SearchReplayTimelineItemInfo[] | null
  sourceCandidates?: SourceCandidateInfo[] | null
  attemptedTargets?: SearchAuditTargetInfo[] | null
  selectedTargets?: SearchAuditTargetInfo[] | null
  discardedCandidates?: SourceCandidateInfo[] | null
  sourceUrls?: string[] | null
}

export interface SelectedTargetInfo {
  url: string
  title?: string
  verified?: boolean
  browserTraceId?: string
  selectionStage?: string
  selectionReason?: string
  selectionSummary?: string
  trustTier?: string
  trustTierLabel?: string
  totalScore?: number
  rankingReasons?: string[]
  rankingSummary?: string
  hasPrefetchedPage?: boolean
}

export interface SearchReplaySnapshotInfo {
  nodeName: string
  planVersionId?: number | null
  planVersion?: number | null
  branchKey?: string | null
  latestProgress?: SearchProgressInfo | null
  timeline?: SearchReplayTimelineItemInfo[] | null
  searchAudit?: SearchAuditSnapshotInfo | null
  attemptedTargets?: SearchAuditTargetInfo[] | null
  discardedCandidates?: SourceCandidateInfo[] | null
  selectedTargets?: SelectedTargetInfo[] | null
  sourceUrls?: string[] | null
}

export interface AgentLog {
  id: number
  taskId: number
  nodeId: number | null
  nodeName?: string | null
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
  eventCursor?: string | null
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

export interface RevisionDirectiveInfo {
  category?: string | null
  actionType?: string | null
  orchestrationAction?: string | null
  priority?: string | null
  targetNode?: string | null
  targetSection?: string | null
  summary?: string | null
  searchFeedback?: string | null
  searchQueries?: string[]
  sourceUrls?: string[]
  expectedOutcome?: string | null
}

export interface RevisionPlanInfo {
  rewriteRequired: boolean
  summary: string | null
  items: RevisionItem[]
  directives?: RevisionDirectiveInfo[]
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

export interface FieldEvidenceDetailInfo {
  fieldName?: string | null
  fieldLabel?: string | null
  coverageStatus?: string | null
  gapComment?: string | null
  evidenceId?: string | null
  sourceUrl?: string | null
  title?: string | null
  snippet?: string | null
  issueFlags?: string[]
  evidence?: EvidenceReferenceInfo | null
}

export interface SectionEvidenceBundleInfo {
  stage?: string | null
  sectionType?: string | null
  sectionKey?: string | null
  sectionTitle?: string | null
  summary?: string | null
  gapSummary?: string | null
  hasGap?: boolean | null
  fieldNames?: string[]
  missingFields?: string[]
  sourceUrls?: string[]
  issueFlags?: string[]
  fields?: FieldEvidenceDetailInfo[]
  evidenceReferences?: EvidenceReferenceInfo[]
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
  revisionDirectives?: RevisionDirectiveInfo[]
}

export interface DeliverySummaryInfo {
  readyForDelivery?: boolean | null
  deliveryStatus?: string | null
  summary?: string | null
  primaryIssue?: string | null
  recommendedAction?: string | null
  blockerCount?: number | null
  evidenceGapCount?: number | null
  sourceUrls?: string[]
}

export interface EvidenceEntryPointInfo {
  summary?: string | null
  sectionKey?: string | null
  sectionTitle?: string | null
  evidenceId?: string | null
  title?: string | null
  url?: string | null
  sourceType?: string | null
  sourceUrls?: string[]
}

export interface AuditSummaryInfo {
  summary?: string | null
  searchAuditSummary?: string | null
  taskRagAuditSummary?: string | null
  sourceUrls?: string[]
}

export interface ReportExportInfo {
  id: number
  taskId: number
  exportVersion: number
  exportFormat: string
  exportStatus: string
  exportSummary?: string | null
  sourceUrls?: string[]
  createdAt: string
}

export interface GovernanceDecisionInfo {
  allowed: boolean
  decisionCode?: string | null
  summary?: string | null
  recommendedAction?: string | null
  organizationKey?: string | null
  quotaScope?: string | null
  quotaKey?: string | null
  requestedUnits?: number | null
  availableUnits?: number | null
  leaseToken?: string | null
  blockingOwner?: string | null
  sourceUrls?: string[]
}

export interface GovernanceQuotaSummaryInfo {
  displayName: string
  status: string
  summary: string
  retryAdvice?: string | null
  limitValue?: number | null
  usedValue?: number | null
  reservedValue?: number | null
  availableValue?: number | null
  sourceUrls?: string[]
}

export interface GovernanceConnectorSummaryInfo {
  displayName: string
  status: string
  summary: string
  retryAdvice?: string | null
  leaseOwner?: string | null
  expiresAt?: string | null
  sourceUrls?: string[]
}

export interface GovernanceRuntimeSummaryInfo {
  organizationKey: string
  summary: string
  quotaSummaries: GovernanceQuotaSummaryInfo[]
  connectorSummaries: GovernanceConnectorSummaryInfo[]
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
  sectionEvidenceBundles?: SectionEvidenceBundleInfo[]
  competitorKnowledges: CompetitorKnowledgeInfo[]
  reportDiagnosis?: ReportDiagnosisInfo | null
  deliverySummary?: DeliverySummaryInfo | null
  evidenceEntryPoint?: EvidenceEntryPointInfo | null
  auditSummary?: AuditSummaryInfo | null
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

export interface KnowledgeDomainInfo {
  id: number
  domainKey: string
  domainName: string
  description?: string | null
  allowedSourceCategories: string[]
  defaultLifecycle: string
  defaultTrustLevel: string
  status: string
}

export interface KnowledgeDocumentInfo {
  id: number
  taskId?: number | null
  evidenceId?: string | null
  documentKey: string
  knowledgeScope: string
  knowledgeDomainId?: number | null
  knowledgeDomainKey?: string | null
  competitorName?: string | null
  sourceType: string
  sourceCategory: string
  discoveryMethod?: string | null
  sourceDomain?: string | null
  sourceLifecycle: string
  trustLevel: string
  connectorKey?: string | null
  title: string
  url: string
  sourceUrls: string[]
  issueFlags: string[]
  consumedTaskIds: number[]
  consumedEvidenceIds: string[]
  traceSummary?: string | null
}

export interface KnowledgeIngestionPayload {
  taskId?: number
  competitorName?: string
  domainKey: string
  sourceCategory: string
  sourceType?: string
  title?: string
  connectorKey?: string
  url?: string
  discoveryMethod?: string
  sourceDomain?: string
  requestedLifecycle?: string
  requestedTrustLevel?: string
  sourceUrls?: string[]
  contentSnippet?: string
  contentText?: string
  summary?: string
}

export interface SourceStrategyLaneInfo {
  competitorName: string
  branchCount: number
  sourceLabels: string[]
  sourceScope: string[]
  entryUrlCount: number
  candidateCount: number
  queryCount: number
  browserSupplementEnabled: boolean
  verificationEnabled: boolean
  minVerifiedCandidates: number | null
  preferredDomains: string[]
  notes: string[]
}

export interface SourceStrategyOverviewInfo {
  competitorCount: number
  collectorCount: number
  browserSupplementCount: number
  verificationCount: number
  lanes: SourceStrategyLaneInfo[]
}

export interface TaskPlanStageInfo {
  key: string
  title: string
  summary: string
  detail?: string
}

export interface TaskPlanPreviewInfo {
  competitorCount: number
  collectorCount: number
  pipelineCount: number
  stages: TaskPlanStageInfo[]
}

export interface TaskPlanPreviewStageContract extends TaskPlanStageInfo {
  stageCode: string
  sourceUrls: string[]
}

export interface TaskPlanPreviewNodeInfo {
  nodeName: string
  displayName: string
  agentType: AgentType
  stageCode: string
  goal: string
  summary: string
  configSummaryData?: TaskNodeConfigSummary | null
  dependsOn: string[]
  required: boolean
  executionOrder: number
  fallbackOrder: string[]
  sourceUrls: string[]
}

export interface TaskPlanPreviewContract {
  contractType: string
  goal: string
  competitorCount: number
  collectorCount: number
  pipelineCount: number
  lanes: SourceStrategyOverviewInfo['lanes']
  stages: TaskPlanPreviewStageContract[]
  nodes: TaskPlanPreviewNodeInfo[]
  sourceUrls: string[]
}
