import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  List,
  Progress,
  Row,
  Space,
  Spin,
  Steps,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  FileTextOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SplitCellsOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { executeTask, getTask, getTaskNodes, resumeTask, rerunTaskNode, retryTask, stopTask } from '../api/client'
import AgentLogPanel from '../components/AgentLogPanel'
import type { NodeStatus, TaskInfo, TaskNodeInfo, TaskStatus } from '../types'
import {
  getAgentTypeText,
  getNodeDisplayName,
  getNodeNameLabel,
  getNodeStatusText,
  getReviewPassedText,
  getReviewSectionText,
  getReviewSeverityText,
  getReviewTypeText,
  getTaskStatusText,
} from '../utils/display'

const { Paragraph, Text } = Typography

const nodeStatusColorMap: Record<NodeStatus, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'success',
  FAILED: 'error',
  SKIPPED: 'default',
}

const taskStatusColorMap: Record<TaskStatus, string> = {
  PENDING: 'gold',
  RUNNING: 'blue',
  SUCCESS: 'green',
  STOPPED: 'orange',
  FAILED: 'red',
}

function isTerminalNodeStatus(status: NodeStatus) {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'SKIPPED'
}

function getNodeNoticeType(status: NodeStatus) {
  if (status === 'FAILED') return 'error' as const
  if (status === 'SKIPPED') return 'warning' as const
  return 'info' as const
}

function statusTag(status: NodeStatus) {
  return <Tag color={nodeStatusColorMap[status]}>{getNodeStatusText(status)}</Tag>
}

function taskStatusTag(status: TaskStatus) {
  return <Tag color={taskStatusColorMap[status]}>{getTaskStatusText(status)}</Tag>
}

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

function pretty(value?: string | null) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function isEvidenceNode(node: TaskNodeInfo) {
  return node.nodeName.startsWith('collect_sources') || node.nodeName === 'extract_schema'
}

