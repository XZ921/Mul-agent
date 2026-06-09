import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Descriptions, List, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getAgentLogDetail, getAgentLogs } from '../api/client'
import AdvancedDiagnosticSection from './AdvancedDiagnosticSection'
import DebugJson from './DebugJson'
import type { AgentLog, AgentType, NodeStatus, TaskEventConnectionStatus } from '../types'
import { appMessage } from '../utils/appMessage'
import { getAgentTypeText, getNodeStatusText, getTaskEventStreamStatusText } from '../utils/display'

const { Text } = Typography

const agentColorMap: Record<AgentType, string> = {
  COLLECTOR: 'blue',
  EXTRACTOR: 'purple',
  ANALYZER: 'cyan',
  WRITER: 'orange',
  REVIEWER: 'magenta',
}

const statusColor: Record<NodeStatus, string> = {
  PENDING: 'default',
  READY: 'blue',
  DISPATCHED: 'processing',
  RUNNING: 'processing',
  WAITING_RETRY: 'orange',
  WAITING_INTERVENTION: 'gold',
  COMPENSATED: 'green',
  PAUSED: 'warning',
  SUCCESS: 'green',
  FAILED: 'red',
  SKIPPED: 'default',
}

interface Props {
  taskId: number
  autoRefresh?: boolean
  logs?: AgentLog[]
  streamStatus?: TaskEventConnectionStatus
  fallbackPollingActive?: boolean
  onRefresh?: () => Promise<void> | void
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

function firstString(...values: Array<unknown>) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) {
      return value.trim()
    }
  }
  return undefined
}

function collectTraceIds(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .map((item) => asRecord(item)?.browserTraceId)
    .filter((item): item is string => typeof item === 'string' && Boolean(item.trim()))
}

function resolveBrowserTraceId(
  searchTrace: Record<string, unknown> | null,
  outputRecord: Record<string, unknown> | null,
) {
  return firstString(
    searchTrace?.browserTraceId,
    outputRecord?.browserTraceId,
    ...collectTraceIds(outputRecord?.selectedTargets),
    ...collectTraceIds(outputRecord?.sourceCandidates),
  )
}

