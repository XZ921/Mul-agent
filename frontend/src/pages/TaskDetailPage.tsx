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
import { executeTask, getTask, getTaskNodes, resumeTask, retryTask } from '../api/client'
import AgentLogPanel from '../components/AgentLogPanel'
import type { NodeStatus, TaskInfo, TaskNodeInfo, TaskStatus } from '../types'

const { Paragraph, Text } = Typography

const nodeStatusMap: Record<NodeStatus, { color: string; text: string }> = {
  PENDING: { color: 'default', text: 'Pending' },
  RUNNING: { color: 'processing', text: 'Running' },
  SUCCESS: { color: 'success', text: 'Success' },
  FAILED: { color: 'error', text: 'Failed' },
  SKIPPED: { color: 'default', text: 'Skipped' },
}

const taskStatusMap: Record<TaskStatus, { color: string; text: string }> = {
  PENDING: { color: 'gold', text: 'Pending' },
  RUNNING: { color: 'blue', text: 'Running' },
  SUCCESS: { color: 'green', text: 'Success' },
  FAILED: { color: 'red', text: 'Failed' },
}

// 统一节点状态标签，避免列表页、抽屉面板出现展示不一致。
function statusTag(status: NodeStatus) {
  const item = nodeStatusMap[status]
  return <Tag color={item.color}>{item.text}</Tag>
}

// 统一任务状态标签，便于任务级状态与节点级状态区分。
function taskStatusTag(status: TaskStatus) {
  const item = taskStatusMap[status]
  return <Tag color={item.color}>{item.text}</Tag>
}

// 后端部分字段以 JSON 字符串存储，前端统一做安全解析，避免页面因脏数据直接崩掉。
function parseJsonArray(value: string | null) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

