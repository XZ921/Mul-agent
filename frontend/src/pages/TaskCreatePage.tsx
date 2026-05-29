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

const { Text } = Typography

const PRESET_DIMENSIONS = ['产品功能', '目标用户', '价格策略', '技术能力', '市场定位', '差异化优势', '商业模式']
const PRESET_SOURCES = ['官网', '产品文档', '定价页', '博客/新闻', '公开测评文章']

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
            {schema.isPreset && <Tag color="blue">Preset</Tag>}
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
        throw new Error('Task created but no taskId returned')
      }

      await executeTask(taskId)
      message.success('Task created and started')
      navigate(`/task/${taskId}`)
    } catch {
      message.error('Create or execute task failed')
    } finally {
      setLoading(false)
    }
  }

  const previewStepItems = workflowPreview.map((node) => ({
    title: node.displayName,
    description: (
      <Space direction="vertical" size={2}>
        <Space wrap>
          <Tag color="blue">{node.agentType}</Tag>
          <Text type="secondary">{node.nodeName}</Text>
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
          Back
        </Button>
      </div>

      <div className="page-header">
        <h2>Create competitor analysis task</h2>
        <p>Fill in the goal, competitors, and source scope. The system will preview a dynamic DAG before execution.</p>
      </div>

      <Row gutter={[16, 16]} align="top">
        <Col xs={24} lg={15}>
          <Card title="Task Config" className="work-card">
            <Form
              form={form}
              layout="vertical"
              onFinish={handleSubmit}
              onValuesChange={refreshPreview}
              initialValues={{
                competitors: [{ name: 'Notion AI', url: 'https://www.notion.so/product/ai' }],
                analysisDimensions: ['产品功能', '目标用户', '价格策略'],
                sourceScope: ['官网', '产品文档', '定价页'],
                reportLanguage: '中文',
                reportTemplate: '标准版',
              }}
            >
              <Form.Item
                name="taskName"
                label="Analysis Topic"
                rules={[{ required: true, message: 'Please enter task name' }]}
              >
                <Input placeholder="e.g. AI knowledge base competitive analysis" maxLength={200} showCount />
              </Form.Item>

              <Form.Item
                name="subjectProduct"
                label="Our Product"
                rules={[{ required: true, message: 'Please enter subject product' }]}
              >
                <Input placeholder="e.g. enterprise RAG platform" maxLength={200} showCount />
              </Form.Item>

              <Divider orientation="left" plain>
                Competitors
              </Divider>

              <Form.List
                name="competitors"
                rules={[
                  {
                    validator: async (_, competitors: CompetitorInput[]) => {
                      const valid = competitors?.some((item) => item?.name?.trim())
                      if (!valid) {
                        return Promise.reject(new Error('Please add at least one competitor'))
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
                            label={index === 0 ? 'Competitor Name' : undefined}
                            rules={[{ required: true, message: 'Please enter competitor name' }]}
                          >
                            <Input placeholder="Dify" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={13}>
                          <Form.Item
                            {...field}
                            name={[field.name, 'url']}
                            label={index === 0 ? 'Official URL' : undefined}
                          >
                            <Input placeholder="https://..." />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={3}>
                          <Button danger disabled={fields.length === 1} onClick={() => remove(field.name)}>
                            Remove
                          </Button>
                        </Col>
                      </Row>
                    ))}
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>
                      Add competitor
                    </Button>
                  </Space>
                )}
              </Form.List>

              <Divider orientation="left" plain>
                Analysis Settings
              </Divider>

              <Form.Item name="schemaId" label="Schema">
                <Select allowClear placeholder="Optional schema" options={schemaOptions} />
              </Form.Item>

              <Form.Item
                name="analysisDimensions"
                label="Dimensions"
                rules={[{ required: true, message: 'Please select analysis dimensions' }]}
              >
                <Select
                  mode="multiple"
                  placeholder="Select dimensions"
                  options={PRESET_DIMENSIONS.map((item) => ({ label: item, value: item }))}
                />
              </Form.Item>

              <Form.Item name="sourceScope" label="Source Scope">
                <Select
                  mode="multiple"
                  placeholder="Select source scope"
                  options={PRESET_SOURCES.map((item) => ({ label: item, value: item }))}
                />
              </Form.Item>

              <Row gutter={12}>
                <Col xs={24} md={12}>
                  <Form.Item name="reportLanguage" label="Report Language">
                    <Select options={[{ label: '中文', value: '中文' }, { label: 'English', value: 'English' }]} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item name="reportTemplate" label="Report Template">
                    <Select options={[{ label: '标准版', value: '标准版' }, { label: '精简版', value: '精简版' }]} />
                  </Form.Item>
                </Col>
              </Row>

              <Space>
                <Button type="primary" htmlType="submit" loading={loading} icon={<RocketOutlined />} size="large">
                  Create and Run
                </Button>
                <Button onClick={() => form.resetFields()}>Reset</Button>
              </Space>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={9}>
          <Card title="Workflow Preview" className="work-card">
            {workflowPreview.length > 0 ? (
              <Steps direction="vertical" size="small" current={workflowPreview.length - 1} items={previewStepItems} />
            ) : (
              <Alert
                type="info"
                showIcon
                message="Preview will appear here"
                description="Fill in task name, product, and at least one competitor to generate a dynamic DAG preview."
              />
            )}
            {previewLoading && <Text type="secondary">Refreshing preview...</Text>}
          </Card>

          <Card className="work-card" style={{ marginTop: 16 }}>
            <Space>
              <FileDoneOutlined className="hint-icon" />
              <Text>Preview shows a competitor-specific collection node for each competitor.</Text>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
