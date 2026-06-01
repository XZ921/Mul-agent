import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  List,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ArrowLeftOutlined,
  DownloadOutlined,
  FileTextOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import {
  getExportUrl,
  getHtmlExportUrl,
  getReport,
  listReportEvidences,
  rerunTaskNode,
} from '../api/client'
import EvidenceList from '../components/EvidenceList'
import MarkdownReport from '../components/MarkdownReport'
import type {
  CompetitorEvidenceCoverageInfo,
  CompetitorKnowledgeInfo,
  EvidenceCoverageOverviewInfo,
  EvidenceInfo,
  QualityIssue,
  ReportInfo,
  ReviewCheckpointInfo,
  ReviewNextAction,
  SearchAuditOverviewInfo,
  SectionEvidenceCoverageInfo,
} from '../types'
import {
  getNodeNameLabel,
  getNodeStatusText,
  getReviewPassedText,
  getReviewSectionText,
  getReviewSeverityText,
  getReviewTypeText,
  normalizeReportSummary,
  normalizeReportTitle,
} from '../utils/display'

const { Paragraph, Text } = Typography

function issueColor(severity: QualityIssue['severity']) {
  if (severity === 'ERROR') return 'red'
  if (severity === 'WARNING') return 'orange'
  return 'blue'
}

function uniqueOptions(values: Array<string | null | undefined>) {
  return Array.from(new Set(values.filter((value): value is string => Boolean(value)))).map((value) => ({
    label: value,
    value,
  }))
}

function metadataText(item: EvidenceInfo, key: string) {
  const value = item.pageMetadata?.[key]
  return value == null || value === '' ? undefined : String(value)
}

function evidenceSourceType(item: EvidenceInfo) {
  return item.sourceType || metadataText(item, 'sourceType')
}

function evidenceDiscoveryMethod(item: EvidenceInfo) {
  return item.discoveryMethod || metadataText(item, 'discoveryMethod')
}

function actionPriorityColor(priority?: string) {
  if (priority === 'HIGH') return 'red'
  if (priority === 'MEDIUM') return 'orange'
  return 'blue'
}

function actionTypeText(actionType?: string) {
  if (actionType === 'SUPPLEMENT_EVIDENCE') return '补充证据'
  if (actionType === 'RERUN_NODE') return '重跑节点'
  if (actionType === 'REWRITE_CLAIM') return '改写结论'
  if (actionType === 'MANUAL_REVIEW') return '人工复核'
  return actionType || '后续动作'
}

function supplementMethodText(method?: string | null) {
  if (method === 'BROWSER') return '浏览器补源'
  if (method === 'HTTP_FALLBACK') return 'HTTP 回退补源'
  if (method === 'TIMEOUT_FALLBACK') return '超时降级回退'
  if (method === 'NONE') return '未触发补源'
  return method || '未知方式'
}

function formatCollectorAuditCounts(item: NonNullable<SearchAuditOverviewInfo['collectors']>[number]) {
  return `规划 ${item.plannedCandidateCount ?? 0} / 验证 ${item.verifiedCandidateCount ?? 0} / 补源 ${
    item.supplementedCandidateCount ?? 0
  } / 选中 ${item.selectedCandidateCount ?? 0}`
}

function formatCollectorAuditIssue(item: NonNullable<SearchAuditOverviewInfo['collectors']>[number]) {
  if (item.traceRecorded === false) {
    if (item.auditMessage) return item.auditMessage
    if (item.errorMessage) return `失败：${item.errorMessage}`
    return '采集节点未记录结构化搜索轨迹'
  }
  if (item.degraded && item.degradationReason) return `降级：${item.degradationReason}`
  if (item.browserBlockedReason) return `阻断：${item.browserBlockedReason}`
  if (item.errorMessage) return `失败：${item.errorMessage}`
  if (item.fallbackDecision) return `回退决策：${item.fallbackDecision}`
  return '执行稳定'
}

function coverageStatusColor(missingEvidenceFields?: number, emptyFields?: number) {
  if ((missingEvidenceFields ?? 0) > 0) return 'red'
  if ((emptyFields ?? 0) > 0) return 'orange'
  return 'green'
}

function coverageFieldTone(status?: string) {
  if (status === 'TRACEABLE') return 'green'
  if (status === 'MISSING_EVIDENCE') return 'orange'
  if (status === 'UNTRACEABLE') return 'red'
  return 'default'
}