// 通用 JSON 解析器，节点配置、输入输出详情都依赖它做容错展示。
function parseJson(value?: string | null) {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

// 把原始 JSON 美化成可读文本，便于节点级溯源面板直接查看配置和产物。
function pretty(value?: string | null) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

// 采集与抽取节点是证据链的核心入口，抽屉里会针对它们展示“已捕获证据”视角。
function isEvidenceNode(node: TaskNodeInfo) {
  return node.nodeName.startsWith('collect_sources') || node.nodeName === 'extract_schema'
}

// Reviewer 节点需要额外展示评分、问题列表、修订计划等结构化内容。
function isReviewNode(node: TaskNodeInfo) {
  return node.agentType === 'REVIEWER'
}

// 不同节点输出里的证据 ID 结构不完全一致，这里统一抽取成一组可回溯的 evidenceId。
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

// Reviewer 输出既承载评分结果，也承载重写计划，这里做一次前端归一化。
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
  const [selectedNode, setSelectedNode] = useState<TaskNodeInfo | null>(null)

  // 任务详情和节点列表总是成对刷新，确保概览区、步骤条、溯源面板看到的是同一份快照。
  const fetchData = useCallback(async () => {
    try {
      const [taskRes, nodesRes] = await Promise.all([getTask(taskId), getTaskNodes(taskId)])
      setTask(taskRes.data)
      setNodes(nodesRes.data || [])
      if (selectedNode) {
        const nextSelected = (nodesRes.data || []).find((node) => node.id === selectedNode.id) || null
        setSelectedNode(nextSelected)
      }
    } catch {
      message.error('Failed to load task detail')
    } finally {
      setLoading(false)
    }
  }, [taskId, selectedNode])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // 运行中的任务持续轮询，支撑“节点级进度面板”实时刷新。
  useEffect(() => {
    if (task?.status !== 'RUNNING') return
    const timer = window.setInterval(fetchData, 3000)
    return () => window.clearInterval(timer)
  }, [task?.status, fetchData])

  // 任务总体进度依赖后端累计完成节点数，适合做任务级概览展示。
  const progressPercent = useMemo(() => {
    if (!task?.totalNodes) return 0
    return Math.round((task.completedNodes / task.totalNodes) * 100)
  }, [task])

  // 步骤条优先定位正在执行的节点；如果没有运行中节点，则退化为已完成节点数量。
  const currentStep = useMemo(() => {
    const runningIndex = nodes.findIndex((node) => node.status === 'RUNNING')
    if (runningIndex >= 0) return runningIndex
    return nodes.filter((node) => node.status === 'SUCCESS').length
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

  // 抽屉中的“上游节点”面板依赖 dependsOn，把当前节点依赖链直接映射成可点击实体。
  const selectedNodeDependencies = useMemo(() => {
    const dependencyNames = parseJsonArray(selectedNode?.dependsOn || null).map(String)
    return dependencyNames
      .map((name) => nodeMap.get(name))
      .filter((item): item is TaskNodeInfo => Boolean(item))
  }, [nodeMap, selectedNode])

  // 节点级溯源的关键：递归回看当前节点及其上游节点，把所有 evidenceId 聚合出来。
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

  // 失败后重新执行整条 DAG，适合首次运行失败或用户明确要求重新跑全流程。
  const handleExecute = async () => {
    setActionLoading(true)
    try {
      await executeTask(taskId)
      message.success('Task started')
      await fetchData()
    } catch {
      message.error('Execute task failed')
    } finally {
      setActionLoading(false)
    }
  }

  // Retry 会把任务重置回初始状态，用于彻底清理失败现场重新开始。
  const handleRetry = async () => {
    setActionLoading(true)
    try {
      await retryTask(taskId)
      message.success('Task reset')
      await fetchData()
    } catch {
      message.error('Retry task failed')
    } finally {
      setActionLoading(false)
    }
  }

  // Resume 会尝试基于已有检查点继续跑，适合 V2 的恢复执行场景。
  const handleResume = async () => {
    setActionLoading(true)
    try {
      await resumeTask(taskId)
      message.success('Task resumed from existing checkpoints')
      await fetchData()
    } catch {
      message.error('Resume task failed')
    } finally {
      setActionLoading(false)
    }
  }

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 80 }} />
  }

  if (!task) {
    return <Alert type="warning" message="Task not found" />
  }

  const competitors = parseJsonArray(task.competitorNames)
  const dimensions = parseJsonArray(task.analysisDimensions)

  return (
    <div>
      <div className="page-toolbar">
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
          Back
        </Button>
      </div>

      <div className="page-header">
        <h2>{task.taskName}</h2>
        <Space wrap>
          {taskStatusTag(task.status)}
          <Text type="secondary">Created {dayjs(task.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card title="Overview" className="work-card">
            <Progress
              percent={progressPercent}
              status={task.status === 'FAILED' ? 'exception' : undefined}
              style={{ marginBottom: 16 }}
            />
            <Descriptions column={1} size="small">
              <Descriptions.Item label="Product">{task.subjectProduct}</Descriptions.Item>
              <Descriptions.Item label="Competitors">
                <Space size={[4, 4]} wrap>
                  {competitors.map((name) => (
                    <Tag key={String(name)}>{String(name)}</Tag>
                  ))}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="Dimensions">
                <Space size={[4, 4]} wrap>
                  {dimensions.map((item) => (
                    <Tag color="blue" key={String(item)}>
                      {String(item)}
                    </Tag>
                  ))}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="Nodes">
                {task.completedNodes}/{task.totalNodes}
              </Descriptions.Item>
            </Descriptions>

            {task.errorMessage && (
              <Alert
                type="error"
                showIcon
                message="Task failed"
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
                  {task.status === 'FAILED' ? 'Run Again' : 'Start'}
                </Button>
              )}
              {task.status === 'FAILED' && (
                <Button type="primary" loading={actionLoading} onClick={handleResume}>
                  Resume
                </Button>
              )}
              {task.status === 'FAILED' && (
                <Button icon={<ReloadOutlined />} loading={actionLoading} onClick={handleRetry}>
                  Reset Task
                </Button>
              )}
              <Button
                icon={<FileTextOutlined />}
                disabled={task.status !== 'SUCCESS'}
                onClick={() => navigate(`/task/${taskId}/report`)}
              >
                View Report
              </Button>
            </Space>

            <div style={{ marginTop: 16 }}>
              <Space wrap>
                {activeRevisionNode && (
                  <Tag color="orange" icon={<SplitCellsOutlined />}>
                    Revision flow ready
                  </Tag>
                )}
                {finalReviewNode && (
                  <Tag color="green" icon={<CheckCircleOutlined />}>
                    Final review node present
                  </Tag>
                )}
              </Space>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Card title="Execution Flow" className="work-card">
            {/* 步骤条承载任务级流程视图，帮助用户快速判断当前卡在哪个节点。 */}
            <Steps
              current={currentStep}
              items={nodes.map((node) => ({
                title: node.displayName,
                description: (
                  <Space direction="vertical" size={2}>
                    <Space wrap>
                      {statusTag(node.status)}
                      <Text type="secondary">{node.agentType}</Text>
                      {node.allowFailedDependency && <Tag color="gold">Allow-fail</Tag>}
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
                    : node.status === 'SUCCESS'
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
            label: 'Node Trace',
            children: (
              <Row gutter={[16, 16]}>
                {nodes.map((node) => (
                  <Col xs={24} lg={12} key={node.id}>
                    <Card
                      size="small"
                      title={
                        <Space>
                          <span>{node.displayName}</span>
                          {statusTag(node.status)}
                        </Space>
                      }
                      extra={
                        <Button type="link" onClick={() => setSelectedNode(node)}>
                          Trace
                        </Button>
                      }
                      className="work-card"
                    >
                      {node.errorMessage && (
                        <Alert type="error" showIcon message={node.errorMessage} style={{ marginBottom: 12 }} />
                      )}
                      <Descriptions column={1} size="small" bordered>
                        <Descriptions.Item label="Node">{node.nodeName}</Descriptions.Item>
                        <Descriptions.Item label="Agent">{node.agentType}</Descriptions.Item>
                        <Descriptions.Item label="Depends On">{node.dependsOn || '[]'}</Descriptions.Item>
                        <Descriptions.Item label="Config">{node.nodeConfig || 'No config recorded'}</Descriptions.Item>
                        <Descriptions.Item label="Input">{node.inputSummary || 'No input recorded'}</Descriptions.Item>
                        <Descriptions.Item label="Output">{node.outputSummary || 'No output recorded'}</Descriptions.Item>
                      </Descriptions>
                    </Card>
                  </Col>
                ))}
              </Row>
            ),
          },
          {
            key: 'logs',
            label: 'Agent Logs',
            children: <AgentLogPanel taskId={taskId} autoRefresh={task.status === 'RUNNING'} />,
          },
        ]}
      />

      <Drawer
        title={selectedNode ? `${selectedNode.displayName} Trace` : 'Node Trace'}
        open={Boolean(selectedNode)}
        onClose={() => setSelectedNode(null)}
        width={820}
      >
        {selectedNode && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            {/* 第一层先把节点配置、输入、输出并排收拢，形成单节点的完整执行快照。 */}
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="Status">{statusTag(selectedNode.status)}</Descriptions.Item>
              <Descriptions.Item label="Node">{selectedNode.nodeName}</Descriptions.Item>
              <Descriptions.Item label="Agent">{selectedNode.agentType}</Descriptions.Item>
              <Descriptions.Item label="Depends On">{selectedNode.dependsOn || '[]'}</Descriptions.Item>
              <Descriptions.Item label="Node Notes">{selectedNode.nodeNotes || '-'}</Descriptions.Item>
              <Descriptions.Item label="Config">
                <Paragraph code className="code-block">
                  {pretty(selectedNode.nodeConfig) || 'No config recorded'}
                </Paragraph>
              </Descriptions.Item>
              <Descriptions.Item label="Input Data">
                <Paragraph code className="code-block">
                  {pretty(selectedNode.inputData) || selectedNode.inputSummary || 'No input recorded'}
                </Paragraph>
              </Descriptions.Item>
              <Descriptions.Item label="Output Data">
                <Paragraph code className="code-block">
                  {pretty(selectedNode.outputData) || selectedNode.outputSummary || 'No output recorded'}
                </Paragraph>
              </Descriptions.Item>
            </Descriptions>

            {/* Reviewer 节点单独展开评分结论和修订计划，补齐 Writer / Reviewer 闭环可视化。 */}
            {isReviewNode(selectedNode) && selectedReviewPayload && (
              <Card size="small" title="Review Decision">
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <Space wrap>
                    {selectedReviewPayload.score != null && (
                      <Tag color="blue">Score {selectedReviewPayload.score}/100</Tag>
                    )}
                    {selectedReviewPayload.passed != null && (
                      <Tag color={selectedReviewPayload.passed ? 'green' : 'orange'}>
                        {selectedReviewPayload.passed ? 'Passed' : 'Needs revision'}
                      </Tag>
                    )}
                  </Space>

                  {selectedReviewPayload.summary && (
                    <Alert
                      type="info"
                      showIcon
                      message="Reviewer Summary"
                      description={selectedReviewPayload.summary}
                    />
                  )}

                  {selectedReviewPayload.issues.length > 0 && (
                    <List
                      size="small"
                      bordered
                      header="Issues"
                      dataSource={selectedReviewPayload.issues}
                      renderItem={(item) => (
                        <List.Item>
                          <Space direction="vertical" size={2}>
                            <Space wrap>
                              <Tag
                                color={
                                  item.severity === 'ERROR'
                                    ? 'red'
                                    : item.severity === 'WARNING'
                                      ? 'orange'
                                      : 'blue'
                                }
                              >
                                {item.severity || 'INFO'}
                              </Tag>
                              <Text>{item.section || 'General'}</Text>
                              <Text type="secondary">{item.type || 'Issue'}</Text>
                            </Space>
                            <Text>{item.suggestion || 'No suggestion provided'}</Text>
                          </Space>
                        </List.Item>
                      )}
                    />
                  )}

                  {selectedReviewPayload.revisionPlan && (
                    <List
                      size="small"
                      bordered
                      header="Revision Plan"
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

            {/* 上游节点列表用于把“当前输出来自哪里”串起来，支持逐层回点查看。 */}
            {selectedNodeDependencies.length > 0 && (
              <Card size="small" title="Upstream Nodes">
                <List
                  size="small"
                  bordered
                  dataSource={selectedNodeDependencies}
                  renderItem={(item) => (
                    <List.Item
                      actions={[
                        <Button key={item.id} type="link" onClick={() => setSelectedNode(item)}>
                          Trace
                        </Button>,
                      ]}
                    >
                      <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        <Space wrap>
                          <Text strong>{item.displayName}</Text>
                          {statusTag(item.status)}
                        </Space>
                        <Text type="secondary">{item.outputSummary || item.errorMessage || 'No output yet'}</Text>
                      </Space>
                    </List.Item>
                  )}
                />
              </Card>
            )}

            {/* 证据标签是节点级溯源的最后一环，用来回答“这段结果最终基于哪些证据”。 */}
            {selectedNodeEvidenceIds.length > 0 && (
              <Card size="small" title={isEvidenceNode(selectedNode) ? 'Evidence Captured' : 'Evidence Trace'}>
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
