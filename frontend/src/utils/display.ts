import type {
  AgentType,
  NodeStatus,
  TaskEventConnectionStatus,
  TaskNodeInfo,
  TaskStatus,
} from '../types'
import { getRealtimeConnectionLabel } from './taskPresentation'

const nodeStatusTextMap: Record<NodeStatus, string> = {
  PENDING: '待执行',
  READY: '待调度',
  DISPATCHED: '已派发',
  RUNNING: '执行中',
  WAITING_RETRY: '等待重试',
  WAITING_INTERVENTION: '等待人工处理',
  COMPENSATED: '已补偿',
  PAUSED: '已暂停',
  SUCCESS: '已完成',
  FAILED: '失败',
  SKIPPED: '已跳过',
}

const taskStatusTextMap: Record<TaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCESS: '已完成',
  STOPPED: '已停止',
  FAILED: '失败',
}

const agentTypeTextMap: Record<AgentType, string> = {
  COLLECTOR: '采集智能体',
  EXTRACTOR: '抽取智能体',
  ANALYZER: '分析智能体',
  WRITER: '撰写智能体',
  REVIEWER: '评审智能体',
}

const reviewSeverityTextMap: Record<string, string> = {
  ERROR: '严重',
  WARNING: '警告',
  INFO: '提示',
}

const reviewLevelTextMap: Record<string, string> = {
  BLOCKER: '阻塞',
  MAJOR: '主要问题',
  MINOR: '轻微问题',
}

const reviewTypeTextMap: Record<string, string> = {
  data_accuracy: '数据准确性',
  grammar_error: '语法表达问题',
  completeness: '内容完整性',
  missing_evidence: '证据缺失',
  evidence_missing: '证据缺失',
  unsupported_claim: '结论缺少支撑',
  unsupported_conclusion: '结论缺少支撑',
  claim_without_evidence: '结论缺少支撑',
  citation_issue: '引用问题',
  structure_issue: '结构问题',
  formatting_issue: '格式问题',
  logic_issue: '逻辑问题',
  inconsistency: '内容不一致',
  ambiguity: '表达不清晰',
}

const reviewSectionTextMap: Record<string, string> = {
  general: '通用',
  summary: '摘要',
  executive_summary: '执行摘要',
  overview: '概览',
  introduction: '引言',
  conclusion: '结论',
  recommendation: '建议',
  recommendations: '建议',
  pricing: '定价',
  pricing_comparison: '定价对比',
  feature_comparison: '功能对比',
  positioning: '市场定位',
  positioning_comparison: '定位对比',
  target_users: '目标用户',
  target_user_comparison: '目标用户对比',
  strengths: '优势分析',
  weaknesses: '不足分析',
  opportunities: '机会点',
  risks: '风险点',
  evidence: '证据说明',
}

const fixedNodeNameMap: Record<string, string> = {
  extract_schema: '竞品结构化抽取',
  analyze_competitors: '竞品综合分析',
  write_report: '生成分析报告',
  quality_check: '报告质量初审',
  rewrite_report: '根据评审改写报告',
  quality_check_final: '报告终审复核',
}

const sourceTypeTextMap: Record<string, string> = {
  OFFICIAL: '官网',
  DOCS: '产品文档',
  PRICING: '定价页面',
  NEWS: '博客资讯',
  REVIEW: '公开测评',
}

const reportTextReplacements: Array<[RegExp, string]> = [
  [/\bCompetitor Analysis Report\b/g, '竞品分析报告'],
  [/\bInitial report:\s*/g, '初版报告：'],
  [/\bRevision report:\s*/g, '修订报告：'],
  [/\bExecutive Summary\b/g, '执行摘要'],
  [/\bDetailed Findings\b/g, '详细发现'],
  [/\bKey Findings\b/g, '关键发现'],
  [/\bConclusion\b/g, '结论'],
  [/\bConclusions\b/g, '结论'],
  [/\bRecommendations\b/g, '建议'],
  [/\bRecommendation\b/g, '建议'],
  [/\bOverview\b/g, '概览'],
  [/\bSummary\b/g, '摘要'],
  [/\bIntroduction\b/g, '引言'],
  [/\bBackground\b/g, '背景'],
  [/\bPricing Comparison\b/g, '定价对比'],
  [/\bFeature Comparison\b/g, '功能对比'],
  [/\bTarget User Comparison\b/g, '目标用户对比'],
  [/\bPositioning Comparison\b/g, '定位对比'],
  [/\bStrengths\b/g, '优势'],
  [/\bWeaknesses\b/g, '不足'],
  [/\bOpportunities\b/g, '机会点'],
  [/\bRisks\b/g, '风险点'],
  [/\bEvidence\b/g, '证据'],
]