function isReviewNode(node: TaskNodeInfo) {
  return node.agentType === 'REVIEWER'
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

function parseReviewPayload(value?: string | null) {
  const parsed = parseJson(value) as
    | {
        score?: number
        passed?: boolean
        summary?: string
        issues?: Array<{ type?: string; section?: string; severity?: string; suggestion?: string }>
        revisionPlan?: {
          rewriteRequired?: boolean
          summary?: string
          items?: Array<{ type?: string; section?: string; severity?: string; suggestion?: string }>
          rewriteGuidelines?: string[]
        }
      }
    | null

  if (!parsed) return null
  return {
    score: typeof parsed.score === 'number' ? parsed.score : null,
    passed: typeof parsed.passed === 'boolean' ? parsed.passed : null,
    summary: parsed.summary || null,
    issues: Array.isArray(parsed.issues) ? parsed.issues : [],
    revisionPlan: parsed.revisionPlan || null,
  }
}

export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const taskId = Number(id)

  const [task, setTask] = useState<TaskInfo | null>(null)
  const [nodes, setNodes] = useState<TaskNodeInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState(false)
  const [selectedNodeId, setSelectedNodeId] = useState<number | null>(null)

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

  const currentStep = useMemo(() => {
    const runningIndex = nodes.findIndex((node) => node.status === 'RUNNING')
    if (runningIndex >= 0) return runningIndex
    return nodes.filter((node) => isTerminalNodeStatus(node.status)).length
  }, [nodes])

  const activeRevisionNode = useMemo(
    () => nodes.find((node) => node.nodeName === 'rewrite_report') || null,
    [nodes],
  )

  const finalReviewNode = useMemo(
    () => nodes.find((node) => node.nodeName === 'quality_check_final') || null,
    [nodes],
  )

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

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 80 }} />
  }

  if (!task) {
    return <Alert type="warning" message="未找到任务" />
  }

  const competitors = parseJsonArray(task.competitorNames)
  const dimensions = parseJsonArray(task.analysisDimensions)

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

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card title="任务概览" className="work-card">
            <Progress
              percent={progressPercent}
              status={task.status === 'FAILED' || task.status === 'STOPPED' ? 'exception' : undefined}
              style={{ marginBottom: 16 }}
            />
            <Descriptions column={1} size="small">
              <Descriptions.Item label="产品">{task.subjectProduct}</Descriptions.Item>
              <Descriptions.Item label="竞品">
                <Space size={[4, 4]} wrap>
                  {competitors.map((name) => (
                    <Tag key={String(name)}>{String(name)}</Tag>
                  ))}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="分析维度">
                <Space size={[4, 4]} wrap>
                  {dimensions.map((item) => (
                    <Tag color="blue" key={String(item)}>
                      {String(item)}
                    </Tag>
                  ))}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="节点进度">
                {completedNodeCount}/{task.totalNodes}
              </Descriptions.Item>
            </Descriptions>

            {task.errorMessage && (
              <Alert
                type={task.status === 'STOPPED' ? 'warning' : 'error'}
                showIcon
                message={task.status === 'STOPPED' ? '任务已停止' : '任务执行失败'}
                description={task.errorMessage}
                style={{ marginTop: 16 }}
              />
            )}

            <Space style={{ marginTop: 16 }} wrap>
              {(task.status === 'PENDING' || task.status === 'FAILED') && (
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  loading={actionLoading}
                  onClick={handleExecute}
                >
                  {task.status === 'FAILED' ? '重新执行' : '开始执行'}
                </Button>
              )}
              {task.status === 'RUNNING' && (
                <Button danger loading={actionLoading} onClick={handleStop}>
                  停止任务
                </Button>
              )}
              {(task.status === 'FAILED' || task.status === 'STOPPED') && (
                <Button type="primary" loading={actionLoading} onClick={handleResume}>
                  恢复执行
                </Button>
              )}
              {(task.status === 'FAILED' || task.status === 'STOPPED') && (
                <Button icon={<ReloadOutlined />} loading={actionLoading} onClick={handleRetry}>
                  重置任务
                </Button>
              )}
              <Button
                icon={<FileTextOutlined />}
                disabled={task.status !== 'SUCCESS'}
                onClick={() => navigate(`/task/${taskId}/report`)}
              >
                查看报告
              </Button>
            </Space>

            <div style={{ marginTop: 16 }}>
              <Space wrap>
                {activeRevisionNode && (
                  <Tag color="orange" icon={<SplitCellsOutlined />}>
                    修订流程已就绪
                  </Tag>
                )}
                {finalReviewNode && (
                  <Tag color="green" icon={<CheckCircleOutlined />}>
                    已包含终审节点
                  </Tag>
                )}
              </Space>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Card title="执行流程" className="work-card">
            <Steps
              className="workflow-steps"
              current={currentStep}
              direction="vertical"
              size="small"
              items={nodes.map((node) => ({
                title: getNodeDisplayName(node),
                description: (
                  <Space direction="vertical" size={2}>
                    <Space wrap>
                      {statusTag(node.status)}
                      <Text type="secondary">{getAgentTypeText(node.agentType)}</Text>
                      {node.allowFailedDependency && <Tag color="gold">允许失败依赖</Tag>}
                    </Space>
                    {node.nodeNotes && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {node.nodeNotes}
                      </Text>
                    )}
                    {node.configSummary && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {node.configSummary}
                      </Text>
                    )}
                    {node.startedAt && (
                      <Text type="secondary">
                        {dayjs(node.startedAt).format('HH:mm:ss')}
                        {node.completedAt ? ` - ${dayjs(node.completedAt).format('HH:mm:ss')}` : ''}
                      </Text>
                    )}
                  </Space>
                ),
                status:
                  node.status === 'FAILED'
                    ? 'error'
                    : isTerminalNodeStatus(node.status)
                      ? 'finish'
                      : node.status === 'RUNNING'
                        ? 'process'
                        : 'wait',
              }))}
            />
          </Card>
        </Col>
      </Row>

      <Tabs
        className="detail-tabs"
        items={[
          {
            key: 'nodes',
            label: '节点追踪',
            children: (
              <Row gutter={[16, 16]}>
                {nodes.map((node) => (
                  <Col xs={24} lg={12} key={node.id}>
                    <Card
                      size="small"
                      title={
                        <Space>
                          <span>{getNodeDisplayName(node)}</span>
                          {statusTag(node.status)}
                        </Space>
                      }
                      extra={
                        <Space size={4}>
                          <Button type="link" onClick={() => setSelectedNodeId(node.id)}>
                            查看追踪
                          </Button>
                          {task.status !== 'RUNNING' && (
                            <Button type="link" loading={actionLoading} onClick={() => void handleRerunNode(node.nodeName)}>
                              从此节点重跑
                            </Button>
                          )}
                        </Space>
                      }
                      className="work-card"
                    >
                      {node.errorMessage && (
                        <Alert
                          type={getNodeNoticeType(node.status)}
                          showIcon
                          message={node.errorMessage}
                          style={{ marginBottom: 12 }}
                        />
                      )}
                      <Descriptions column={1} size="small" bordered>
                        <Descriptions.Item label="节点">{getNodeNameLabel(node.nodeName)}</Descriptions.Item>
                        <Descriptions.Item label="智能体">{getAgentTypeText(node.agentType)}</Descriptions.Item>
                        <Descriptions.Item label="依赖节点">{node.dependsOn || '[]'}</Descriptions.Item>
                        <Descriptions.Item label="配置">{node.nodeConfig || '暂无配置记录'}</Descriptions.Item>
                        <Descriptions.Item label="输入">{node.inputSummary || '暂无输入记录'}</Descriptions.Item>
                        <Descriptions.Item label="输出">{node.outputSummary || '暂无输出记录'}</Descriptions.Item>
                      </Descriptions>
                    </Card>
                  </Col>
                ))}
              </Row>
            ),
          },
          {
            key: 'logs',
            label: '智能体日志',
            children: <AgentLogPanel taskId={taskId} autoRefresh={task.status === 'RUNNING'} />,
          },
        ]}
      />

      <Drawer
        title={selectedNode ? `${getNodeDisplayName(selectedNode)}追踪` : '节点追踪'}
        open={Boolean(selectedNode)}
        onClose={() => setSelectedNodeId(null)}
        width={820}
      >
        {selectedNode && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            {task.status !== 'RUNNING' && (
              <Button type="primary" loading={actionLoading} onClick={() => void handleRerunNode(selectedNode.nodeName)}>
                从该节点重跑
              </Button>
            )}

            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="状态">{statusTag(selectedNode.status)}</Descriptions.Item>
              <Descriptions.Item label="节点">{getNodeNameLabel(selectedNode.nodeName)}</Descriptions.Item>
              <Descriptions.Item label="智能体">{getAgentTypeText(selectedNode.agentType)}</Descriptions.Item>
              <Descriptions.Item label="依赖节点">{selectedNode.dependsOn || '[]'}</Descriptions.Item>
              <Descriptions.Item label="节点说明">{selectedNode.nodeNotes || '-'}</Descriptions.Item>
              <Descriptions.Item label="配置">
                <Paragraph code className="code-block">
                  {pretty(selectedNode.nodeConfig) || '暂无配置记录'}
                </Paragraph>
              </Descriptions.Item>
              <Descriptions.Item label="输入数据">
                <Paragraph code className="code-block">
                  {pretty(selectedNode.inputData) || selectedNode.inputSummary || '暂无输入记录'}
                </Paragraph>
              </Descriptions.Item>
              <Descriptions.Item label="输出数据">
                <Paragraph code className="code-block">
                  {pretty(selectedNode.outputData) || selectedNode.outputSummary || '暂无输出记录'}
                </Paragraph>
              </Descriptions.Item>
            </Descriptions>

            {isReviewNode(selectedNode) && selectedReviewPayload && (
              <Card size="small" title="评审结论">
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <Space wrap>
                    {selectedReviewPayload.score != null && (
                      <Tag color="blue">评分 {selectedReviewPayload.score}/100</Tag>
                    )}
                    {selectedReviewPayload.passed != null && (
                      <Tag color={selectedReviewPayload.passed ? 'green' : 'orange'}>
                        {getReviewPassedText(selectedReviewPayload.passed)}
                      </Tag>
                    )}
                  </Space>

                  {selectedReviewPayload.summary && (
                    <Alert type="info" showIcon message="评审摘要" description={selectedReviewPayload.summary} />
                  )}

                  {selectedReviewPayload.issues.length > 0 && (
                    <List
                      size="small"
                      bordered
                      header="问题清单"
                      dataSource={selectedReviewPayload.issues}
                      renderItem={(item) => (
                        <List.Item>
                          <Space direction="vertical" size={2}>
                            <Space wrap>
                              <Tag
                                color={
                                  item.severity === 'ERROR' ? 'red' : item.severity === 'WARNING' ? 'orange' : 'blue'
                                }
                              >
                                {getReviewSeverityText(item.severity)}
                              </Tag>
                              <Text>{getReviewSectionText(item.section)}</Text>
                              <Text type="secondary">{getReviewTypeText(item.type)}</Text>
                            </Space>
                            <Text>{item.suggestion || '暂无建议说明'}</Text>
                          </Space>
                        </List.Item>
                      )}
                    />
                  )}

                  {selectedReviewPayload.revisionPlan && (
                    <List
                      size="small"
                      bordered
                      header="修订计划"
                      dataSource={[
                        ...(selectedReviewPayload.revisionPlan.summary
                          ? [selectedReviewPayload.revisionPlan.summary]
                          : []),
                        ...((selectedReviewPayload.revisionPlan.rewriteGuidelines || []) as string[]),
                      ]}
                      renderItem={(item) => <List.Item>{item}</List.Item>}
                    />
                  )}
                </Space>
              </Card>
            )}

            {selectedNodeDependencies.length > 0 && (
              <Card size="small" title="上游节点">
                <List
                  size="small"
                  bordered
                  dataSource={selectedNodeDependencies}
                  renderItem={(item) => (
                    <List.Item
                      actions={[
                        <Button key={item.id} type="link" onClick={() => setSelectedNodeId(item.id)}>
                          查看追踪
                        </Button>,
                      ]}
                    >
                      <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        <Space wrap>
                          <Text strong>{getNodeDisplayName(item)}</Text>
                          {statusTag(item.status)}
                        </Space>
                        <Text type="secondary">{item.outputSummary || item.errorMessage || '暂无输出'}</Text>
                      </Space>
                    </List.Item>
                  )}
                />
              </Card>
            )}

            {selectedNodeEvidenceIds.length > 0 && (
              <Card size="small" title={isEvidenceNode(selectedNode) ? '已捕获证据' : '证据追踪'}>
                <Space wrap>
                  {selectedNodeEvidenceIds.map((evidenceId) => (
                    <Tag color="green" key={evidenceId}>
                      {evidenceId}
                    </Tag>
                  ))}
                </Space>
              </Card>
            )}
          </Space>
        )}
      </Drawer>
    </div>
  )
}
