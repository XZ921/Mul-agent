import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, Empty, Progress, Select, Space, Table, Tag, Tooltip, message } from 'antd'
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

const statusMap: Record<TaskStatus, { color: string; text: string }> = {
  PENDING: { color: 'gold', text: '待执行' },
  RUNNING: { color: 'blue', text: '执行中' },
  SUCCESS: { color: 'green', text: '已完成' },
  FAILED: { color: 'red', text: '失败' },
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
    fetchTasks()
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
    return { total, running, success, failed }
  }, [tasks])

  const handleDelete = async (id: number) => {
    try {
      await deleteTask(id)
      message.success('任务已删除')
      fetchTasks()
    } catch {
      message.error('删除失败，运行中的任务不可删除')
    }
  }

  const columns = [
    {
      title: '任务',
      dataIndex: 'taskName',
      render: (text: string, record: TaskInfo) => (
        <Space direction="vertical" size={2}>
          <a onClick={() => navigate(`/task/${record.id}`)}>{text}</a>
          <span className="muted-text">{record.subjectProduct}</span>
        </Space>
      ),
    },
    {
      title: '竞品',
      dataIndex: 'competitorNames',
      width: 220,
      render: (value: string | null) => {
        const names = parseJsonArray(value)
        return names.length ? (
          <Space size={[4, 4]} wrap>
            {names.slice(0, 3).map((name) => (
              <Tag key={String(name)}>{String(name)}</Tag>
            ))}
            {names.length > 3 && <Tag>+{names.length - 3}</Tag>}
          </Space>
        ) : '-'
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (status: TaskStatus) => {
        const item = statusMap[status] || { color: 'default', text: status }
        return <Tag color={item.color}>{item.text}</Tag>
      },
    },
    {
      title: '进度',
      key: 'progress',
      width: 160,
      render: (_: unknown, record: TaskInfo) => {
        const percent = record.totalNodes ? Math.round((record.completedNodes / record.totalNodes) * 100) : 0
        return <Progress percent={percent} size="small" status={record.status === 'FAILED' ? 'exception' : undefined} />
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
      width: 170,
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
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={record.status === 'RUNNING'}
              onClick={() => handleDelete(record.id)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="page-header">
        <h2>竞品分析任务</h2>
        <p>保留最近 10 条任务，聚焦创建、执行、查看报告这条第一版主流程。</p>
      </div>

      <div className="metric-grid">
        <Card><span className="metric-value">{summary.total}</span><span className="metric-label">最近任务</span></Card>
        <Card><span className="metric-value">{summary.running}</span><span className="metric-label">执行中</span></Card>
        <Card><span className="metric-value">{summary.success}</span><span className="metric-label">已完成</span></Card>
        <Card><span className="metric-value">{summary.failed}</span><span className="metric-label">失败</span></Card>
      </div>

      <Card className="work-card">
        <div className="table-toolbar">
          <Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/task/new')}>
              新建任务
            </Button>
            <Button icon={<ReloadOutlined />} onClick={fetchTasks}>
              刷新
            </Button>
          </Space>
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
    </div>
  )
}
