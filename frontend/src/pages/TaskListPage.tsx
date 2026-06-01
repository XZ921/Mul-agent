import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  List,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  DeleteOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { deleteTask, listTasks } from '../api/client'
import type { TaskInfo, TaskStatus } from '../types'
import { getTaskStatusText } from '../utils/display'

const { Text } = Typography

const statusColorMap: Record<TaskStatus, string> = {
  PENDING: 'gold',
  RUNNING: 'blue',
  SUCCESS: 'green',
  STOPPED: 'orange',
  FAILED: 'red',
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

function taskProgressPercent(task: TaskInfo) {
  return task.totalNodes ? Math.round((task.completedNodes / task.totalNodes) * 100) : 0
}

export default function TaskListPage() {
  const navigate = useNavigate()
  const [tasks, setTasks] = useState<TaskInfo[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string | undefined>()

  const fetchTasks = async () => {
    setLoading(true)
    try {
      const res = await listTasks(statusFilter)
      setTasks((res.data || []).slice(0, 10))
    } catch {
      message.error('获取任务列表失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void fetchTasks()
  }, [statusFilter])

  useEffect(() => {
    const hasRunning = tasks.some((task) => task.status === 'RUNNING')
    if (!hasRunning) return
    const timer = window.setInterval(fetchTasks, 3000)
    return () => window.clearInterval(timer)
  }, [tasks, statusFilter])

  const summary = useMemo(() => {
    const total = tasks.length
    const running = tasks.filter((task) => task.status === 'RUNNING').length
    const success = tasks.filter((task) => task.status === 'SUCCESS').length
    const failed = tasks.filter((task) => task.status === 'FAILED').length
    const stopped = tasks.filter((task) => task.status === 'STOPPED').length
    const avgProgress = total
      ? Math.round(tasks.reduce((sum, task) => sum + taskProgressPercent(task), 0) / total)
      : 0
    return { total, running, success, failed, stopped, avgProgress }
  }, [tasks])

  const focusTasks = useMemo(
    () => tasks.filter((task) => task.status === 'FAILED' || task.status === 'RUNNING' || task.status === 'STOPPED').slice(0, 4),
    [tasks],
  )

  const heroText = useMemo(() => {
    if (summary.failed > 0) {
      return `当前有 ${summary.failed} 个失败任务，建议优先进入详情页处理节点异常或证据风险。`
    }
    if (summary.running > 0) {
      return `当前有 ${summary.running} 个任务正在运行，系统会自动轮询最新进度。`
    }
    if (summary.success > 0) {
      return `最近已有 ${summary.success} 个任务成功完成，可以直接查看报告或复用结论。`
    }
    return '从这里创建新任务、追踪执行进度，并在报告页处理质检与修订闭环。'
  }, [summary.failed, summary.running, summary.success])

  const handleDelete = async (id: number) => {
    try {
      await deleteTask(id)
      message.success('任务已删除')
      await fetchTasks()
    } catch {
      message.error('删除失败，运行中的任务不可删除')
    }
  }

  const columns = [
    {
      title: '任务',
      dataIndex: 'taskName',
      render: (text: string, record: TaskInfo) => {
        const competitors = parseJsonArray(record.competitorNames).map(String)
        return (
          <Space direction="vertical" size={4}>
            <a onClick={() => navigate(`/task/${record.id}`)}>{text}</a>
            <Text type="secondary">{record.subjectProduct}</Text>
            <Text type="secondary">
              {competitors.length > 0
                ? `竞品：${competitors.slice(0, 3).join('、')}${competitors.length > 3 ? ` 等 ${competitors.length} 个` : ''}`
                : '暂未记录竞品'}
            </Text>
          </Space>
        )
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (status: TaskStatus) => <Tag color={statusColorMap[status]}>{getTaskStatusText(status)}</Tag>,
    },
    {
      title: '进度',
      key: 'progress',
      width: 220,
      render: (_: unknown, record: TaskInfo) => {
        const percent = taskProgressPercent(record)
        return (
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Progress percent={percent} size="small" status={record.status === 'FAILED' ? 'exception' : undefined} />
            <Text type="secondary">{`${record.completedNodes}/${record.totalNodes} 节点已完成`}</Text>
          </Space>
        )
      },
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (time: string) => dayjs(time).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_: unknown, record: TaskInfo) => (
        <Space>
          <Tooltip title="查看详情">
            <Button icon={<EyeOutlined />} onClick={() => navigate(`/task/${record.id}`)} />
          </Tooltip>
          <Tooltip title="查看报告">
            <Button
              icon={<FileTextOutlined />}
              disabled={record.status !== 'SUCCESS'}
              onClick={() => navigate(`/task/${record.id}/report`)}
            />
          </Tooltip>
          <Tooltip title="删除任务">
            <Popconfirm
              title="确认删除该任务吗？"
              description={`删除后将移除任务「${record.taskName}」及其关联产物，此操作不可撤销。`}
              okText="确认删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              disabled={record.status === 'RUNNING'}
              onConfirm={() => void handleDelete(record.id)}
            >
              <Button
                danger
                icon={<DeleteOutlined />}
                disabled={record.status === 'RUNNING'}
              />
            </Popconfirm>
          </Tooltip>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="page-header">
        <h2>竞品分析任务</h2>
        <p>把最近任务、执行进度和需要关注的异常收敛到同一个入口页里。</p>
      </div>

      <Card
        className={`work-card hero-card ${
          summary.failed > 0 ? 'hero-tone-danger' : summary.running > 0 ? 'hero-tone-active' : 'hero-tone-success'
        }`}
      >
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <div className="hero-shell">
            <div className="hero-copy">
              <Space wrap>
                <Tag color={summary.failed > 0 ? 'red' : summary.running > 0 ? 'blue' : 'green'}>
                  {summary.failed > 0 ? '存在阻塞任务' : summary.running > 0 ? '执行中任务活跃' : '当前任务态稳定'}
                </Tag>
                <Text type="secondary">最近只保留并展示 10 条任务</Text>
              </Space>
              <div className="hero-title">任务入口与执行总览</div>
              <Text type="secondary" className="hero-description">
                {heroText}
              </Text>
            </div>
            <Space wrap className="hero-actions">
              <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/task/new')}>
                新建任务
              </Button>
              <Button icon={<ReloadOutlined />} onClick={() => void fetchTasks()}>
                刷新列表
              </Button>
            </Space>
          </div>

          <div className="hero-stats">
            <div className="hero-stat">
              <span className="hero-stat-value">{summary.total}</span>
              <span className="hero-stat-label">最近任务</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-value">{summary.running}</span>
              <span className="hero-stat-label">执行中</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-value">{summary.failed}</span>
              <span className="hero-stat-label">失败</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-value">{`${summary.avgProgress}%`}</span>
              <span className="hero-stat-label">平均完成度</span>
            </div>
          </div>
        </Space>
      </Card>

      <Row gutter={[16, 16]} style={{ marginTop: 0 }}>
        <Col xs={24} lg={16}>
          <Card className="work-card">
            <div className="table-toolbar">
              <Text strong>最近任务列表</Text>
              <Select
                allowClear
                placeholder="按状态筛选"
                style={{ width: 140 }}
                value={statusFilter}
                onChange={(value) => setStatusFilter(value)}
                options={[
                  { label: '待执行', value: 'PENDING' },
                  { label: '执行中', value: 'RUNNING' },
                  { label: '已完成', value: 'SUCCESS' },
                  { label: '已停止', value: 'STOPPED' },
                  { label: '失败', value: 'FAILED' },
                ]}
              />
            </div>

            <Table
              rowKey="id"
              columns={columns}
              dataSource={tasks}
              loading={loading}
              pagination={false}
              locale={{
                emptyText: <Empty description="暂无分析任务" />,
              }}
            />
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="需要关注" className="work-card">
            {focusTasks.length > 0 ? (
              <List
                dataSource={focusTasks}
                renderItem={(task) => (
                  <List.Item>
                    <Space direction="vertical" size={8} style={{ width: '100%' }}>
                      <Space wrap>
                        <Tag color={statusColorMap[task.status]}>{getTaskStatusText(task.status)}</Tag>
                        <Text strong>{task.taskName}</Text>
                      </Space>
                      <Text type="secondary">{task.subjectProduct}</Text>
                      <Progress
                        percent={taskProgressPercent(task)}
                        size="small"
                        status={task.status === 'FAILED' ? 'exception' : undefined}
                      />
                      <Space wrap>
                        <Button type="link" onClick={() => navigate(`/task/${task.id}`)}>
                          进入详情
                        </Button>
                        <Button
                          type="link"
                          disabled={task.status !== 'SUCCESS'}
                          onClick={() => navigate(`/task/${task.id}/report`)}
                        >
                          查看报告
                        </Button>
                      </Space>
                    </Space>
                  </List.Item>
                )}
              />
            ) : (
              <Alert
                type="success"
                showIcon
                message="当前没有需要优先处理的任务"
                description="当任务失败、运行中或已停止时，这里会优先展示最值得关注的对象。"
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  )
}
