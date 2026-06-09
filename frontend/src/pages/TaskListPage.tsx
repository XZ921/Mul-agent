import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
import type { TaskInfo, TaskListSummary, TaskStatus } from '../types'
import { appMessage } from '../utils/appMessage'
import { getTaskStatusText } from '../utils/display'
import {
  buildTaskListOverview,
  getTaskDetailEntryGuidance,
  getTaskProgressPercent,
  safeParseStringArray,
} from '../utils/taskPresentation'

const { Text } = Typography
const TASK_LIST_PAGE_SIZE = 10

const statusColorMap: Record<TaskStatus, string> = {
  PENDING: 'gold',
  RUNNING: 'blue',
  SUCCESS: 'green',
  STOPPED: 'orange',
  FAILED: 'red',
}

export default function TaskListPage() {
  const navigate = useNavigate()
  const [tasks, setTasks] = useState<TaskInfo[]>([])
  const [attentionItems, setAttentionItems] = useState<TaskInfo[]>([])
  const [summary, setSummary] = useState<TaskListSummary>({
    total: 0,
    running: 0,
    success: 0,
    failed: 0,
    stopped: 0,
    avgProgress: 0,
  })
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [currentPage, setCurrentPage] = useState(1)
  const statusFilterRef = useRef<string | undefined>(undefined)
  const currentPageRef = useRef(1)
  const taskDetailGuidance = getTaskDetailEntryGuidance('list')

  const fetchTasks = useCallback(async (page = currentPageRef.current) => {
    setLoading(true)
    try {
      const res = await listTasks(statusFilterRef.current, page, TASK_LIST_PAGE_SIZE)
      const nextPage = res.data?.pageNum ?? page
      currentPageRef.current = nextPage
      setCurrentPage(nextPage)
      setTasks(res.data?.items || [])
      setAttentionItems(res.data?.attentionItems || [])
      setSummary(res.data?.summary || {
        total: 0,
        running: 0,
        success: 0,
        failed: 0,
        stopped: 0,
        avgProgress: 0,
      })
      setTotal(res.data?.total || 0)
    } catch {
      appMessage.error('获取任务列表失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    statusFilterRef.current = statusFilter
    currentPageRef.current = 1
    setCurrentPage(1)
    void fetchTasks(1)
  }, [fetchTasks, statusFilter])

  const hasRunningTasks = useMemo(
    () => summary.running > 0,
    [summary.running],
  )

  useEffect(() => {
    // 列表页只保留一条轻量刷新定时器。
    // 只要当前仍存在运行中任务，就继续按统一节奏刷新；
    // 不能因为每次返回了新的 tasks 数组就反复销毁/重建定时器。
    if (!hasRunningTasks) return
    const timer = window.setInterval(() => {
      void fetchTasks()
    }, 3000)
    return () => window.clearInterval(timer)
  }, [fetchTasks, hasRunningTasks])

  const overview = useMemo(() => buildTaskListOverview(summary), [summary])
  const visibleAttentionItems = useMemo(
    () => attentionItems.filter((task) => task.status === 'FAILED' || task.status === 'STOPPED' || task.status === 'RUNNING'),
    [attentionItems],
  )

  const handleDelete = async (id: number) => {
    try {
      await deleteTask(id)
      appMessage.success('任务已删除')
      await fetchTasks(currentPageRef.current)
    } catch {
      appMessage.error('删除失败，运行中的任务不可删除')
    }
  }

  const columns = [
    {
      title: '任务',
      dataIndex: 'taskName',
      render: (text: string, record: TaskInfo) => {
        const competitors = safeParseStringArray(record.competitorNames)
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
        const percent = getTaskProgressPercent(record)
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
          overview.tone === 'danger'
            ? 'hero-tone-danger'
            : overview.tone === 'active'
              ? 'hero-tone-active'
              : overview.tone === 'success'
                ? 'hero-tone-success'
                : 'hero-tone-default'
        }`}
      >
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <div className="hero-shell">
            <div className="hero-copy">
              <Space wrap>
                <Tag color={overview.tone === 'danger' ? 'red' : overview.tone === 'active' ? 'blue' : overview.tone === 'success' ? 'green' : 'default'}>
                  {overview.badgeText}
                </Tag>
                <Text type="secondary">{overview.entryHint}</Text>
              </Space>
              <div className="hero-title">任务入口与执行总览</div>
              <Text type="secondary" className="hero-description">
                {overview.heroDescription}
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
                onChange={(value) => {
                  setStatusFilter(value)
                }}
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
              pagination={{
                current: currentPage,
                pageSize: TASK_LIST_PAGE_SIZE,
                total,
                showSizeChanger: false,
                onChange: (page) => {
                  currentPageRef.current = page
                  setCurrentPage(page)
                  void fetchTasks(page)
                },
              }}
              locale={{
                emptyText: <Empty description="暂无分析任务" />,
              }}
            />
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="需要关注" className="work-card">
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message={taskDetailGuidance.message}
              description={taskDetailGuidance.description}
            />
            {visibleAttentionItems.length > 0 ? (
              <List
                dataSource={visibleAttentionItems}
                renderItem={(task) => (
                  <List.Item>
                    <Space direction="vertical" size={8} style={{ width: '100%' }}>
                      <Space wrap>
                        <Tag color={statusColorMap[task.status]}>{getTaskStatusText(task.status)}</Tag>
                        <Text strong>{task.taskName}</Text>
                      </Space>
                      <Text type="secondary">{task.subjectProduct}</Text>
                      <Progress
                        percent={getTaskProgressPercent(task)}
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