function displayValue(value: unknown) {
  if (value == null || value === '') return '未提供'
  if (Array.isArray(value)) {
    return value.map((item) => (typeof item === 'string' ? item : JSON.stringify(item))).join('、') || '未提供'
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  return String(value)
}

function searchModeText(mode?: string) {
  if (mode === 'HYBRID') return '混合模式：浏览器优先，必要时 HTTP 回退'
  if (mode === 'BROWSER_ONLY') return '仅浏览器模式'
  if (mode === 'HTTP_ONLY') return '仅 HTTP 模式'
  if (mode === 'HEURISTIC_ONLY') return '仅启发式模式'
  return mode || '未提供'
}

function stepStatusTag(status?: string) {
  if (status === 'SUCCESS') return <Tag color="green">已完成</Tag>
  if (status === 'RUNNING') return <Tag color="blue">执行中</Tag>
  if (status === 'PAUSED') return <Tag color="orange">已暂停</Tag>
  if (status === 'SKIPPED') return <Tag color="gold">已跳过</Tag>
  if (status === 'FAILED') return <Tag color="red">失败</Tag>
  return <Tag>{status || '待执行'}</Tag>
}

function progressStatusTag(status?: string) {
  if (status === 'SUCCESS') return <Tag color="green">已完成</Tag>
  if (status === 'RUNNING') return <Tag color="blue">执行中</Tag>
  if (status === 'PAUSED') return <Tag color="orange">已暂停</Tag>
  if (status === 'SKIPPED') return <Tag color="gold">已跳过</Tag>
  if (status === 'FAILED') return <Tag color="red">失败</Tag>
  if (status === 'DEGRADED') return <Tag color="orange">已降级</Tag>
  return <Tag>{status || '未知状态'}</Tag>
}

function buildLogRiskSummary(logs: AgentLog[]) {
  // 日志面板默认只回答“有没有风险、要不要进一步排查”，
  // 详细表格、追踪编号和原始执行记录统一后置到高级诊断层。
  if (logs.length === 0) {
    return '当前还没有智能体日志，任务推进后会在高级诊断里补充完整执行明细。'
  }

  const failedCount = logs.filter((log) => log.status === 'FAILED').length
  const interventionCount = logs.filter((log) => log.needsHumanIntervention).length
  const runningCount = logs.filter((log) => log.status === 'RUNNING').length

  if (failedCount > 0 || interventionCount > 0) {
    return `当前有 ${failedCount} 条失败日志、${interventionCount} 条需人工关注记录，建议先进入高级诊断查看失败原因和追踪编号。`
  }

  if (runningCount > 0) {
    return `当前有 ${runningCount} 个智能体仍在执行，详细日志表与原始轨迹已后置到高级诊断。`
  }

  return '当前日志整体稳定，详细执行表和原始轨迹默认收纳到高级诊断，避免首屏直接暴露技术细节。'
}

export default function AgentLogPanel({
  taskId,
  autoRefresh = false,
  logs: controlledLogs,
  streamStatus,
  fallbackPollingActive = false,
  onRefresh,
}: Props) {
  const [logs, setLogs] = useState<AgentLog[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedLog, setSelectedLog] = useState<AgentLog | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState<AgentLog | null>(null)
  const [agentFilter, setAgentFilter] = useState<string | undefined>()
  const pollingRef = useRef<number | null>(null)
  const fetchingRef = useRef(false)

  const useExternalLogs = Array.isArray(controlledLogs)

  const fetchLogs = useCallback(async () => {
    // 任务详情页接入 SSE 后，日志列表优先消费外部受控数据；这里只保留统一刷新兜底。
    if (useExternalLogs) {
      await onRefresh?.()
      return
    }
    if (fetchingRef.current) return
    fetchingRef.current = true
    setLoading(true)
    try {
      const res = await getAgentLogs(taskId)
      setLogs(res.data || [])
    } catch {
      appMessage.error('获取智能体日志失败')
    } finally {
      fetchingRef.current = false
      setLoading(false)
    }
  }, [onRefresh, taskId, useExternalLogs])

  useEffect(() => {
    if (useExternalLogs) return
    fetchLogs()
  }, [fetchLogs, useExternalLogs])

  useEffect(() => {
    if (!autoRefresh || useExternalLogs) return
    let cancelled = false

    const schedule = () => {
      if (cancelled) return
      pollingRef.current = window.setTimeout(async () => {
        if (document.hidden) {
          schedule()
          return
        }
        await fetchLogs()
        schedule()
      }, 3000)
    }

    schedule()
    return () => {
      cancelled = true
      if (pollingRef.current != null) {
        window.clearTimeout(pollingRef.current)
      }
    }
  }, [autoRefresh, fetchLogs, useExternalLogs])

  useEffect(() => {
    if (!useExternalLogs) return
    setLogs(controlledLogs || [])
  }, [controlledLogs, useExternalLogs])

  const filteredLogs = agentFilter ? logs.filter((log) => log.agentType === agentFilter) : logs
  const logSummary = useMemo(
    () => ({
      total: logs.length,
      running: logs.filter((log) => log.status === 'RUNNING').length,
      failed: logs.filter((log) => log.status === 'FAILED').length,
      intervention: logs.filter((log) => log.needsHumanIntervention).length,
    }),
    [logs],
  )
  const riskSummaryText = useMemo(() => buildLogRiskSummary(logs), [logs])

  const streamStatusText = streamStatus
    ? getTaskEventStreamStatusText(streamStatus, fallbackPollingActive)
    : null

  const handleViewDetail = async (log: AgentLog) => {
    setSelectedLog(log)
    setDetail(log)
    setDetailOpen(true)
    try {
      const res = await getAgentLogDetail(log.id)
      setDetail(res.data)
    } catch {
      setDetail(log)
    }
  }

  const detailOutput = parseJson(detail?.outputData)
  const detailOutputRecord = asRecord(detailOutput)
  const searchTrace = asRecord(detailOutputRecord?.searchExecutionTrace)
  const searchProgress = asRecord(detailOutputRecord?.searchProgress)
  const searchProgressSnapshots = Array.isArray(detailOutputRecord?.searchProgressSnapshots)
    ? (detailOutputRecord?.searchProgressSnapshots as Record<string, unknown>[])
    : []
  const searchPlanSteps = Array.isArray(asRecord(detailOutputRecord?.searchExecutionPlan)?.steps)
    ? ((asRecord(detailOutputRecord?.searchExecutionPlan)?.steps as unknown[]) || [])
    : []
  const browserTraceId = resolveBrowserTraceId(searchTrace, detailOutputRecord)

  const columns = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (time: string) => dayjs(time).format('HH:mm:ss'),
    },
    {
      title: '智能体类型',
      dataIndex: 'agentType',
      width: 140,
      render: (type: AgentType) => <Tag color={agentColorMap[type]}>{getAgentTypeText(type)}</Tag>,
    },
    {
      title: '名称',
      dataIndex: 'agentName',
      width: 140,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: NodeStatus) => <Tag color={statusColor[status]}>{getNodeStatusText(status)}</Tag>,
    },
    {
      title: '模型',
      dataIndex: 'modelName',
      width: 150,
      render: (value: string | null) => value || '-',
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 90,
      render: (duration: number | null) => (duration ? `${(duration / 1000).toFixed(1)}秒` : '-'),
    },
    {
      title: '追踪编号',
      dataIndex: 'traceId',
      ellipsis: true,
      render: (value: string | null) => value || '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_: unknown, record: AgentLog) => <a onClick={() => handleViewDetail(record)}>详情</a>,
    },
  ]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card className="work-card">
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <div>
            <Text strong>日志风险摘要</Text>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              {riskSummaryText}
            </Typography.Paragraph>
          </div>

          <div className="report-inline-metrics">
            <div className="report-inline-metric">
              <span className="report-inline-value">{logSummary.total}</span>
              <span className="report-inline-label">日志总数</span>
            </div>
            <div className="report-inline-metric">
              <span className="report-inline-value">{logSummary.running}</span>
              <span className="report-inline-label">执行中</span>
            </div>
            <div className="report-inline-metric">
              <span className="report-inline-value">{logSummary.failed}</span>
              <span className="report-inline-label">失败</span>
            </div>
            <div className="report-inline-metric">
              <span className="report-inline-value">{logSummary.intervention}</span>
              <span className="report-inline-label">需人工关注</span>
            </div>
          </div>

          <AdvancedDiagnosticSection
            summary="日志表、筛选器、追踪编号和原始执行细节仅在需要排查时展开，避免默认首屏变成调试台。"
          >
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <div className="table-toolbar">
                <Space wrap>
                  <Text strong>智能体执行日志</Text>
                  {useExternalLogs ? (
                    <Tag color={fallbackPollingActive ? 'orange' : streamStatus === 'open' ? 'green' : 'blue'}>
                      {fallbackPollingActive
                        ? '自动刷新兜底中'
                        : streamStatus === 'open'
                          ? '实时同步中'
                          : '状态通道准备中'}
                    </Tag>
                  ) : autoRefresh ? (
                    <Tag color="blue">自动刷新中</Tag>
                  ) : null}
                </Space>
                <Space wrap>
                  <Select
                    allowClear
                    placeholder="按智能体类型筛选"
                    style={{ width: 190 }}
                    value={agentFilter}
                    onChange={setAgentFilter}
                    options={(['COLLECTOR', 'EXTRACTOR', 'ANALYZER', 'WRITER', 'REVIEWER'] as AgentType[]).map((type) => ({
                      label: <Tag color={agentColorMap[type]}>{getAgentTypeText(type)}</Tag>,
                      value: type,
                    }))}
                  />
                  <Button icon={<ReloadOutlined />} onClick={() => void fetchLogs()}>
                    刷新
                  </Button>
                </Space>
              </div>

              <Alert
                type={fallbackPollingActive ? 'warning' : autoRefresh || useExternalLogs ? 'info' : 'success'}
                showIcon
                message={
                  streamStatusText
                  || (autoRefresh ? '运行中任务会按节流轮询刷新日志' : '当前已停止自动刷新')
                }
                description={
                  useExternalLogs
                    ? (fallbackPollingActive
                        ? '当前日志会优先跟随实时进度更新；如果实时同步暂不可用，会回退到任务页统一自动刷新快照。'
                        : '日志由任务详情页统一增量归并，手动刷新会补拉最新快照。')
                    : autoRefresh
                      ? '页面切到后台时会暂停轮询，避免重复定时器和无意义请求。'
                      : '如需查看最新日志，可手动刷新。'
                }
              />

              <Table
                rowKey="id"
                columns={columns}
                dataSource={filteredLogs}
                loading={loading}
                size="small"
                pagination={{ pageSize: 10 }}
                locale={{ emptyText: '暂无智能体执行日志' }}
              />
            </Space>
          </AdvancedDiagnosticSection>
        </Space>
      </Card>

      <Modal
        title={`智能体执行详情 ${selectedLog ? `- ${getAgentTypeText(selectedLog.agentType)}` : ''}`}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        width={920}
        footer={null}
      >
        {detail && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="智能体类型">
                <Tag color={agentColorMap[detail.agentType]}>{getAgentTypeText(detail.agentType)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="智能体名称">{detail.agentName}</Descriptions.Item>
              <Descriptions.Item label="模型">{detail.modelName || '-'}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[detail.status]}>{getNodeStatusText(detail.status)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="耗时">
                {detail.durationMs ? `${(detail.durationMs / 1000).toFixed(1)}秒` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="令牌用量">{detail.tokenUsage || '-'}</Descriptions.Item>
              <Descriptions.Item label="追踪编号">{detail.traceId || '-'}</Descriptions.Item>
              <Descriptions.Item label="推理摘要">{detail.reasoningSummary || '-'}</Descriptions.Item>
              {detail.errorMessage && (
                <Descriptions.Item label="错误信息">
                  <span className="danger-text">{detail.errorMessage}</span>
                </Descriptions.Item>
              )}
            </Descriptions>

            {searchProgress && (
              <Card size="small" title="搜索进度">
                <Descriptions column={1} size="small" bordered>
                  <Descriptions.Item label="当前状态">
                    <Space wrap>
                      {progressStatusTag(typeof searchProgress.status === 'string' ? searchProgress.status : undefined)}
                      {searchProgress.degraded === true && <Tag color="orange">已触发降级链路</Tag>}
                    </Space>
                  </Descriptions.Item>
                  <Descriptions.Item label="当前步骤">
                    {displayValue(searchProgress.currentStep)}
                  </Descriptions.Item>
                  <Descriptions.Item label="完成进度">
                    {`${displayValue(searchProgress.completedSteps)}/${displayValue(searchProgress.totalSteps)}（${displayValue(searchProgress.progressPercent)}%）`}
                  </Descriptions.Item>
                  <Descriptions.Item label="状态说明">
                    {displayValue(searchProgress.message)}
                  </Descriptions.Item>
                  <Descriptions.Item label="降级原因">
                    {displayValue(searchProgress.degradationReason)}
                  </Descriptions.Item>
                </Descriptions>
              </Card>
            )}

            {searchTrace && (
              <Card size="small" title="搜索执行轨迹">
                <Descriptions column={1} size="small" bordered>
                  <Descriptions.Item label="补源方式">
                    {displayValue(searchTrace.supplementMethod)}
                  </Descriptions.Item>
                  <Descriptions.Item label="搜索模式">
                    {searchModeText(typeof searchTrace.searchMode === 'string' ? searchTrace.searchMode : undefined)}
                  </Descriptions.Item>
                  <Descriptions.Item label="执行 Query">
                    {displayValue(searchTrace.browserExecutedQueries)}
                  </Descriptions.Item>
                  <Descriptions.Item label="执行摘要">
                    {displayValue(searchTrace.browserSearchSummary)}
                  </Descriptions.Item>
                  <Descriptions.Item label="浏览器轨迹编号">
                    {displayValue(browserTraceId)}
                  </Descriptions.Item>
                  <Descriptions.Item label="回退决策">
                    {displayValue(searchTrace.fallbackDecision)}
                  </Descriptions.Item>
                  <Descriptions.Item label="候选统计">
                    {`规划 ${displayValue(searchTrace.plannedCandidateCount)} / 验证通过 ${displayValue(searchTrace.verifiedCandidateCount)} / 补源 ${displayValue(searchTrace.supplementedCandidateCount)} / 最终选中 ${displayValue(searchTrace.selectedCandidateCount)}`}
                  </Descriptions.Item>
                  <Descriptions.Item label="阻断信号">
                    {displayValue(searchTrace.browserBlockedReason)}
                  </Descriptions.Item>
                  <Descriptions.Item label="阻断次数">
                    {displayValue(searchTrace.browserBlockedCount)}
                  </Descriptions.Item>
                  <Descriptions.Item label="超时预算">
                    {displayValue(searchTrace.searchTimeoutMillis)}
                  </Descriptions.Item>
                  <Descriptions.Item label="恢复检查点">
                    {displayValue(searchTrace.recoveryCheckpoint)}
                  </Descriptions.Item>
                  <Descriptions.Item label="恢复建议">
                    {displayValue(searchTrace.recoveryAdvice)}
                  </Descriptions.Item>
                  <Descriptions.Item label="轨迹版本">
                    {displayValue(searchTrace.traceVersion)}
                  </Descriptions.Item>
                  <Descriptions.Item label="降级原因">
                    {displayValue(searchTrace.degradationReason)}
                  </Descriptions.Item>
                </Descriptions>
              </Card>
            )}

            {searchTrace && asRecord(searchTrace.runtimePolicy) && (
              <Card size="small" title="搜索运行策略">
                <Descriptions column={1} size="small" bordered>
                  <Descriptions.Item label="启用结果页验证">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.verifyResultPage)}
                  </Descriptions.Item>
                  <Descriptions.Item label="最大重试次数">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.maxRetries)}
                  </Descriptions.Item>
                  <Descriptions.Item label="最小搜索间隔">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.minIntervalMillis)}
                  </Descriptions.Item>
                  <Descriptions.Item label="单任务最大 Query 数">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.maxSearchesPerTask)}
                  </Descriptions.Item>
                  <Descriptions.Item label="页面超时">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.pageTimeoutMillis)}
                  </Descriptions.Item>
                  <Descriptions.Item label="结果页打开上限">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.maxOpenResultPages)}
                  </Descriptions.Item>
                  <Descriptions.Item label="阻断信号列表">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.blockedSignals)}
                  </Descriptions.Item>
                  <Descriptions.Item label="默认恢复提示">
                    {displayValue(asRecord(searchTrace.runtimePolicy)?.recoveryHint)}
                  </Descriptions.Item>
                </Descriptions>
              </Card>
            )}

            {searchPlanSteps.length > 0 && (
              <Card size="small" title="搜索执行计划">
                <List
                  size="small"
                  bordered
                  dataSource={searchPlanSteps}
                  renderItem={(item) => {
                    const step = asRecord(item)
                    return (
                      <List.Item>
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space wrap>
                            {stepStatusTag(typeof step?.status === 'string' ? step.status : undefined)}
                            <span>{displayValue(step?.goal || step?.stepCode)}</span>
                            {typeof step?.stepCode === 'string' && <Tag>{step.stepCode}</Tag>}
                          </Space>
                          {typeof step?.message === 'string' && <span>{step.message}</span>}
                        </Space>
                      </List.Item>
                    )
                  }}
                />
              </Card>
            )}

            {searchProgressSnapshots.length > 0 && (
              <Card size="small" title="搜索进度历史">
                <List
                  size="small"
                  bordered
                  dataSource={searchProgressSnapshots}
                  renderItem={(snapshot, index) => (
                    <List.Item key={`${String(snapshot.currentStepCode || 'snapshot')}-${index}`}>
                      <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        <Space wrap>
                          {progressStatusTag(typeof snapshot.status === 'string' ? snapshot.status : undefined)}
                          <span>{displayValue(snapshot.currentStep || snapshot.currentStepCode)}</span>
                          {typeof snapshot.currentStepCode === 'string' && <Tag>{snapshot.currentStepCode}</Tag>}
                        </Space>
                        <span>{displayValue(snapshot.message)}</span>
                      </Space>
                    </List.Item>
                  )}
                />
              </Card>
            )}

            {!searchTrace && !searchProgress && detail.agentType === 'COLLECTOR' && (
              <Alert type="info" showIcon message="当前采集日志未记录结构化搜索轨迹" />
            )}

            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="输入">
                <DebugJson value={detail.inputData} emptyText="未记录输入" />
              </Descriptions.Item>
              <Descriptions.Item label="提示词">
                <DebugJson value={detail.promptUsed} emptyText="未记录提示词" />
              </Descriptions.Item>
              <Descriptions.Item label="输出">
                <DebugJson value={detail.outputData} emptyText="未记录输出" />
              </Descriptions.Item>
            </Descriptions>
          </Space>
        )}
      </Modal>
    </Space>
  )
}
