import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Col, Divider, Form, Input, Row, Select, Space, Tag, Typography } from 'antd'
import { ArrowLeftOutlined, PlusOutlined, RocketOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { createTask, executeTask, listSchemas, previewWorkflow } from '../api/client'
import KnowledgeSourceIntakeCard from '../components/task-create/KnowledgeSourceIntakeCard'
import SourceStrategySummary from '../components/task-create/SourceStrategySummary'
import TaskPlanPreviewBoard from '../components/task-create/TaskPlanPreviewBoard'
import type { AnalysisSchema, CreateTaskRequest, TaskPlanPreviewContract } from '../types'
import { appMessage } from '../utils/appMessage'
import { buildConversationEntryUrl } from '../utils/conversationPresentation'
import { buildGovernanceActionFailureMessage } from '../utils/governancePresentation'
import {
  buildSourceStrategyOverviewFromPreview,
  buildTaskPlanPreviewFromContract,
} from '../utils/taskNodeInsights'

const { Text } = Typography

const PRESET_DIMENSIONS = ['产品功能', '目标用户', '价格策略', '技术能力', '市场定位', '差异化优势', '商业模式']
const PRESET_SOURCES = ['官网', '产品文档', '定价页面', '博客资讯', '公开测评']

const INITIAL_FORM_VALUES = {
  competitors: [{ name: '', url: '' }],
  reportLanguage: '中文',
  reportTemplate: '标准版',
}

interface CompetitorInput {
  name?: string
  url?: string
}

/**
 * 统一把表单值归一化为后端任务创建请求，避免页面层在预览、提交等多个入口重复拼装参数。
 * 这里会主动裁剪空白、过滤空竞品，并把官网地址单独整理为后端约定的数组字段。
 */
export function buildCreateTaskRequest(values: Record<string, unknown>): CreateTaskRequest {
  const competitors = ((values.competitors as CompetitorInput[]) || [])
    .map((item) => ({
      name: item.name?.trim(),
      url: item.url?.trim(),
    }))
    .filter((item) => item.name)

  return {
    taskName: String(values.taskName || '').trim(),
    subjectProduct: String(values.subjectProduct || '').trim(),
    competitorNames: competitors.map((item) => item.name as string),
    competitorUrls: competitors.map((item) => item.url || '').filter(Boolean),
    analysisDimensions: values.analysisDimensions as string[],
    sourceScope: values.sourceScope as string[],
    reportLanguage: (values.reportLanguage as string) || '中文',
    reportTemplate: (values.reportTemplate as string) || '标准版',
    schemaId: values.schemaId as number | undefined,
  }
}

function getErrorMessage(error: unknown, fallback: string) {
  if (
    error &&
    typeof error === 'object' &&
    'response' in error &&
    error.response &&
    typeof error.response === 'object' &&
    'data' in error.response
  ) {
    const data = error.response.data as { message?: unknown }
    if (typeof data?.message === 'string' && data.message.trim()) {
      return data.message
    }
  }
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  return fallback
}

function isValidHttpUrl(value?: string) {
  if (!value) return true
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

export default function TaskCreatePage() {
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [schemas, setSchemas] = useState<AnalysisSchema[]>([])
  const [previewContract, setPreviewContract] = useState<TaskPlanPreviewContract | null>(null)
  const [previewVersion, setPreviewVersion] = useState(0)
  const [draftRequest, setDraftRequest] = useState<CreateTaskRequest>(() => buildCreateTaskRequest(INITIAL_FORM_VALUES))

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

  const sourceStrategyOverview = useMemo(
    () => buildSourceStrategyOverviewFromPreview(previewContract),
    [previewContract],
  )

  const taskPlanPreview = useMemo(() => buildTaskPlanPreviewFromContract(previewContract), [previewContract])

  /**
   * 创建页还没有正式 taskId，这里只携带草稿阶段稳定存在的最小上下文，
   * 让统一对话入口能围绕当前任务草稿继续追问，而不会绑定未保存的临时表单状态。
   */
  const conversationEntryUrl = useMemo(
    () =>
      buildConversationEntryUrl({
        pageType: 'TASK_CREATE',
        taskName: draftRequest.taskName,
      }),
    [draftRequest.taskName],
  )

  const refreshPreview = async () => {
    const values = form.getFieldsValue(true)
    try {
      const request = buildCreateTaskRequest(values)
      if (!request.taskName || !request.subjectProduct || !request.competitorNames?.length) {
        setPreviewContract(null)
        return
      }
      setPreviewLoading(true)
      const res = await previewWorkflow(request)
      setPreviewContract(res.data || null)
    } catch {
      setPreviewContract(null)
    } finally {
      setPreviewLoading(false)
    }
  }

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void refreshPreview()
    }, 450)
    return () => window.clearTimeout(timer)
  }, [previewVersion])

  const handleSubmit = async (values: Record<string, unknown>) => {
    setLoading(true)
    try {
      const data = buildCreateTaskRequest(values)
      const res = await createTask(data)
      const taskId = res.data?.id
      if (!taskId) {
        throw new Error('taskId missing')
      }

      try {
        await executeTask(taskId)
        appMessage.success('任务已创建并启动')
        navigate(`/task/${taskId}`)
      } catch (error) {
        appMessage.warning(buildGovernanceActionFailureMessage(error, '任务已创建，但自动启动失败', '请前往任务详情页手动启动'))
        navigate(`/task/${taskId}`)
      }
    } catch (error) {
      appMessage.error(buildGovernanceActionFailureMessage(error, '创建任务失败', '请检查表单或稍后重试'))
    } finally {
      setLoading(false)
    }
  }

  const handleReset = () => {
    form.resetFields()
    setPreviewContract(null)
    setDraftRequest(buildCreateTaskRequest(INITIAL_FORM_VALUES))
    setPreviewVersion((current) => current + 1)
  }

  return (
    <div>
      <div className="page-toolbar">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
            返回
          </Button>
          <Button onClick={() => navigate(conversationEntryUrl)}>进入解释入口</Button>
        </Space>
      </div>

      <div className="page-header">
        <h2>创建竞品分析任务</h2>
        <p>先说明任务目标，再确认资料策略和执行计划；低层检索细节会留在后续按需查看的路径中。</p>
      </div>

      <Row gutter={[16, 16]} align="top">
        <Col xs={24} lg={14}>
          <Card title="任务目标" className="work-card">
            <Form
              form={form}
              layout="vertical"
              onFinish={handleSubmit}
              onValuesChange={() => {
                setDraftRequest(buildCreateTaskRequest(form.getFieldsValue(true)))
                setPreviewVersion((current) => current + 1)
              }}
              initialValues={INITIAL_FORM_VALUES}
            >
              <Space direction="vertical" size={4} style={{ width: '100%', marginBottom: 16 }}>
                <Text strong>任务目标</Text>
                <Text type="secondary">先补充分析主题、本方产品和竞品对象，系统会据此生成来源策略与执行计划预览。</Text>
              </Space>

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
                      const normalizedNames = (competitors || [])
                        .map((item) => item?.name?.trim())
                        .filter(Boolean) as string[]
                      const uniqueNames = new Set(normalizedNames.map((item) => item.toLowerCase()))
                      if (normalizedNames.length === 0) {
                        return Promise.reject(new Error('请至少添加一个竞品'))
                      }
                      if (uniqueNames.size !== normalizedNames.length) {
                        return Promise.reject(new Error('竞品名称不能重复'))
                      }
                    },
                  },
                ]}
              >
                {(fields, { add, remove }) => (
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    {fields.map((field, index) => {
                      const { key, ...restField } = field
                      return (
                        <Row gutter={8} key={key} align="middle">
                          <Col xs={24} md={8}>
                            <Form.Item
                              {...restField}
                              name={[field.name, 'name']}
                              label={index === 0 ? '竞品名称' : undefined}
                              rules={[
                                { required: true, message: '请输入竞品名称' },
                                {
                                  validator: async (_, value?: string) => {
                                    if (!value || value.trim()) {
                                      return
                                    }
                                    return Promise.reject(new Error('竞品名称不能为空白'))
                                  },
                                },
                              ]}
                            >
                              <Input placeholder="例如：Dify" />
                            </Form.Item>
                          </Col>
                          <Col xs={24} md={13}>
                            <Form.Item
                              {...restField}
                              name={[field.name, 'url']}
                              label={index === 0 ? '官网地址' : undefined}
                              rules={[
                                {
                                  validator: async (_, value?: string) => {
                                    if (!value || !value.trim()) {
                                      return
                                    }
                                    if (!isValidHttpUrl(value.trim())) {
                                      return Promise.reject(new Error('请输入合法的 http/https 地址'))
                                    }
                                  },
                                },
                              ]}
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
                      )
                    })}
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>
                      添加竞品
                    </Button>
                  </Space>
                )}
              </Form.List>

              <Divider orientation="left" plain>
                可调整输入
              </Divider>

              <Space direction="vertical" size={4} style={{ width: '100%', marginBottom: 12 }}>
                <Text type="secondary">这些设置会直接影响研究范围、来源覆盖和最终报告表达，可在启动前继续调整。</Text>
              </Space>

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

              <Space wrap>
                <Button type="primary" htmlType="submit" loading={loading} icon={<RocketOutlined />} size="large">
                  创建并执行
                </Button>
                <Button onClick={handleReset}>重置</Button>
              </Space>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card title="来源策略" className="work-card">
            <SourceStrategySummary
              overview={sourceStrategyOverview}
              hasReadyPreview={previewContract !== null}
            />
            {previewLoading && <Text type="secondary">正在刷新预览...</Text>}
          </Card>

          <Card title="执行计划预览" className="work-card" style={{ marginTop: 16 }}>
            <TaskPlanPreviewBoard
              plan={taskPlanPreview}
              hasReadyPreview={previewContract !== null}
            />
          </Card>

          <Card className="work-card" style={{ marginTop: 16 }}>
            <Space align="start">
              <ThunderboltOutlined className="hint-icon" />
              <Text>
                首屏会先帮助你确认任务目标、来源策略和执行节奏；原始检索词、候选链接与其他技术细节会放到后续按需查看的路径中。
              </Text>
            </Space>
          </Card>

          <div style={{ marginTop: 16 }}>
            <KnowledgeSourceIntakeCard />
          </div>

          {previewContract === null && (
            <Alert
              style={{ marginTop: 16 }}
              type="info"
              showIcon
              message="补全任务目标后将自动生成计划"
              description="填写分析主题、本方产品和至少一个竞品后，系统会自动刷新来源策略与执行计划预览。"
            />
          )}
        </Col>
      </Row>
    </div>
  )
}
