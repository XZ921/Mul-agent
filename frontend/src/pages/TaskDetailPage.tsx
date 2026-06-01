import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Input,
  Modal,
  Row,
  Space,
  Spin,
  Tabs,
  Typography,
  message,
} from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import {
  executeTask,
  getTask,
  getTaskNodes,
  pauseTaskNode,
  resumeTask,
  resumeTaskNode,
  rerunTaskNode,
  retryTask,
  skipTaskNode,
  stopTask,
  terminateTaskNode,
  updateTaskNodeConfigAndRerun,
} from '../api/client'
import AgentLogPanel from '../components/AgentLogPanel'
import DagOverviewBoard from '../components/task-detail/DagOverviewBoard'
import NodeAccordionList from '../components/task-detail/NodeAccordionList'
import NodeTraceDrawer from '../components/task-detail/NodeTraceDrawer'
import SearchActivityPanel from '../components/task-detail/SearchActivityPanel'
import TaskActionQueue from '../components/task-detail/TaskActionQueue'
import TaskConfigPanel from '../components/task-detail/TaskConfigPanel'
import TaskStatusHero from '../components/task-detail/TaskStatusHero'
import {
  displayValue,
  getNodeHeadline,
  getTaskHeroTone,
  getTaskStageLabel,
  isEvidenceRiskIssue,
  isTerminalNodeStatus,
  parseReviewPayload,
  pretty,
  readableAgentType,
  searchModeText,
  statusTag,
  taskStatusTag,
} from '../components/task-detail/shared'
import type { ReadableField, TaskActionQueueItem } from '../components/task-detail/types'
import type {
  SourceCandidateInfo,
  TaskInfo,
  TaskNodeInfo,
} from '../types'
import {
  getNodeDisplayName,
  getNodeNameLabel,
  getNodeStatusText,
  getSourceTypeText,
} from '../utils/display'
import { getCollectorNodeInsight, getDependencyNames } from '../utils/taskNodeInsights'

const { Text } = Typography
const { TextArea } = Input