function coverageFieldLabel(status?: string) {
  if (status === 'TRACEABLE') return '可追溯'
  if (status === 'MISSING_EVIDENCE') return '缺证据'
  if (status === 'UNTRACEABLE') return '不可追溯'
  if (status === 'EMPTY') return '空字段'
  return status || '未标注'
}

function buildTaskContextUrl(
  taskId: number,
  action: { actionType?: string | null; targetNode?: string | null; actionTitle?: string | null },
) {
  const params = new URLSearchParams({ from: 'report' })
  if (action.actionType) params.set('actionType', action.actionType)
  if (action.targetNode) params.set('targetNode', action.targetNode)
  if (action.actionTitle) params.set('actionTitle', action.actionTitle)
  return `/task/${taskId}?${params.toString()}`
}

function extractCoverageStatus(value: unknown) {
  if (value && typeof value === 'object' && typeof (value as { status?: unknown }).status === 'string') {
    return (value as { status: string }).status
  }
  return typeof value === 'string' ? value : 'EMPTY'
}

function ReviewActionButton({
  action,
  actionLoading,
  onAction,
}: {
  action: ReviewNextAction
  actionLoading: boolean
  onAction: (action: ReviewNextAction) => Promise<void>
}) {
  let label = action.title || actionTypeText(action.actionType)
  let type: 'primary' | 'default' = 'default'

  if (action.actionType === 'RERUN_NODE' || action.actionType === 'REWRITE_CLAIM') {
    type = 'primary'
  }
  if (action.actionType === 'SUPPLEMENT_EVIDENCE') {
    label = '前往补充证据'
  }
  if (action.actionType === 'MANUAL_REVIEW') {
    label = '前往任务页复核'
  }

  return (
    <Button loading={actionLoading} onClick={() => void onAction(action)} type={type}>
      {label}
    </Button>
  )
}

function ReviewCheckpointPanel({
  title,
  review,
  actionLoading,
  onAction,
}: {
  title: string
  review: ReviewCheckpointInfo | null
  actionLoading: boolean
  onAction: (action: ReviewNextAction) => Promise<void>
}) {
  if (!review) {
    return null
  }

  return (
    <Card size="small" className="review-subpanel" title={title}>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          <Tag color={review.passed ? 'green' : review.passed === false ? 'orange' : 'default'}>
            {review.passed == null ? getNodeStatusText(review.nodeStatus) : getReviewPassedText(review.passed)}
          </Tag>
          {review.score != null && <Tag color="blue">{`评分 ${review.score}/100`}</Tag>}
          <Tag>{getNodeNameLabel(review.nodeName)}</Tag>
        </Space>

        {review.summary && <Alert type="info" showIcon message="评审摘要" description={review.summary} />}

        {review.passed === false && review.requiresHumanIntervention && (
          <Alert
            type="error"
            showIcon
            message="当前检查点要求先人工处理"
            description="证据缺口或严重问题过多，系统已不建议继续自动改写。请先回到任务页补证据、扩大搜索范围或重跑采集链路。"
          />
        )}

        {review.issues.length > 0 ? (
          <List
            size="small"
            bordered
            dataSource={review.issues}
            renderItem={(issue) => (
              <List.Item>
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag color={issueColor(issue.severity)}>{getReviewSeverityText(issue.severity)}</Tag>
                    <Text strong>{getReviewSectionText(issue.section)}</Text>
                    <Text type="secondary">{getReviewTypeText(issue.type)}</Text>
                  </Space>
                  <Text>{issue.suggestion}</Text>
                </Space>
              </List.Item>
            )}
          />
        ) : (
          <Alert type="success" showIcon message="当前检查点没有质量问题" />
        )}

        {review.nextActions?.length > 0 && (
          <div className="review-next-actions">
            {review.nextActions.map((action, index) => (
              <div className="review-next-action-item" key={`${review.nodeName}-${action.actionType}-${index}`}>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag color={actionPriorityColor(action.priority)}>{action.priority || 'MEDIUM'}</Tag>
                    <Tag color="geekblue">{actionTypeText(action.actionType)}</Tag>
                    {action.targetNode && <Tag>{getNodeNameLabel(action.targetNode)}</Tag>}
                  </Space>
                  <Text strong>{action.title || '处理评审问题'}</Text>
                  <Text type="secondary">{action.description || '请按建议处理当前问题。'}</Text>
                  <Space wrap>
                    <ReviewActionButton action={action} actionLoading={actionLoading} onAction={onAction} />
                  </Space>
                </Space>
              </div>
            ))}
          </div>
        )}
      </Space>
    </Card>
  )
}

