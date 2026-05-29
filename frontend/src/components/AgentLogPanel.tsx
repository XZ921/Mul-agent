import { useCallback, useEffect, useState } from 'react'
import { Button, Descriptions, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getAgentLogDetail, getAgentLogs } from '../api/client'
import type { AgentLog, AgentType, NodeStatus } from '../types'

const { Paragraph } = Typography

const agentColorMap: Record<AgentType, string> = {
  COLLECTOR: 'blue',
  EXTRACTOR: 'purple',
  ANALYZER: 'cyan',
  WRITER: 'orange',
  REVIEWER: 'magenta',
}

const statusColor: Record<NodeStatus, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  SKIPPED: 'default',
}

interface Props {
  taskId: number
  autoRefresh?: boolean
}

export default function AgentLogPanel({ taskId, autoRefresh = false }: Props) {
  const [logs, setLogs] = useState<AgentLog[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedLog, setSelectedLog] = useState<AgentLog | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState<AgentLog | null>(null)
  const [agentFilter, setAgentFilter] = useState<string | undefined>()

  const fetchLogs = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getAgentLogs(taskId)
      setLogs(res.data || [])
    } catch {
      message.error('获取 Agent 日志失败')
    } finally {
      setLoading(false)
    }
  }, [taskId])

  useEffect(() => {
    fetchLogs()
  }, [fetchLogs])

  useEffect(() => {
    if (!autoRefresh) return
    const timer = window.setInterval(fetchLogs, 3000)
    return () => window.clearInterval(timer)
  }, [autoRefresh, fetchLogs])

  const filteredLogs = agentFilter ? logs.filter((log) => log.agentType === agentFilter) : logs

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

  const columns = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (time: string) => dayjs(time).format('HH:mm:ss'),
    },
    {
      title: 'Agent',
      dataIndex: 'agentType',
      width: 120,
      render: (type: AgentType) => <Tag color={agentColorMap[type]}>{type}</Tag>,
    },
    {
      title: '名称',
      dataIndex: 'agentName',
      width: 130,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (status: NodeStatus) => <Tag color={statusColor[status]}>{status}</Tag>,
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
      render: (duration: number | null) => (duration ? `${(duration / 1000).toFixed(1)}s` : '-'),
    },
    {
      title: '追踪 ID',
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
    <div>
      <div className="table-toolbar">
        <Space>
          <Select
            allowClear
            placeholder="按 Agent 筛选"
            style={{ width: 170 }}
            value={agentFilter}
            onChange={setAgentFilter}
            options={['COLLECTOR', 'EXTRACTOR', 'ANALYZER', 'WRITER', 'REVIEWER'].map((type) => ({
              label: <Tag color={agentColorMap[type as AgentType]}>{type}</Tag>,
              value: type,
            }))}
          />
          <Button icon={<ReloadOutlined />} onClick={fetchLogs}>
            刷新
          </Button>
        </Space>
      </div>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={filteredLogs}
        loading={loading}
        size="small"
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无 Agent 执行日志' }}
      />

      <Modal
        title={`Agent 执行详情 ${selectedLog?.agentType || ''}`}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        width={920}
        footer={null}
      >
        {detail && (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="Agent 类型">
              <Tag color={agentColorMap[detail.agentType]}>{detail.agentType}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="Agent 名称">{detail.agentName}</Descriptions.Item>
            <Descriptions.Item label="模型">{detail.modelName || '-'}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={statusColor[detail.status]}>{detail.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="耗时">
              {detail.durationMs ? `${(detail.durationMs / 1000).toFixed(1)}s` : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="Token 用量">{detail.tokenUsage || '-'}</Descriptions.Item>
            <Descriptions.Item label="追踪 ID">{detail.traceId || '-'}</Descriptions.Item>
            {detail.errorMessage && (
              <Descriptions.Item label="错误信息">
                <span className="danger-text">{detail.errorMessage}</span>
              </Descriptions.Item>
            )}
            <Descriptions.Item label="输入">
              <Paragraph code className="code-block">
                {detail.inputData || '(未记录)'}
              </Paragraph>
            </Descriptions.Item>
            <Descriptions.Item label="Prompt">
              <Paragraph code className="code-block">
                {detail.promptUsed || '(未记录)'}
              </Paragraph>
            </Descriptions.Item>
            <Descriptions.Item label="输出">
              <Paragraph code className="code-block">
                {detail.outputData || '(未记录)'}
              </Paragraph>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  )
}