function parseJsonArray(value: string | null) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function parseJson(value?: string | null) {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

function summarizeStrings(items: string[], limit = 3) {
  const readable = items.map((item) => item?.trim()).filter(Boolean) as string[]
  if (!readable.length) return '未提供'
  const visible = readable.slice(0, limit)
  return readable.length > limit ? `${visible.join('、')} 等 ${readable.length} 项` : visible.join('、')
}

function compactUrl(url: string) {
  return url.replace(/^https?:\/\//, '').replace(/\/+$/, '')
}

function summarizeUrlArray(value: unknown, limit = 2) {
  if (!Array.isArray(value)) return '未提供'
  return summarizeStrings(
    value.map((item) => String(item)).filter(Boolean).map(compactUrl),
    limit,
  )
}

function candidatePreviewLabel(candidate: SourceCandidateInfo) {
  return candidate.title || candidate.domain || (candidate.url ? compactUrl(candidate.url) : '未命名候选')
}

function summarizeMarkdown(raw: string | null | undefined, maxLength = 160) {
  if (!raw) return ''
  const lines = raw
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#') && !line.startsWith('```'))
  const summary = lines[0] || ''
  if (!summary) return ''
  return summary.length > maxLength ? `${summary.slice(0, maxLength)}...` : summary
}

type ConfigSuggestion = {
  key: string
  label: string
  description: string
  patch: Record<string, unknown>
}

function asStringArray(value: unknown) {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : []
}

function uniqueStrings(items: Array<string | null | undefined>) {
  return Array.from(new Set(items.map((item) => (item || '').trim()).filter(Boolean)))
}

function extractDomainsFromUrls(urls: string[]) {
  return uniqueStrings(
    urls.map((url) => {
      try {
        return new URL(url).hostname
      } catch {
        return ''
      }
    }),
  )
}

function mergeConfigPatch(rawConfig: string, patch: Record<string, unknown>) {
  const parsed = parseJson(rawConfig)
  const base = asRecord(parsed) || {}
  return JSON.stringify({ ...base, ...patch }, null, 2)
}

function getConfigSuggestions(node: TaskNodeInfo | null, config: Record<string, unknown> | null): ConfigSuggestion[] {
  if (!node || !config) return []

  if (node.agentType === 'COLLECTOR') {
    const currentScopes = asStringArray(config.sourceScope)
    const officialFirstScopes = uniqueStrings([
      ...currentScopes,
      '官网',
      '产品文档',
      '定价页面',
    ])
    const broaderScopes = uniqueStrings([
      ...currentScopes,
      '官网',
      '产品文档',
      '定价页面',
      '博客资讯',
      '公开测评',
    ])
    const existingUrls = asStringArray(config.competitorUrls)
    const preferredDomains = uniqueStrings([
      ...asStringArray(config.preferredDomains),
      ...extractDomainsFromUrls(existingUrls),
    ])
    return [
      {
        key: 'collector-broaden',
        label: '增强补源广度',
        description: '当官网入口过少或证据不足时，自动把新闻、测评也纳入搜索，并保持混合补源。',
        patch: {
          sourceScope: broaderScopes,
          searchMode: 'HYBRID',
          browserSearchEnabled: true,
          verifyResultPage: true,
          minVerifiedCandidates: Math.max(Number(config.minVerifiedCandidates || 0), 2),
          maxSearchResults: Math.max(Number(config.maxSearchResults || 0), 5),
        },
      },
      {
        key: 'collector-official',
        label: '优先官方材料',
        description: '先集中采官网、文档和定价页，减少第三方噪声，适合补官方证据。',
        patch: {
          sourceScope: officialFirstScopes,
          preferredDomains,
          verifyResultPage: true,
          minVerifiedCandidates: 2,
        },
      },
      {
        key: 'collector-relax',
        label: '放宽入口门槛',
        description: '当入口很难找时，先降低验证门槛拿到更多候选，再人工筛选。',
        patch: {
          searchMode: 'HYBRID',
          browserSearchEnabled: true,
          verifyResultPage: false,
          minVerifiedCandidates: 1,
          maxSearchResults: Math.max(Number(config.maxSearchResults || 0), 6),
        },
      },
    ]
  }

  if (node.agentType === 'WRITER') {
    return [
      {
        key: 'writer-standard',
        label: '标准中文版',
        description: '恢复到默认的中文标准报告，适合正式交付。',
        patch: {
          reportLanguage: '中文',
          reportTemplate: '标准版',
        },
      },
      {
        key: 'writer-compact',
        label: '精简版输出',
        description: '缩短篇幅，保留关键章节，更适合快速复盘。',
        patch: {
          reportTemplate: '精简版',
        },
      },
      {
        key: 'writer-english',
        label: '改成英文输出',
        description: '保留当前链路，只把最终报告语言切成英文。',
        patch: {
          reportLanguage: '英文',
        },
      },
    ]
  }

  if (node.agentType === 'REVIEWER') {
    return [
      {
        key: 'reviewer-final',
        label: '切为终审口径',
        description: '适合在改写完成后做最终复核，避免继续按初审逻辑评审。',
        patch: {
          qualityPolicy: 'final pass after revision',
          sourceNode: 'rewrite_report',
        },
      },
    ]
  }

  return []
}

function extractEvidenceIds(payload: unknown): string[] {
  if (!payload || typeof payload !== 'object') return []
  const record = payload as Record<string, unknown>
  const directIds = Array.isArray(record.evidenceIds) ? record.evidenceIds : []
  const sources = Array.isArray(record.sources) ? record.sources : []
  const documents = Array.isArray(record.documents) ? record.documents : []

  const sourceIds = sources
    .map((item) => (item && typeof item === 'object' ? String((item as { id?: string }).id || '') : ''))
    .filter(Boolean)
  const documentIds = documents
    .map((item) =>
      item && typeof item === 'object' ? String((item as { evidenceId?: string }).evidenceId || '') : '',
    )
    .filter(Boolean)

  return [...directIds.map(String), ...sourceIds, ...documentIds]
}

function buildReadableConfig(node: TaskNodeInfo, config: Record<string, unknown> | null): ReadableField[] {
  const summary = node.configSummaryData

  if (node.agentType === 'REVIEWER') {
    return [
      { label: '评审类型', value: String(summary?.qualityPolicy || config?.qualityPolicy || '标准质量评审') },
      { label: '评审对象', value: String(summary?.sourceNode || config?.sourceNode || '当前报告') },
      { label: '触发条件', value: String(config?.trigger || '无条件执行') },
    ]
  }

  if (node.agentType === 'WRITER') {
    return [
      {
        label: '撰写模式',
        value: String(summary?.mode || config?.mode || 'initial') === 'revision' ? '根据评审修订报告' : '生成初版报告',
      },
      { label: '报告语言', value: String(summary?.reportLanguage || config?.reportLanguage || '中文') },
      { label: '报告模板', value: String(summary?.reportTemplate || config?.reportTemplate || '标准版') },
      { label: '上游评审节点', value: String(summary?.sourceNode || config?.sourceNode || '无') },
    ]
  }

  if (node.agentType === 'COLLECTOR') {
    const insight = getCollectorNodeInsight(node)
    const stepLabels = insight.searchExecutionPlan?.steps?.map((step) => step.goal || step.stepCode) || []
    const candidateLabels = insight.sourceCandidates.map(candidatePreviewLabel)
    return [
      {
        label: '配置摘要',
        value: summary?.summaryText || node.configSummary || `${insight.competitorName} / ${getSourceTypeText(insight.sourceType)}`,
      },
      { label: '竞品名称', value: insight.competitorName },
      { label: '来源类型', value: getSourceTypeText(insight.sourceType) },
      { label: '采集范围', value: summarizeStrings(insight.sourceScope) },
      {
        label: '规划入口',
        value: `${insight.competitorUrls.length} 个${
          insight.competitorUrls.length ? `：${summarizeUrlArray(insight.competitorUrls)}` : ''
        }`,
      },
      {
        label: '候选来源',
        value: `${insight.candidateCount} 条${
          candidateLabels.length ? `：${summarizeStrings(candidateLabels, 2)}` : ''
        }`,
      },
      {
        label: '搜索计划',
        value: stepLabels.length ? `${stepLabels.length} 步：${summarizeStrings(stepLabels, 3)}` : '未配置',
      },
      { label: '搜索模式', value: summary?.searchModeLabel || searchModeText(insight.searchMode) },
      { label: '启用浏览器补源', value: displayValue(insight.browserSearchEnabled) },
      {
        label: '启用结果页验证',
        value: displayValue(summary?.verificationEnabled ?? insight.verifyResultPage),
      },
      {
        label: '最少验证候选数',
        value: displayValue(summary?.minVerifiedCandidates ?? insight.minVerifiedCandidates),
      },
      { label: '优选域名', value: summarizeStrings(insight.preferredDomains) },
      { label: '补源说明', value: displayValue(summary?.discoveryNotes ?? insight.discoveryNotes) },
    ]
  }

  if (node.agentType === 'EXTRACTOR') {
    return [
      { label: '分析维度', value: displayValue(summary?.dimensions ?? config?.dimensions) },
      { label: '模板编号', value: displayValue(config?.schemaId) },
    ]
  }

  if (node.agentType === 'ANALYZER') {
    return [
      { label: '竞品数量', value: displayValue(summary?.competitorCount ?? config?.competitorCount) },
      { label: '分析维度数量', value: displayValue(summary?.dimensionCount ?? config?.dimensionCount) },
    ]
  }

  if (!config) return []
  return Object.entries(config).slice(0, 6).map(([key, value]) => ({
    label: key,
    value: displayValue(value),
  }))
}

function buildReadableInput(node: TaskNodeInfo, input: Record<string, unknown> | null): ReadableField[] {
  if (!input) return []

  return [
    { label: '任务编号', value: displayValue(input.taskId) },
    { label: '任务名称', value: displayValue(input.taskName) },
    { label: '当前节点', value: getNodeNameLabel(String(input.nodeName || node.nodeName)) },
    { label: '智能体', value: readableAgentType(String(input.agentType || node.agentType)) },
    { label: '上游依赖', value: displayValue(input.dependsOn) },
  ]
}

function buildReadableOutput(
  node: TaskNodeInfo,
  output: Record<string, unknown> | null,
  rawOutput?: string | null,
): ReadableField[] {
  if (node.agentType === 'REVIEWER') {
    if (!output) return []
    const issues = Array.isArray(output.issues) ? output.issues : []
    const sections = issues
      .map((item) => asRecord(item)?.section)
      .filter((item): item is string => typeof item === 'string' && item.length > 0)
    const issueTypes = issues
      .map((item) => asRecord(item)?.type)
      .filter((item): item is string => typeof item === 'string' && item.length > 0)

    return [
      { label: '评审阶段', value: displayValue(output.reviewStage) },
      { label: '终审结果', value: output.passed === true ? '已通过' : output.passed === false ? '未通过' : '未判定' },
      { label: '评分', value: output.score == null ? '未提供' : `${output.score}/100` },
      { label: '主要问题', value: issueTypes.length ? issueTypes.join('、') : '无' },
      { label: '影响章节', value: sections.length ? sections.join('、') : '无' },
      { label: '评审摘要', value: displayValue(output.summary) },
    ]
  }

  if (node.agentType === 'WRITER') {
    const rawOutputLength = rawOutput?.length || 0
    const preview = summarizeMarkdown(rawOutput)
    return [
      { label: '产出类型', value: String(node.nodeName === 'rewrite_report' ? '修订后的报告' : '初版报告') },
      { label: '输出状态', value: node.status === 'SUCCESS' ? '报告已生成' : getNodeStatusText(node.status) },
      {
        label: '执行结果',
        value: node.outputSummary || (node.nodeName === 'rewrite_report' ? '已根据初审意见完成改写' : '已完成报告生成'),
      },
      { label: '内容预览', value: preview || '报告正文已生成，可在原始输出中查看完整 Markdown' },
      { label: '正文长度', value: rawOutputLength > 0 ? `${rawOutputLength} 字符` : '已写入报告正文' },
    ]
  }

  if (!output) return []

  if (node.agentType === 'COLLECTOR') {
    const insight = node.collectorInsight
    const trace = insight?.searchExecutionTrace || null
    return [
      { label: '竞品名称', value: displayValue(insight?.competitorName ?? output.competitor) },
      { label: '来源类型', value: displayValue(insight?.sourceTypeLabel ?? output.sourceType) },
      { label: '已采集链接数', value: displayValue(insight?.totalCollected ?? output.totalCollected) },
      { label: '成功采集数', value: displayValue(insight?.successCollected ?? output.successCollected) },
      { label: '补源说明', value: displayValue(insight?.discoveryNotes ?? output.discoveryNotes) },
      { label: '补源方式', value: displayValue(trace?.supplementMethod) },
      { label: '浏览器搜索摘要', value: displayValue(trace?.browserSearchSummary) },
    ]
  }

  if (node.agentType === 'ANALYZER') {
    return [
      { label: '整体概述', value: displayValue(output.overview) },
      { label: '功能对比', value: displayValue(output.featureComparison) },
      { label: '定价对比', value: displayValue(output.pricingComparison) },
      { label: '目标用户对比', value: displayValue(output.targetUserComparison) },
    ]
  }

  if (node.agentType === 'EXTRACTOR') {
    return [
      { label: '输出状态', value: node.outputSummary || '已完成结构化抽取' },
      { label: '证据覆盖', value: '请结合下方证据追踪与原始数据查看字段级来源' },
    ]
  }

  return Object.entries(output).slice(0, 6).map(([key, value]) => ({
    label: key,
    value: displayValue(value),
  }))
}

export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>()
  const location = useLocation()
  const navigate = useNavigate()
  const taskId = Number(id)

  const [task, setTask] = useState<TaskInfo | null>(null)
  const [nodes, setNodes] = useState<TaskNodeInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState(false)
  const [selectedNodeId, setSelectedNodeId] = useState<number | null>(null)
  const [configEditorOpen, setConfigEditorOpen] = useState(false)
  const [configEditorValue, setConfigEditorValue] = useState('')
  const [hasAppliedRouteContext, setHasAppliedRouteContext] = useState(false)

  const fetchData = useCallback(async () => {
    try {
      const [taskRes, nodesRes] = await Promise.all([getTask(taskId), getTaskNodes(taskId)])
      setTask(taskRes.data)
      setNodes(nodesRes.data || [])
    } catch {
      message.error('加载任务详情失败')
    } finally {
      setLoading(false)
    }
  }, [taskId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  useEffect(() => {
    if (task?.status !== 'RUNNING') return
    const timer = window.setInterval(fetchData, 3000)
    return () => window.clearInterval(timer)
  }, [task?.status, fetchData])

  const completedNodeCount = useMemo(() => {
    if (nodes.length) {
      return nodes.filter((node) => isTerminalNodeStatus(node.status)).length
    }
    return task?.completedNodes ?? 0
  }, [nodes, task])

  const progressPercent = useMemo(() => {
    if (!task?.totalNodes) return 0
    return Math.round((completedNodeCount / task.totalNodes) * 100)
  }, [completedNodeCount, task])

  const nodeMap = useMemo(() => new Map(nodes.map((node) => [node.nodeName, node])), [nodes])

  const selectedNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId) || null,
    [nodes, selectedNodeId],
  )

  const selectedNodeDependencies = useMemo(() => {
    const dependencyNames = parseJsonArray(selectedNode?.dependsOn || null).map(String)
    return dependencyNames
      .map((name) => nodeMap.get(name))
      .filter((item): item is TaskNodeInfo => Boolean(item))
  }, [nodeMap, selectedNode])

  const selectedNodeEvidenceIds = useMemo(() => {
    if (!selectedNode) return []

    const visited = new Set<string>()
    const collected = new Set<string>()

    const visit = (node: TaskNodeInfo | null) => {
      if (!node || visited.has(node.nodeName)) return
      visited.add(node.nodeName)

      extractEvidenceIds(parseJson(node.outputData)).forEach((id) => collected.add(id))
      parseJsonArray(node.dependsOn).map(String).forEach((name) => visit(nodeMap.get(name) || null))
    }

    visit(selectedNode)
    return Array.from(collected)
  }, [nodeMap, selectedNode])

  const selectedReviewPayload = useMemo(() => parseReviewPayload(selectedNode?.outputData), [selectedNode])
  const reviewRiskSummary = useMemo(() => {
    const reviewNodes = nodes.filter((node) => node.agentType === 'REVIEWER')
    const issues = reviewNodes.flatMap((node) => parseReviewPayload(node.outputData)?.issues || [])
    const evidenceIssues = issues.filter((item) => isEvidenceRiskIssue(item))
    const sections = Array.from(
      new Set(
        evidenceIssues
          .map((item) => (typeof item.section === 'string' ? item.section : ''))
          .filter(Boolean),
      ),
    )
    const hasError = evidenceIssues.some((item) => item.severity === 'ERROR')
    return {
      total: evidenceIssues.length,
      sections,
      hasError,
    }
  }, [nodes])
  const selectedNodeConfig = useMemo(() => parseJson(selectedNode?.nodeConfig), [selectedNode?.nodeConfig])
  const selectedNodeInput = useMemo(() => parseJson(selectedNode?.inputData), [selectedNode?.inputData])
  const selectedNodeOutput = useMemo(() => parseJson(selectedNode?.outputData), [selectedNode?.outputData])
  const selectedSourceCandidates = useMemo(
    () => selectedNode?.collectorInsight?.sourceCandidates || [],
    [selectedNode?.collectorInsight?.sourceCandidates],
  )
  const selectedSearchExecutionPlan = useMemo(
    () => selectedNode?.collectorInsight?.searchExecutionPlan || null,
    [selectedNode?.collectorInsight?.searchExecutionPlan],
  )
  const selectedSearchExecutionTrace = useMemo(
    () => selectedNode?.collectorInsight?.searchExecutionTrace || null,
    [selectedNode?.collectorInsight?.searchExecutionTrace],
  )
  const selectedSearchProgress = useMemo(
    () => selectedNode?.collectorInsight?.searchProgress || null,
    [selectedNode?.collectorInsight?.searchProgress],
  )
  const selectedSearchProgressSnapshots = useMemo(
    () => selectedNode?.collectorInsight?.searchProgressSnapshots || [],
    [selectedNode?.collectorInsight?.searchProgressSnapshots],
  )
  const selectedTargets = useMemo(
    () => selectedNode?.collectorInsight?.selectedTargets || [],
    [selectedNode?.collectorInsight?.selectedTargets],
  )
  const sourceCandidateGroups = useMemo(() => {
    const groups = new Map<string, SourceCandidateInfo[]>()
    selectedSourceCandidates.forEach((candidate) => {
      const key = candidate.selectionStage || 'UNSTAGED'
      const current = groups.get(key) || []
      current.push(candidate)
      groups.set(key, current)
    })
    return Array.from(groups.entries()).map(([stage, candidates]) => ({
      stage,
      candidates,
    }))
  }, [selectedSourceCandidates])
  const sourceCandidateStageSummary = useMemo(
    () =>
      sourceCandidateGroups.map((group) => ({
        stage: group.stage,
        count: group.candidates.length,
      })),
    [sourceCandidateGroups],
  )
  const readableConfigFields = useMemo(
    () => (selectedNode ? buildReadableConfig(selectedNode, asRecord(selectedNodeConfig)) : []),
    [selectedNode, selectedNodeConfig],
  )
  const readableInputFields = useMemo(
    () => (selectedNode ? buildReadableInput(selectedNode, asRecord(selectedNodeInput)) : []),
    [selectedNode, selectedNodeInput],
  )
  const readableOutputFields = useMemo(
    () =>
      selectedNode
        ? buildReadableOutput(selectedNode, asRecord(selectedNodeOutput), selectedNode?.outputData)
        : [],
    [selectedNode, selectedNodeOutput],
  )
  const configSuggestions = useMemo(
    () => getConfigSuggestions(selectedNode, asRecord(parseJson(configEditorValue)) || asRecord(selectedNodeConfig)),
    [configEditorValue, selectedNode, selectedNodeConfig],
  )

  const collectorNodeViews = useMemo(
    () =>
      nodes
        .filter((node) => node.agentType === 'COLLECTOR')
        .map((node) => ({
          node,
          insight: getCollectorNodeInsight(node),
          dependencies: getDependencyNames(node.dependsOn),
        })),
    [nodes],
  )

  const activeCollectorViews = useMemo(
    () =>
      collectorNodeViews.filter(
        ({ node, insight }) =>
          node.status === 'RUNNING' ||
          (task?.status === 'RUNNING' && insight.searchProgress && insight.searchProgress.status === 'RUNNING'),
      ),
    [collectorNodeViews, task?.status],
  )

  const collectorLaneGroups = useMemo(() => {
    const groups = new Map<string, typeof collectorNodeViews>()
    collectorNodeViews.forEach((item) => {
      const key = item.insight.competitorName || '未命名竞品'
      const existing = groups.get(key) || []
      existing.push(item)
      groups.set(key, existing)
    })
    return Array.from(groups.entries())
  }, [collectorNodeViews])

  const pipelineNodes = useMemo(() => {
    const orderedPipeline = [
      'extract_schema',
      'analyze_competitors',
      'write_report',
      'quality_check',
      'rewrite_report',
      'quality_check_final',
    ]
    return orderedPipeline.map((name) => nodeMap.get(name)).filter((node): node is TaskNodeInfo => Boolean(node))
  }, [nodeMap])

  const pausedNodes = useMemo(() => nodes.filter((node) => node.status === 'PAUSED'), [nodes])
  const failedNodes = useMemo(() => nodes.filter((node) => node.status === 'FAILED'), [nodes])
  const terminateRequestedNodes = useMemo(
    () => nodes.filter((node) => node.controlState === 'TERMINATE_REQUESTED'),
    [nodes],
  )

  const taskStageLabel = useMemo(() => (task ? getTaskStageLabel(task, nodes) : '等待启动'), [nodes, task])
  const heroTone = useMemo(() => (task ? getTaskHeroTone(task, nodes) : 'default'), [nodes, task])
  const pendingActionCount = failedNodes.length + pausedNodes.length + terminateRequestedNodes.length
  const actionQueueOverflowCount =
    Math.max(0, failedNodes.length - 3) +
    Math.max(0, pausedNodes.length - 3) +
    Math.max(0, terminateRequestedNodes.length - 2)
  const taskRouteContext = useMemo(() => {
    const params = new URLSearchParams(location.search)
    const from = params.get('from')
    if (from !== 'report') return null
    return {
      actionType: params.get('actionType'),
      targetNode: params.get('targetNode'),
      actionTitle: params.get('actionTitle'),
    }
  }, [location.search])

  const taskConfigSummaryItems = useMemo(() => {
    if (!task) return []
    const competitors = parseJsonArray(task.competitorNames).map(String)
    const dimensions = parseJsonArray(task.analysisDimensions).map(String)
    const scopes = parseJsonArray(task.sourceScope).map(String)
    return [
      { label: '主体产品', value: task.subjectProduct || '未提供' },
      { label: '竞品列表', value: summarizeStrings(competitors, 4) },
      { label: '分析维度', value: summarizeStrings(dimensions, 5) },
      { label: '采集范围', value: scopes.length > 0 ? summarizeStrings(scopes, 5) : '未指定' },
      { label: '创建时间', value: dayjs(task.createdAt).format('YYYY-MM-DD HH:mm:ss') },
    ]
  }, [task])

  const handleExecute = async () => {
    setActionLoading(true)
    try {
      await executeTask(taskId)
      message.success('任务已启动')
      await fetchData()
    } catch {
      message.error('执行任务失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleRetry = async () => {
    setActionLoading(true)
    try {
      await retryTask(taskId)
      message.success('任务已重置')
      await fetchData()
    } catch {
      message.error('重试任务失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleResume = async () => {
    setActionLoading(true)
    try {
      await resumeTask(taskId)
      message.success('已基于现有检查点恢复任务')
      await fetchData()
    } catch {
      message.error('恢复任务失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleStop = async () => {
    setActionLoading(true)
    try {
      await stopTask(taskId)
      message.success('任务已停止')
      await fetchData()
    } catch {
      message.error('停止任务失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleRerunNode = async (nodeName: string) => {
    setActionLoading(true)
    try {
      await rerunTaskNode(taskId, nodeName)
      message.success(`已从节点 ${nodeName} 重新发起执行`)
      await fetchData()
    } catch {
      message.error('从当前节点重跑失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handlePauseNode = async (nodeName: string) => {
    setActionLoading(true)
    try {
      await pauseTaskNode(taskId, nodeName)
      message.success(`节点 ${nodeName} 已暂停`)
      await fetchData()
    } catch {
      message.error('暂停节点失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleResumeNode = async (nodeName: string) => {
    setActionLoading(true)
    try {
      await resumeTaskNode(taskId, nodeName)
      message.success(`节点 ${nodeName} 已恢复`)
      await fetchData()
    } catch {
      message.error('恢复节点失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleSkipNode = async (nodeName: string) => {
    setActionLoading(true)
    try {
      await skipTaskNode(taskId, nodeName)
      message.success(`节点 ${nodeName} 已跳过`)
      await fetchData()
    } catch {
      message.error('跳过节点失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleTerminateNode = async (nodeName: string) => {
    setActionLoading(true)
    try {
      await terminateTaskNode(taskId, nodeName)
      message.success(`已向节点 ${nodeName} 发送终止请求`)
      await fetchData()
    } catch {
      message.error('终止节点失败')
    } finally {
      setActionLoading(false)
    }
  }

  const openConfigEditor = (node: TaskNodeInfo) => {
    setSelectedNodeId(node.id ?? null)
    setConfigEditorValue(pretty(node.nodeConfig) || node.nodeConfig || '{}')
    setConfigEditorOpen(true)
  }

  const handleUpdateConfigAndRerun = async () => {
    if (!selectedNode) return
    setActionLoading(true)
    try {
      await updateTaskNodeConfigAndRerun(taskId, selectedNode.nodeName, configEditorValue)
      message.success(`已更新 ${selectedNode.nodeName} 配置并重新发起执行`)
      setConfigEditorOpen(false)
      await fetchData()
    } catch {
      message.error('更新节点配置并重跑失败')
    } finally {
      setActionLoading(false)
    }
  }

  const taskActionQueue: TaskActionQueueItem[] = []

  failedNodes.slice(0, 3).forEach((node) => {
    taskActionQueue.push({
      key: `failed-${node.id}`,
      tone: 'error',
      title: `${getNodeDisplayName(node)} 执行失败`,
      description: node.errorMessage || '请查看节点追踪并决定是否重跑或改配置后继续。',
      nodeId: node.id,
      actions: [
        { key: 'trace', label: '查看追踪', onClick: () => setSelectedNodeId(node.id) },
        ...(node.canRerun
          ? [{ key: 'rerun', label: '从该节点重跑', kind: 'primary' as const, onClick: () => void handleRerunNode(node.nodeName) }]
          : []),
        ...(node.canUpdateConfigAndRerun
          ? [{ key: 'config', label: '修改配置后继续', onClick: () => openConfigEditor(node) }]
          : []),
      ],
    })
  })

  pausedNodes.slice(0, 3).forEach((node) => {
    taskActionQueue.push({
      key: `paused-${node.id}`,
      tone: 'warning',
      title: `${getNodeDisplayName(node)} 已暂停`,
      description: node.interventionReason || '该节点不会继续参与调度，等待人工恢复或跳过。',
      nodeId: node.id,
      actions: [
        ...(node.canResumeNode
          ? [{ key: 'resume', label: '恢复节点', kind: 'primary' as const, onClick: () => void handleResumeNode(node.nodeName) }]
          : []),
        ...(node.canSkip ? [{ key: 'skip', label: '手动跳过', onClick: () => void handleSkipNode(node.nodeName) }] : []),
        { key: 'trace', label: '查看追踪', onClick: () => setSelectedNodeId(node.id) },
      ],
    })
  })

  terminateRequestedNodes.slice(0, 2).forEach((node) => {
    taskActionQueue.push({
      key: `terminate-${node.id}`,
      tone: 'info',
      title: `${getNodeDisplayName(node)} 已收到终止请求`,
      description: node.interventionReason || '系统会在当前轮执行返回后停止使用本轮结果。',
      nodeId: node.id,
      actions: [{ key: 'trace', label: '查看追踪', onClick: () => setSelectedNodeId(node.id) }],
    })
  })

  const rewriteNode = nodeMap.get('rewrite_report')
  if (reviewRiskSummary.total > 0) {
    taskActionQueue.push({
      key: 'review-risk',
      tone: reviewRiskSummary.hasError ? 'error' : 'warning',
      title: `当前存在 ${reviewRiskSummary.total} 个证据风险问题`,
      description:
        reviewRiskSummary.sections.length > 0
          ? `涉及章节：${reviewRiskSummary.sections.join('、')}。建议优先补证据或调整结论后再继续终审。`
          : '当前评审已识别到缺证据章节或结论，请优先前往报告页处理。',
      actions: [
        { key: 'report', label: '前往报告页', kind: 'primary', onClick: () => navigate(`/task/${taskId}/report`) },
        ...(rewriteNode?.canRerun
          ? [{ key: 'rewrite', label: '从 rewrite_report 重跑', onClick: () => void handleRerunNode('rewrite_report') }]
          : []),
      ],
    })
  }

  const defaultExpandedNodeKeys = useMemo(
    () =>
      nodes
        .filter(
          (node) =>
            node.status === 'FAILED' ||
            node.status === 'PAUSED' ||
            node.status === 'RUNNING' ||
            node.controlState === 'TERMINATE_REQUESTED',
        )
        .map((node) => String(node.id)),
    [nodes],
  )

  useEffect(() => {
    if (hasAppliedRouteContext || !taskRouteContext || nodes.length === 0) return
    const targetNodeName = taskRouteContext.targetNode
    if (targetNodeName) {
      const matchedNode = nodes.find((node) => node.nodeName === targetNodeName)
      if (matchedNode) {
        setSelectedNodeId(matchedNode.id)
      }
    }
    if (taskRouteContext.actionType) {
      message.info(
        taskRouteContext.targetNode
          ? `来自报告页：已定位到 ${taskRouteContext.targetNode}，可继续处理${taskRouteContext.actionTitle || '相关动作'}。`
          : `来自报告页：请继续处理${taskRouteContext.actionTitle || '相关动作'}。`,
      )
    }
    setHasAppliedRouteContext(true)
  }, [hasAppliedRouteContext, nodes, taskRouteContext])

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 80 }} />
  }

  if (!task) {
    return <Alert type="warning" message="未找到任务" />
  }

  return (
    <div>
      <div className="page-toolbar">
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
          返回
        </Button>
      </div>

      <div className="page-header">
        <h2>{task.taskName}</h2>
        <Space wrap>
          {taskStatusTag(task.status)}
          <Text type="secondary">创建时间 {dayjs(task.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
        </Space>
      </div>

      <TaskStatusHero
        task={task}
        heroTone={heroTone}
        taskStageLabel={taskStageLabel}
        completedNodeCount={completedNodeCount}
        progressPercent={progressPercent}
        pendingActionCount={pendingActionCount}
        reviewRiskTotal={reviewRiskSummary.total}
        activeCollectorCount={activeCollectorViews.length}
        actionLoading={actionLoading}
        onExecute={() => void handleExecute()}
        onStop={() => void handleStop()}
        onResume={() => void handleResume()}
        onRetry={() => void handleRetry()}
        onViewReport={() => navigate(`/task/${taskId}/report`)}
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <DagOverviewBoard
            collectorLaneGroups={collectorLaneGroups}
            pipelineNodes={pipelineNodes}
            getNodeHeadline={getNodeHeadline}
            onSelectNode={setSelectedNodeId}
          />
        </Col>

        <Col xs={24} lg={8}>
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <TaskActionQueue
              items={taskActionQueue}
              actionLoading={actionLoading}
              overflowCount={actionQueueOverflowCount}
            />
            

            <Card title="实时采集活动" className="work-card">
              {activeCollectorViews.length > 0 ? (
                <div className="live-activity-grid">
                  {activeCollectorViews.map(({ node, insight }) => (
                    <Card
                      key={node.id}
                      size="small"
                      className="live-activity-card"
                      title={
                        <Space wrap>
                          <Text strong>{`${insight.competitorName} / ${getSourceTypeText(insight.sourceType)}`}</Text>
                          {statusTag(node.status)}
                        </Space>
                      }
                      extra={
                        <Button type="link" onClick={() => setSelectedNodeId(node.id)}>
                          查看追踪
                        </Button>
                      }
                    >
                      <SearchActivityPanel insight={insight} />
                    </Card>
                  ))}
                </div>
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message={task.status === 'RUNNING' ? '当前没有活跃采集节点' : '任务当前未处于采集活跃态'}
                  description={
                    task.status === 'RUNNING'
                      ? '系统会在采集节点进入搜索、验证、补源和抓取阶段时实时展示进度。'
                      : '任务执行后，这里会展示正在搜索或抓取中的采集分支。'
                  }
                />
              )}
            </Card>
          </Space>
        </Col>
      </Row>

      <TaskConfigPanel items={taskConfigSummaryItems} />

      <Tabs
        className="detail-tabs"
        items={[
          {
            key: 'nodes',
            label: '节点追踪',
            children: (
              <NodeAccordionList
                nodes={nodes}
                defaultExpandedNodeKeys={defaultExpandedNodeKeys}
                actionLoading={actionLoading}
                onSelectNode={setSelectedNodeId}
                onResumeNode={(nodeName) => void handleResumeNode(nodeName)}
                onTerminateNode={(nodeName) => void handleTerminateNode(nodeName)}
                onRerunNode={(nodeName) => void handleRerunNode(nodeName)}
              />
            ),
          },
          {
            key: 'logs',
            label: '智能体日志',
            children: <AgentLogPanel taskId={taskId} autoRefresh={task.status === 'RUNNING'} />,
          },
        ]}
      />

      <NodeTraceDrawer
        open={Boolean(selectedNode)}
        selectedNode={selectedNode}
        actionLoading={actionLoading}
        onClose={() => setSelectedNodeId(null)}
        onSelectNode={setSelectedNodeId}
        onPauseNode={(nodeName) => void handlePauseNode(nodeName)}
        onResumeNode={(nodeName) => void handleResumeNode(nodeName)}
        onSkipNode={(nodeName) => void handleSkipNode(nodeName)}
        onTerminateNode={(nodeName) => void handleTerminateNode(nodeName)}
        onRerunNode={(nodeName) => void handleRerunNode(nodeName)}
        onOpenConfigEditor={openConfigEditor}
        readableConfigFields={readableConfigFields}
        readableInputFields={readableInputFields}
        readableOutputFields={readableOutputFields}
        selectedSearchProgress={selectedSearchProgress}
        selectedSearchExecutionTrace={selectedSearchExecutionTrace}
        selectedSearchExecutionPlan={selectedSearchExecutionPlan}
        selectedSearchProgressSnapshots={selectedSearchProgressSnapshots}
        selectedSourceCandidates={selectedSourceCandidates}
        sourceCandidateStageSummary={sourceCandidateStageSummary}
        sourceCandidateGroups={sourceCandidateGroups}
        selectedTargets={selectedTargets}
        selectedReviewPayload={selectedReviewPayload}
        selectedNodeDependencies={selectedNodeDependencies}
        selectedNodeEvidenceIds={selectedNodeEvidenceIds}
      />

      <Modal
        title={selectedNode ? `修改 ${getNodeDisplayName(selectedNode)} 配置后继续执行` : '修改节点配置'}
        open={configEditorOpen}
        onCancel={() => setConfigEditorOpen(false)}
        onOk={() => void handleUpdateConfigAndRerun()}
        confirmLoading={actionLoading}
        width={760}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="节点配置会作为新的起点继续执行"
            description="系统会从当前节点开始识别受影响的下游节点并自动失效重跑，已成功且不受影响的节点成果会被保留。"
          />
          {configSuggestions.length > 0 && (
            <Card size="small" title="不会改 JSON？可以先直接套用这些建议">
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                {configSuggestions.map((suggestion) => (
                  <div key={suggestion.key}>
                    <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
                      <Space direction="vertical" size={2}>
                        <Text strong>{suggestion.label}</Text>
                        <Text type="secondary">{suggestion.description}</Text>
                      </Space>
                      <Button onClick={() => setConfigEditorValue(mergeConfigPatch(configEditorValue || '{}', suggestion.patch))}>
                        一键套用
                      </Button>
                    </Space>
                  </div>
                ))}
              </Space>
            </Card>
          )}
          <Alert
            type="warning"
            showIcon
            message="下方 JSON 仅作为高级编辑入口"
            description="更推荐优先使用上面的业务建议按钮；只有你明确知道节点参数含义时，再手工改 JSON。"
          />
          <TextArea
            rows={18}
            value={configEditorValue}
            onChange={(event) => setConfigEditorValue(event.target.value)}
            placeholder="请输入合法 JSON 对象"
          />
        </Space>
      </Modal>
    </div>
  )
}