function EvidenceCoverageOverviewPanel({ overview }: { overview: EvidenceCoverageOverviewInfo | null | undefined }) {
  if (!overview) {
    return (
      <Alert
        type="info"
        showIcon
        message="当前报告未返回字段级证据覆盖"
        description="这通常意味着任务生成于旧版本，或当前报告尚未写入结构化覆盖信息。"
      />
    )
  }

  const sections = overview.sections || []
  const competitors = overview.competitors || []

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div className="report-inline-metrics">
        <div className="report-inline-metric">
          <span className="report-inline-value">{overview.totalFields ?? 0}</span>
          <span className="report-inline-label">字段总数</span>
        </div>
        <div className="report-inline-metric">
          <span className="report-inline-value">{overview.traceableFields ?? 0}</span>
          <span className="report-inline-label">可追溯</span>
        </div>
        <div className="report-inline-metric">
          <span className="report-inline-value">{overview.missingEvidenceFields ?? 0}</span>
          <span className="report-inline-label">缺证据</span>
        </div>
        <div className="report-inline-metric">
          <span className="report-inline-value">{overview.emptyFields ?? 0}</span>
          <span className="report-inline-label">空字段</span>
        </div>
      </div>

      {(overview.missingEvidenceFields ?? 0) > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`当前仍有 ${overview.missingEvidenceFields} 个结构化字段缺少证据支撑`}
          description="如果这些字段被写成明确判断，终审阶段应优先补证据、收紧表述或删除结论。"
        />
      )}

      {sections.length > 0 && (
        <List
          size="small"
          bordered
          header="章节覆盖概览"
          dataSource={sections}
          renderItem={(item: SectionEvidenceCoverageInfo) => (
            <List.Item>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Space wrap>
                  <Tag color={coverageStatusColor(item.missingEvidenceFields, item.emptyFields)}>{item.sectionTitle}</Tag>
                  <Tag color="green">{`可追溯 ${item.traceableFields}`}</Tag>
                  <Tag color="red">{`缺证据 ${item.missingEvidenceFields}`}</Tag>
                  <Tag color="orange">{`空字段 ${item.emptyFields}`}</Tag>
                </Space>
                <Text type="secondary">{`字段总数：${item.totalFields}`}</Text>
                {item.missingFields.length > 0 && (
                  <Text type="secondary">{`缺口：${item.missingFields.join('、')}`}</Text>
                )}
              </Space>
            </List.Item>
          )}
        />
      )}

      {competitors.length > 0 && (
        <List
          size="small"
          bordered
          header="竞品覆盖概览"
          dataSource={competitors}
          renderItem={(item: CompetitorEvidenceCoverageInfo) => (
            <List.Item>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Space wrap>
                  <Tag color="blue">{item.competitorName}</Tag>
                  <Tag color="green">{`可追溯 ${item.traceableFields}`}</Tag>
                  <Tag color="red">{`缺证据 ${item.missingEvidenceFields}`}</Tag>
                  <Tag color="orange">{`空字段 ${item.emptyFields}`}</Tag>
                </Space>
                <Text type="secondary">{`字段总数：${item.totalFields}`}</Text>
                {item.missingSections.length > 0 && (
                  <Text type="secondary">{`缺口章节：${item.missingSections.join('、')}`}</Text>
                )}
              </Space>
            </List.Item>
          )}
        />
      )}
    </Space>
  )
}

