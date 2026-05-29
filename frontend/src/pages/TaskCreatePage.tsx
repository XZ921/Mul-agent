import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Form,
  Input,
  Row,
  Select,
  Space,
  Steps,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ArrowLeftOutlined,
  FileDoneOutlined,
  PlusOutlined,
  RocketOutlined,
} from '@ant-design/icons'
import { createTask, executeTask, listSchemas, previewWorkflow } from '../api/client'
import type { AnalysisSchema, CreateTaskRequest, TaskNodeInfo } from '../types'
import { getAgentTypeText, getNodeDisplayName, getNodeNameLabel } from '../utils/display'

const { Text } = Typography

const PRESET_DIMENSIONS = ['产品功能', '目标用户', '价格策略', '技术能力', '市场定位', '差异化优势', '商业模式']
const PRESET_SOURCES = ['官网', '产品文档', '定价页面', '博客资讯', '公开测评']

interface CompetitorInput {
  name?: string
  url?: string
}

export default function TaskCreatePage() {
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [schemas, setSchemas] = useState<AnalysisSchema[]>([])
  const [workflowPreview, setWorkflowPreview] = useState<TaskNodeInfo[]>([])

  useEffect(() => {
    listSchemas()
      .then((res) => setSchemas(res.data || []))
      .catch(() => setSchemas([]))
  }, [])

  const schemaOptions = useMemo(
    () =>
      schemas.map((schema) => ({
        label: (
          <Space>
            <span>{schema.name}</span>
            {schema.isPreset && <Tag color="blue">预置</Tag>}
          </Space>
        ),
        value: schema.id,
      })),
    [schemas],
  )

  const buildRequest = (values: Record<string, unknown>): CreateTaskRequest => {
    const competitors = ((values.competitors as CompetitorInput[]) || [])
      .map((item) => ({
        name: item.name?.trim(),
        url: item.url?.trim(),
      }))
      .filter((item) => item.name)

    return {
      taskName: values.taskName as string,
      subjectProduct: values.subjectProduct as string,
      competitorNames: competitors.map((item) => item.name as string),
      competitorUrls: competitors.map((item) => item.url || '').filter(Boolean),
      analysisDimensions: values.analysisDimensions as string[],
      sourceScope: values.sourceScope as string[],
      reportLanguage: (values.reportLanguage as string) || '中文',
      reportTemplate: (values.reportTemplate as string) || '标准版',
      schemaId: values.schemaId as number | undefined,
    }
  }

  const refreshPreview = async () => {
    const values = form.getFieldsValue(true)
    try {
      const request = buildRequest(values)
      if (!request.taskName || !request.subjectProduct || !request.competitorNames?.length) {
        setWorkflowPreview([])
        return
      }
      setPreviewLoading(true)
      const res = await previewWorkflow(request)
      setWorkflowPreview(res.data || [])
    } catch {
      setWorkflowPreview([])
    } finally {
      setPreviewLoading(false)
    }
  }

  const handleSubmit = async (values: Record<string, unknown>) => {
    setLoading(true)
    try {
      const data = buildRequest(values)
      const res = await createTask(data)
      const taskId = res.data?.id
      if (!taskId) {
        throw new Error('taskId missing')
      }

      await executeTask(taskId)
      message.success('任务已创建并启动')
      navigate(`/task/${taskId}`)
    } catch {
      message.error('创建或执行任务失败')
    } finally {
      setLoading(false)
    }
  }

  const previewStepItems = workflowPreview.map((node) => ({
    title: getNodeDisplayName(node),
    description: (
      <Space direction="vertical" size={2}>
        <Space wrap>
          <Tag color="blue">{getAgentTypeText(node.agentType)}</Tag>
          <Text type="secondary">{getNodeNameLabel(node.nodeName)}</Text>
        </Space>
        {node.configSummary && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {node.configSummary}
          </Text>
        )}
      </Space>
    ),
    status: 'process' as const,
  }))

  return (
    <div>
      <div className="page-toolbar">
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
          返回
        </Button>
      </div>

      <div className="page-header">
        <h2>创建竞品分析任务</h2>
        <p>填写分析目标、竞品信息和采集范围，系统会在执行前动态预览工作流。</p>
      </div>

      <Row gutter={[16, 16]} align="top">
        <Col xs={24} lg={15}>
          <Card title="任务配置" className="work-card">
            <Form
              form={form}
              layout="vertical"
              onFinish={handleSubmit}
              onValuesChange={refreshPreview}
              initialValues={{
                competitors: [{ name: 'Notion AI', url: 'https://www.notion.so/product/ai' }],
                analysisDimensions: ['产品功能', '目标用户', '价格策略'],
                sourceScope: ['官网', '产品文档', '定价页面'],
                reportLanguage: '中文',
                reportTemplate: '标准版',
              }}
            >
              <Form.Item
                name="taskName"
                label="分析主题"
                rules={[{ required: true, message: '请输入任务名称' }]}
              >
                <Input placeholder="例如：AI 知识库竞品分析" maxLength={200} showCount />
              </Form.Item>

              <Form.Item
                name="subjectProduct"
                label="本方产品"
                rules={[{ required: true, message: '请输入本方产品名称' }]}
              >
                <Input placeholder="例如：企业级 RAG 平台" maxLength={200} showCount />
              </Form.Item>

              <Divider orientation="left" plain>
                竞品列表
              </Divider>

              <Form.List
                name="competitors"
                rules={[
                  {
                    validator: async (_, competitors: CompetitorInput[]) => {
                      const valid = competitors?.some((item) => item?.name?.trim())
                      if (!valid) {
                        return Promise.reject(new Error('请至少添加一个竞品'))
                      }
                    },
                  },
                ]}
              >
                {(fields, { add, remove }) => (
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    {fields.map((field, index) => (
                      <Row gutter={8} key={field.key} align="middle">
                        <Col xs={24} md={8}>
                          <Form.Item
                            {...field}
                            name={[field.name, 'name']}
                            label={index === 0 ? '竞品名称' : undefined}
                            rules={[{ required: true, message: '请输入竞品名称' }]}
                          >
                            <Input placeholder="例如：Dify" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={13}>
                          <Form.Item
                            {...field}
                            name={[field.name, 'url']}
                            label={index === 0 ? '官网地址' : undefined}
                          >
                            <Input placeholder="https://..." />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={3}>
                          <Button danger disabled={fields.length === 1} onClick={() => remove(field.name)}>
                            删除
                          </Button>
                        </Col>
                      </Row>
                    ))}
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>
                      添加竞品
                    </Button>
                  </Space>
                )}
              </Form.List>

              <Divider orientation="left" plain>
                分析设置
              </Divider>

              <Form.Item name="schemaId" label="分析结构模板">
                <Select allowClear placeholder="可选，不填写则使用默认规则" options={schemaOptions} />
              </Form.Item>

              <Form.Item
                name="analysisDimensions"
                label="分析维度"
                rules={[{ required: true, message: '请选择分析维度' }]}
              >
                <Select
                  mode="multiple"
                  placeholder="请选择分析维度"
                  options={PRESET_DIMENSIONS.map((item) => ({ label: item, value: item }))}
                />
              </Form.Item>

              <Form.Item name="sourceScope" label="采集范围">
                <Select
                  mode="multiple"
                  placeholder="请选择采集范围"
                  options={PRESET_SOURCES.map((item) => ({ label: item, value: item }))}
                />
              </Form.Item>

              <Row gutter={12}>
                <Col xs={24} md={12}>
                  <Form.Item name="reportLanguage" label="报告语言">
                    <Select
                      options={[
                        { label: '中文', value: '中文' },
                        { label: '英文', value: '英文' },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item name="reportTemplate" label="报告模板">
                    <Select
                      options={[
                        { label: '标准版', value: '标准版' },
                        { label: '精简版', value: '精简版' },
                      ]}
                    />
                  </Form.Item>
                </Col>
              </Row>

              <Space>
                <Button type="primary" htmlType="submit" loading={loading} icon={<RocketOutlined />} size="large">
                  创建并执行
                </Button>
                <Button onClick={() => form.resetFields()}>重置</Button>
              </Space>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={9}>
          <Card title="工作流预览" className="work-card">
            {workflowPreview.length > 0 ? (
              <Steps direction="vertical" size="small" current={workflowPreview.length - 1} items={previewStepItems} />
            ) : (
              <Alert
                type="info"
                showIcon
                message="这里将显示预览结果"
                description="填写任务名称、本方产品和至少一个竞品后，将自动生成动态工作流预览。"
              />
            )}
            {previewLoading && <Text type="secondary">正在刷新预览...</Text>}
          </Card>

          <Card className="work-card" style={{ marginTop: 16 }}>
            <Space>
              <FileDoneOutlined className="hint-icon" />
              <Text>预览中会为每个竞品生成独立的信息采集节点。</Text>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
