export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: string
  traceId: string
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export type NodeStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'

export type AgentType = 'COLLECTOR' | 'EXTRACTOR' | 'ANALYZER' | 'WRITER' | 'REVIEWER'

export interface TaskInfo {
  id: number
  taskName: string
  subjectProduct: string
  competitorNames: string
  competitorUrls: string | null
  analysisDimensions: string | null
  status: TaskStatus
  errorMessage: string | null
  totalNodes: number
  completedNodes: number
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export interface TaskNodeInfo {
  id: number
  nodeName: string
  displayName: string
  nodeConfig: string | null
  configSummary?: string | null
  nodeNotes?: string | null
  allowFailedDependency?: boolean
  agentType: AgentType
  dependsOn: string | null
  required: boolean
  retryable?: boolean
  maxRetries?: number
  retryCount?: number
  status: NodeStatus
  errorMessage: string | null
  executionOrder: number
  inputSummary: string | null
  outputSummary: string | null
  inputData?: string | null
  outputData?: string | null
  startedAt: string | null
  completedAt: string | null
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
}

export interface QualityIssue {
  type: string
  section: string
  severity: 'ERROR' | 'WARNING' | 'INFO'
  suggestion: string
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
  summary: string | null
  issues: QualityIssue[]
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
  competitorKnowledges: CompetitorKnowledgeInfo[]
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