function SearchAuditOverviewPanel({ overview }: { overview: SearchAuditOverviewInfo | null | undefined }) {
  const collectors = overview?.collectors || []
  const collectorNodeCount = overview?.collectorNodeCount ?? collectors.length
  const traceRecordedCount = overview?.traceRecordedCount ?? collectors.filter((item) => item.traceRecorded !== false).length
  const missingTraceCount = Math.max(0, collectorNodeCount - traceRecordedCount)

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {!overview && (
        <Alert
          type="info"
          showIcon
          message="当前报告未返回结构化搜索审计摘要"
          description="这通常意味着任务生成于旧版本，或采集阶段尚未写入统一审计字段。"
        />
      )}

      {overview && missingTraceCount > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`有 ${missingTraceCount} 个采集节点未记录结构化搜索轨迹`}
          description="这并不一定代表节点没执行，可能是浏览器不可用、节点失败、被跳过，或轨迹写入不完整。"
        />
      )}

      {overview && (
        <>
          <div className="report-inline-metrics">
            <div className="report-inline-metric">
              <span className="report-inline-value">{collectorNodeCount}</span>
              <span className="report-inline-label">采集节点</span>
            </div>
            <div className="report-inline-metric">
              <span className="report-inline-value">{traceRecordedCount}</span>
              <span className="report-inline-label">已记录轨迹</span>
            </div>
            <div className="report-inline-metric">
              <span className="report-inline-value">{overview.checkpointRecoveredCount ?? 0}</span>
              <span className="report-inline-label">检查点恢复</span>
            </div>
            <div className="report-inline-metric">
              <span className="report-inline-value">{overview.degradedCount ?? 0}</span>
              <span className="report-inline-label">降级次数</span>
            </div>
          </div>

          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="规划候选总数">{overview.plannedCandidateCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="验证通过总数">{overview.verifiedCandidateCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="运行期补源总数">{overview.supplementedCandidateCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="最终选中总数">{overview.selectedCandidateCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="阻断信号次数">{overview.browserBlockedCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="缺失轨迹节点">{missingTraceCount}</Descriptions.Item>
          </Descriptions>
        </>
      )}

      {collectors.length > 0 ? (
        <List
          size="small"
          bordered
          dataSource={collectors}
          renderItem={(item) => (
            <List.Item>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Space wrap>
                  <Tag color="blue">{item.nodeName}</Tag>
                  {item.nodeStatus && <Tag>{getNodeStatusText(item.nodeStatus)}</Tag>}
                  <Tag>{item.competitorName || '未知竞品'}</Tag>
                  <Tag color="cyan">{item.sourceType || '未知类型'}</Tag>
                  <Tag color={item.traceRecorded === false ? 'default' : 'geekblue'}>
                    {item.traceRecorded === false ? '未记录轨迹' : supplementMethodText(item.supplementMethod)}
                  </Tag>
                  {item.resumedFromCheckpoint ? <Tag color="green">已恢复</Tag> : <Tag>未恢复</Tag>}
                  {item.providerFallbackUsed && <Tag color="gold">已回退</Tag>}
                  {item.degraded && <Tag color="orange">已降级</Tag>}
                </Space>
                <Text>{item.traceRecorded === false ? '未记录结构化候选统计' : formatCollectorAuditCounts(item)}</Text>
                <Text type="secondary">{formatCollectorAuditIssue(item)}</Text>
                {item.browserTraceId && <Text type="secondary">{`浏览器轨迹：${item.browserTraceId}`}</Text>}
                {(item.selectedUrls || []).length > 0 && (
                  <Text type="secondary">{`正式来源：${(item.selectedUrls || []).join('、')}`}</Text>
                )}
              </Space>
            </List.Item>
          )}
        />
      ) : (
        <Alert
          type="info"
          showIcon
          message="暂无节点级搜索审计明细"
          description="采集节点开始执行后，这里会展示每个节点的补源方式、候选统计和异常信息。"
        />
      )}
    </Space>
  )
}

function KnowledgeTracePanel({ knowledges }: { knowledges: CompetitorKnowledgeInfo[] }) {
  if (!knowledges.length) {
    return <Alert type="info" showIcon message="暂无竞品知识溯源信息" />
  }

  const coverageFields = [
    { key: 'summary', label: '产品概览' },
    { key: 'positioning', label: '市场定位' },
    { key: 'targetUsers', label: '目标用户' },
    { key: 'pricing', label: '定价策略' },
    { key: 'strengths', label: '优势判断' },
    { key: 'weaknesses', label: '短板风险' },
  ]

  return (
    <Collapse
      items={knowledges.map((knowledge) => ({
        key: knowledge.competitorName,
        label: (
          <Space wrap>
            <Text strong>{knowledge.competitorName}</Text>
            <Tag color="blue">{`来源 ${knowledge.sourceUrls.length}`}</Tag>
            {knowledge.officialUrl && <Tag>含官网</Tag>}
          </Space>
        ),
        children: (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Paragraph>{normalizeReportSummary(knowledge.summary) || '暂无摘要'}</Paragraph>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="官网地址">{knowledge.officialUrl || '未记录'}</Descriptions.Item>
              <Descriptions.Item label="市场定位">{knowledge.positioning || '未记录'}</Descriptions.Item>
              <Descriptions.Item label="目标用户">
                {knowledge.targetUsers?.length ? knowledge.targetUsers.join('、') : '未记录'}
              </Descriptions.Item>
            </Descriptions>

            <div className="knowledge-coverage-grid">
              {coverageFields.map((field) => {
                const status = extractCoverageStatus((knowledge.evidenceCoverage as Record<string, unknown>)?.[field.key])
                return (
                  <div className="knowledge-coverage-cell" key={`${knowledge.competitorName}-${field.key}`}>
                    <Text>{field.label}</Text>
                    <Tag color={coverageFieldTone(status)}>{coverageFieldLabel(status)}</Tag>
                  </div>
                )
              })}
            </div>

            <List
              size="small"
              bordered
              header="来源链接"
              dataSource={knowledge.sourceUrls}
              renderItem={(item) => <List.Item>{item}</List.Item>}
            />
          </Space>
        ),
      }))}
    />
  )
}

export default function ReportPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const taskId = Number(id)

  const [report, setReport] = useState<ReportInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState(false)
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const [evidenceLoading, setEvidenceLoading] = useState(false)
  const [filteredEvidences, setFilteredEvidences] = useState<EvidenceInfo[]>([])
  const [evidenceFilters, setEvidenceFilters] = useState<{
    competitorName?: string
    sourceType?: string
    discoveryMethod?: string
  }>({})

  const fetchReport = async () => {
    try {
      const res = await getReport(taskId)
      setReport(res.data)
      setFilteredEvidences(res.data.evidences || [])
    } catch {
      message.error('加载报告失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void fetchReport()
  }, [taskId])

  const qualityIssueCount = useMemo(() => report?.qualityIssues?.length || 0, [report])
  const latestReview = useMemo(() => report?.finalReview || report?.initialReview || null, [report])
  const rewriteRecommended = Boolean(
    !report?.qualityPassed
      && latestReview
      && latestReview.passed === false
      && latestReview.requiresHumanIntervention !== true
      && latestReview.autoRewriteAllowed !== false,
  )
  const evidenceFilterOptions = useMemo(() => {
    const source = report?.evidences || []
    return {
      competitors: uniqueOptions(source.map((item) => item.competitorName)),
      sourceTypes: uniqueOptions(source.map(evidenceSourceType)),
      discoveryMethods: uniqueOptions(source.map(evidenceDiscoveryMethod)),
    }
  }, [report])

  const reviewProblemCount = latestReview?.issues?.length || qualityIssueCount
  const evidenceMissingCount = report?.evidenceCoverageOverview?.missingEvidenceFields ?? 0
  const searchAuditDegradedCount = report?.searchAuditOverview?.degradedCount ?? 0

  const fetchFilteredEvidences = async (nextFilters: typeof evidenceFilters) => {
    setEvidenceFilters(nextFilters)
    setEvidenceLoading(true)
    try {
      const normalizedFilters = Object.fromEntries(
        Object.entries(nextFilters).filter(([, value]) => Boolean(value)),
      ) as typeof evidenceFilters
      const res = await listReportEvidences(taskId, normalizedFilters)
      setFilteredEvidences(res.data || [])
    } catch {
      message.error('筛选证据来源失败')
    } finally {
      setEvidenceLoading(false)
    }
  }

  const handleRewriteReport = async () => {
    if (!rewriteRecommended) {
      message.warning('当前不建议直接自动改写，请先回到任务页补证据或调整采集策略')
      navigate(`/task/${taskId}`)
      return
    }
    setActionLoading(true)
    try {
      await rerunTaskNode(taskId, 'rewrite_report')
      message.success('已触发 rewrite_report 重跑，请回到任务页观察执行进度')
      navigate(
        buildTaskContextUrl(taskId, {
          actionType: 'REWRITE_CLAIM',
          targetNode: 'rewrite_report',
          actionTitle: '报告重写',
        }),
      )
    } catch {
      message.error('触发报告重写失败')
    } finally {
      setActionLoading(false)
    }
  }

  const handleReviewAction = async (action: ReviewNextAction) => {
    if (action.actionType === 'SUPPLEMENT_EVIDENCE' || action.actionType === 'MANUAL_REVIEW') {
      navigate(
        buildTaskContextUrl(taskId, {
          actionType: action.actionType,
          targetNode: action.targetNode,
          actionTitle: action.title || actionTypeText(action.actionType),
        }),
      )
      return
    }

    const targetNode = action.actionType === 'REWRITE_CLAIM' ? 'rewrite_report' : action.targetNode
    if (!targetNode) {
      navigate(
        buildTaskContextUrl(taskId, {
          actionType: action.actionType,
          actionTitle: action.title || actionTypeText(action.actionType),
        }),
      )
      return
    }

    setActionLoading(true)
    try {
      await rerunTaskNode(taskId, targetNode)
      message.success(`已触发 ${targetNode} 重跑`)
      navigate(
        buildTaskContextUrl(taskId, {
          actionType: action.actionType,
          targetNode,
          actionTitle: action.title || actionTypeText(action.actionType),
        }),
      )
    } catch {
      message.error('执行评审建议失败')
    } finally {
      setActionLoading(false)
    }
  }

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 80 }} />
  }

  if (!report) {
    return (
      <Alert
        type="warning"
        showIcon
        message="未找到报告"
        description="请先成功执行任务，再打开报告页面。"
      />
    )
  }

  return (
    <div>
      <div className="page-toolbar">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/task/${taskId}`)}>
            返回任务
          </Button>
          <Button icon={<DownloadOutlined />} onClick={() => window.open(getExportUrl(taskId))}>
            导出文本版
          </Button>
          <Button icon={<DownloadOutlined />} onClick={() => window.open(getHtmlExportUrl(taskId))}>
            导出网页版
          </Button>
          <Button icon={<SafetyCertificateOutlined />} onClick={() => setEvidenceOpen(true)}>
            查看证据
          </Button>
        </Space>
      </div>

      <div className="page-header">
        <h2>{normalizeReportTitle(report.title)}</h2>
        <p>先看质量结论和修订动作，再下钻证据覆盖、搜索审计与知识溯源。</p>
      </div>

      <Card
        className={`work-card hero-card ${
          report.qualityPassed ? 'hero-tone-success' : 'hero-tone-danger'
        }`}
      >
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <div className="hero-shell">
            <div className="hero-copy">
              <Space wrap>
                <Tag color={report.qualityPassed ? 'green' : 'red'}>
                  {report.qualityPassed ? '质量评审通过' : '质量评审未通过'}
                </Tag>
                <Tag color={report.rewriteApplied ? 'orange' : 'default'}>
                  {report.rewriteApplied ? '已执行改写' : '尚未触发改写'}
                </Tag>
                {latestReview && <Tag>{getNodeNameLabel(latestReview.nodeName)}</Tag>}
              </Space>
              <div className="review-hero-score">
                <span className="review-hero-score-value">{report.qualityScore ?? '—'}</span>
                <span className="review-hero-score-unit">/100</span>
              </div>
              <div className="hero-title">{report.qualityPassed ? '报告可进入复用或输出阶段' : '当前报告仍需修订或补证据后再终审'}</div>
              <Text type="secondary" className="hero-description">
                {latestReview?.summary ||
                  (report.qualityPassed
                    ? '当前版本已满足质量要求，可以直接导出、分享或继续作为下一轮分析的基线。'
                    : '请优先处理缺证据、结论过强或章节缺口问题，再触发重写或指定节点重跑。')}
              </Text>
              {!report.qualityPassed && latestReview?.requiresHumanIntervention && (
                <Alert
                  type="error"
                  showIcon
                  style={{ marginTop: 12 }}
                  message="当前报告不应继续自动改写"
                  description="这次失败更像是证据链不足，而不是单纯文案问题。建议先去任务页补证据、扩大搜索范围，或从采集/抽取节点重跑。"
                />
              )}
            </div>

            <Space wrap className="hero-actions">
              {!report.qualityPassed && rewriteRecommended && (
                <Button
                  type="primary"
                  icon={<FileTextOutlined />}
                  loading={actionLoading}
                  onClick={() => void handleRewriteReport()}
                >
                  应用计划并触发重写
                </Button>
              )}
              <Button type={rewriteRecommended ? 'default' : 'primary'} onClick={() => navigate(`/task/${taskId}`)}>
                前往任务页处理
              </Button>
              <Button onClick={() => setEvidenceOpen(true)}>查看证据</Button>
            </Space>
          </div>

          <div className="hero-stats">
            <div className="hero-stat">
              <span className="hero-stat-value">{reviewProblemCount}</span>
              <span className="hero-stat-label">当前问题数</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-value">{report.evidenceCount}</span>
              <span className="hero-stat-label">证据来源数</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-value">{evidenceMissingCount}</span>
              <span className="hero-stat-label">缺证据字段</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-value">{searchAuditDegradedCount}</span>
              <span className="hero-stat-label">搜索降级次数</span>
            </div>
          </div>
        </Space>
      </Card>

      <Card title="报告正文" className="work-card" style={{ marginTop: 16 }}>
        <MarkdownReport content={report.content} evidences={report.evidences} />
      </Card>

      <Row gutter={[16, 16]} style={{ marginTop: 0 }}>
        <Col xs={24} lg={14}>
          <Card title="问题诊断看板" className="work-card review-diagnosis-card">
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              {reviewProblemCount > 0 ? (
                <Alert
                  type="warning"
                  showIcon
                  message={`当前共识别 ${reviewProblemCount} 个质量问题`}
                  description={
                    evidenceMissingCount > 0
                      ? `其中 ${evidenceMissingCount} 个字段存在缺证据风险，建议优先处理这些问题。`
                      : '建议先阅读问题清单，再决定是补证据、重跑节点还是直接触发重写。'
                  }
                />
              ) : (
                <Alert type="success" showIcon message="当前报告没有新增质量问题" />
              )}

              <ReviewCheckpointPanel
                title="初审结果"
                review={report.initialReview}
                actionLoading={actionLoading}
                onAction={handleReviewAction}
              />
              <ReviewCheckpointPanel
                title="终审结果"
                review={report.finalReview}
                actionLoading={actionLoading}
                onAction={handleReviewAction}
              />

              {!report.initialReview && !report.finalReview && qualityIssueCount > 0 && (
                <Card size="small" className="review-subpanel" title="最新质量反馈">
                  <Collapse
                    items={report.qualityIssues.map((issue, index) => ({
                      key: String(index),
                      label: (
                        <Space>
                          <Tag color={issueColor(issue.severity)}>{getReviewSeverityText(issue.severity)}</Tag>
                          <span>{getReviewSectionText(issue.section)}</span>
                          <span className="muted-text">{getReviewTypeText(issue.type)}</span>
                        </Space>
                      ),
                      children: <p>{issue.suggestion}</p>,
                    }))}
                  />
                </Card>
              )}
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card title="修订计划与操作" className="work-card review-action-card">
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              {report.revisionPlan ? (
                <>
                  <Alert
                    type={report.revisionPlan.rewriteRequired ? 'warning' : 'success'}
                    showIcon
                    message={report.revisionPlan.rewriteRequired ? '当前建议执行改写' : '当前无需重新改写'}
                    description={report.revisionPlan.summary || '暂无修订摘要'}
                  />

                  {report.revisionPlan.items.length > 0 && (
                    <List
                      size="small"
                      bordered
                      header="修订问题清单"
                      dataSource={report.revisionPlan.items}
                      renderItem={(item) => (
                        <List.Item>
                          <Space direction="vertical" size={4} style={{ width: '100%' }}>
                            <Space wrap>
                              <Tag color={issueColor(item.severity)}>{getReviewSeverityText(item.severity)}</Tag>
                              <Text strong>{getReviewSectionText(item.section)}</Text>
                              <Text type="secondary">{getReviewTypeText(item.type)}</Text>
                            </Space>
                            <Text>{item.suggestion}</Text>
                          </Space>
                        </List.Item>
                      )}
                    />
                  )}

                  {report.revisionPlan.rewriteGuidelines.length > 0 && (
                    <List
                      size="small"
                      bordered
                      header="改写指引"
                      dataSource={report.revisionPlan.rewriteGuidelines}
                      renderItem={(item) => <List.Item>{item}</List.Item>}
                    />
                  )}

                  <Space wrap>
                    {report.revisionPlan.rewriteRequired && rewriteRecommended && (
                      <Button type="primary" loading={actionLoading} onClick={() => void handleRewriteReport()}>
                        应用修订计划并触发重写
                      </Button>
                    )}
                    {report.revisionPlan.rewriteRequired && !rewriteRecommended && (
                      <Alert
                        type="warning"
                        showIcon
                        message="当前暂不建议直接执行自动改写"
                        description="请先处理证据缺口或搜索质量问题，再回到此处触发改写。"
                      />
                    )}
                    <Button onClick={() => navigate(`/task/${taskId}`)}>返回任务页补证据</Button>
                  </Space>
                </>
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message="当前没有结构化修订计划"
                  description="如果后续评审识别到问题，这里会自动生成改写指引和推荐动作。"
                />
              )}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="证据与高级追踪" className="work-card" style={{ marginTop: 16 }}>
        <Collapse
          items={[
            {
              key: 'coverage',
              label: `章节证据覆盖${report.evidenceCoverageOverview ? ` · 缺证据 ${evidenceMissingCount}` : ''}`,
              children: <EvidenceCoverageOverviewPanel overview={report.evidenceCoverageOverview} />,
            },
            {
              key: 'audit',
              label: `搜索审计${report.searchAuditOverview ? ` · 降级 ${searchAuditDegradedCount}` : ''}`,
              children: <SearchAuditOverviewPanel overview={report.searchAuditOverview} />,
            },
            {
              key: 'knowledge',
              label: `知识溯源 · ${report.competitorKnowledges.length} 个竞品`,
              children: <KnowledgeTracePanel knowledges={report.competitorKnowledges} />,
            },
            qualityIssueCount > 0
              ? {
                  key: 'feedback',
                  label: `最新质量反馈 · ${qualityIssueCount} 条`,
                  children: (
                    <Collapse
                      items={report.qualityIssues.map((issue, index) => ({
                        key: `quality-${index}`,
                        label: (
                          <Space>
                            <Tag color={issueColor(issue.severity)}>{getReviewSeverityText(issue.severity)}</Tag>
                            <span>{getReviewSectionText(issue.section)}</span>
                            <span className="muted-text">{getReviewTypeText(issue.type)}</span>
                          </Space>
                        ),
                        children: <p>{issue.suggestion}</p>,
                      }))}
                    />
                  ),
                }
              : {
                  key: 'feedback',
                  label: '最新质量反馈',
                  children: <Alert type="success" showIcon message="当前没有额外质量反馈" />,
                },
          ]}
        />
      </Card>

      <Modal
        title={`证据来源（${filteredEvidences.length}/${report.evidences.length}）`}
        open={evidenceOpen}
        onCancel={() => setEvidenceOpen(false)}
        width={900}
        footer={null}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap>
            <Select
              allowClear
              placeholder="竞品"
              style={{ minWidth: 160 }}
              options={evidenceFilterOptions.competitors}
              value={evidenceFilters.competitorName}
              onChange={(value) => void fetchFilteredEvidences({ ...evidenceFilters, competitorName: value })}
            />
            <Select
              allowClear
              placeholder="来源类型"
              style={{ minWidth: 140 }}
              options={evidenceFilterOptions.sourceTypes}
              value={evidenceFilters.sourceType}
              onChange={(value) => void fetchFilteredEvidences({ ...evidenceFilters, sourceType: value })}
            />
            <Select
              allowClear
              placeholder="补源方式"
              style={{ minWidth: 140 }}
              options={evidenceFilterOptions.discoveryMethods}
              value={evidenceFilters.discoveryMethod}
              onChange={(value) => void fetchFilteredEvidences({ ...evidenceFilters, discoveryMethod: value })}
            />
            <Button onClick={() => void fetchFilteredEvidences({})}>清空筛选</Button>
          </Space>
          <Spin spinning={evidenceLoading}>
            <EvidenceList evidences={filteredEvidences} />
          </Spin>
        </Space>
      </Modal>
    </div>
  )
}