function toLookupKey(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[\s-]+/g, '_')
}

function prettifyFallbackLabel(value: string) {
  return value
    .replace(/[_-]+/g, ' ')
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

function normalizeText(value: string) {
  let normalized = value
  for (const [pattern, replacement] of reportTextReplacements) {
    normalized = normalized.replace(pattern, replacement)
  }
  return normalized
}

export function getNodeStatusText(status: NodeStatus) {
  return nodeStatusTextMap[status] || status
}

export function getTaskStatusText(status: TaskStatus) {
  return taskStatusTextMap[status] || status
}

export function getAgentTypeText(type: AgentType) {
  return agentTypeTextMap[type] || type
}

export function getSourceTypeText(sourceType?: string | null) {
  if (!sourceType) return '官网'
  return sourceTypeTextMap[sourceType.toUpperCase()] || sourceType
}

export function getReviewSeverityText(severity?: string | null) {
  if (!severity) return '提示'
  return reviewSeverityTextMap[severity] || severity
}

export function getReviewLevelText(level?: string | null) {
  if (!level) return '提示'
  return reviewLevelTextMap[level] || level
}

export function getReviewTypeText(type?: string | null) {
  if (!type) return '问题'
  const key = toLookupKey(type)
  return reviewTypeTextMap[key] || prettifyFallbackLabel(type)
}

export function getReviewSectionText(section?: string | null) {
  if (!section) return '通用'
  const key = toLookupKey(section)
  return reviewSectionTextMap[key] || prettifyFallbackLabel(section)
}

export function getReviewPassedText(passed: boolean | null | undefined) {
  if (passed == null) return '未判定'
  return passed ? '已通过' : '需修订'
}

export function getDiagnosisStageText(stage?: string | null) {
  if (stage === 'INITIAL_REVIEW') return '初审诊断'
  if (stage === 'FINAL_REVIEW') return '终审诊断'
  if (stage === 'REPORT') return '报告回流诊断'
  return stage || '诊断'
}

export function normalizeReportTitle(title: string) {
  return normalizeText(title)
}

export function normalizeReportSummary(summary?: string | null) {
  if (!summary) return summary || ''
  return normalizeText(summary)
}

export function normalizeReportContent(content?: string | null) {
  if (!content) return content || ''
  return normalizeText(content)
}

export function getNodeDisplayName(
  node: Pick<TaskNodeInfo, 'nodeName' | 'displayName' | 'collectorInsight' | 'configSummaryData'>,
) {
  if (fixedNodeNameMap[node.nodeName]) {
    return fixedNodeNameMap[node.nodeName]
  }

  if (node.nodeName.startsWith('collect_sources')) {
    const competitorName = node.collectorInsight?.competitorName || node.configSummaryData?.competitorName || ''
    const sourceType = node.collectorInsight?.sourceTypeLabel
      || node.configSummaryData?.sourceTypeLabel
      || getSourceTypeText(node.collectorInsight?.sourceType || node.configSummaryData?.sourceType || 'OFFICIAL')
    return competitorName ? `${competitorName} - ${sourceType}采集` : `${sourceType}采集`
  }

  return normalizeText(node.displayName || node.nodeName)
}

export function getNodeNameLabel(nodeName: string) {
  if (fixedNodeNameMap[nodeName]) {
    return fixedNodeNameMap[nodeName]
  }
  if (nodeName.startsWith('collect_sources')) {
    return '信息采集节点'
  }
  return nodeName
}

/**
 * 统一输出任务详情页的实时通道文案，避免各组件各自判断连接态。
 */
export function getTaskEventStreamStatusText(
  status: TaskEventConnectionStatus,
  fallbackPollingActive = false,
) {
  return getRealtimeConnectionLabel(status, fallbackPollingActive)
}
