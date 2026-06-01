import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Divider,
  Form,
  Input,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ApartmentOutlined,
  ArrowLeftOutlined,
  PlusOutlined,
  RocketOutlined,
  SearchOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { createTask, executeTask, listSchemas, previewWorkflow } from '../api/client'
import type { AnalysisSchema, CreateTaskRequest, TaskNodeConfigSummary, TaskNodeInfo } from '../types'
import { getAgentTypeText, getNodeDisplayName, getNodeNameLabel, getSourceTypeText } from '../utils/display'
import { getCollectorNodeInsight, getDependencyNames } from '../utils/taskNodeInsights'

const { Text } = Typography

const PRESET_DIMENSIONS = ['产品功能', '目标用户', '价格策略', '技术能力', '市场定位', '差异化优势', '商业模式']
const PRESET_SOURCES = ['官网', '产品文档', '定价页面', '博客资讯', '公开测评']

interface CompetitorInput {
  name?: string
  url?: string
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

function summarizeList(items: string[], limit = 3) {
  if (!items.length) return '未提供'
  const visible = items.slice(0, limit)
  return items.length > limit ? `${visible.join('、')} 等 ${items.length} 项` : visible.join('、')
}

function compactUrl(url: string) {
  return url.replace(/^https?:\/\//, '').replace(/\/+$/, '')
}

function candidateLabel(candidate: { title?: string; url?: string; domain?: string }) {
  return candidate.title || candidate.domain || (candidate.url ? compactUrl(candidate.url) : '未命名候选')
}

function searchModeDescription(mode?: string) {
  if (mode === 'HYBRID') return '混合模式：浏览器优先，HTTP 兜底'
  if (mode === 'BROWSER_ONLY') return '仅浏览器模式'
  if (mode === 'HTTP_ONLY') return '仅 HTTP 模式'
  if (mode === 'HEURISTIC_ONLY') return '仅启发式候选'
  return '默认混合模式'
}

function collectorPreviewSummary(summaryData: TaskNodeConfigSummary | null | undefined, fallbackCandidateCount: number, fallbackQueryCount: number) {
  const candidateCount = summaryData?.candidateCount ?? fallbackCandidateCount
  const queryCount = summaryData?.queryCount ?? fallbackQueryCount
  return `预备从 ${candidateCount} 个候选来源采集，已生成 ${queryCount} 组 Query`
}

function collectorPreviewMeta(summaryData: TaskNodeConfigSummary | null | undefined, fallback: {
  searchMode?: string
  verifyResultPage: boolean
  minVerifiedCandidates: number | null
}) {
  return {
    searchModeText: summaryData?.searchModeLabel
      ? `搜索模式：${summaryData.searchModeLabel}`
      : searchModeDescription(fallback.searchMode),
    verificationText: summaryData?.verificationEnabled != null
      ? summaryData.verificationEnabled
        ? `结果页验证开启，至少验证 ${summaryData.minVerifiedCandidates || 1} 条`
        : '结果页验证关闭'
      : fallback.verifyResultPage
        ? `结果页验证开启，至少验证 ${fallback.minVerifiedCandidates || 1} 条`
        : '结果页验证关闭',
  }
}

function pipelinePreviewSummary(node: TaskNodeInfo) {
  const summaryData = node.configSummaryData
  if (!summaryData) {
    return node.configSummary || getNodeNameLabel(node.nodeName)
  }
  if (node.agentType === 'EXTRACTOR') {
    return summaryData.dimensions?.length
      ? `将按 ${summaryData.dimensions.slice(0, 3).join('、')}${summaryData.dimensions.length > 3 ? ' 等维度' : ''} 进行结构化抽取`
      : summaryData.summaryText || getNodeNameLabel(node.nodeName)
  }
  if (node.agentType === 'ANALYZER') {
    return `将汇总 ${summaryData.competitorCount ?? 0} 个竞品，分析 ${summaryData.dimensionCount ?? 0} 个维度`
  }
  if (node.agentType === 'WRITER') {
    return summaryData.mode === 'revision'
      ? '将基于评审反馈生成修订版报告'
      : `将输出 ${summaryData.reportLanguage || '中文'} / ${summaryData.reportTemplate || '标准版'} 报告`
  }
  if (node.agentType === 'REVIEWER') {
    return summaryData.sourceNode
      ? `将按 ${summaryData.qualityPolicy || '标准质量评审'} 复核 ${summaryData.sourceNode}`
      : `将按 ${summaryData.qualityPolicy || '标准质量评审'} 进行质量评审`
  }
  return summaryData.summaryText || node.configSummary || getNodeNameLabel(node.nodeName)
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

  useEffect(() => {
    void refreshPreview()
  }, [])

  const handleSubmit = async (values: Record<string, unknown>) => {
    setLoading(true)
    try {
      const data = buildRequest(values)
      const res = await createTask(data)
      const taskId = res.data?.id
      if (!taskId) {
        throw new Error('taskId missing')
      }

      try {
        await executeTask(taskId)
        message.success('任务已创建并启动')
        navigate(`/task/${taskId}`)
      } catch (error) {
        message.warning(`任务已创建，但自动启动失败：${getErrorMessage(error, '请前往任务详情页手动启动')}`)
        navigate(`/task/${taskId}`)
      }
    } catch (error) {
      message.error(`创建任务失败：${getErrorMessage(error, '请检查表单或稍后重试')}`)
    } finally {
      setLoading(false)
    }
  }

  const previewCollectorNodes = useMemo(
    () => workflowPreview.filter((node) => node.agentType === 'COLLECTOR'),
    [workflowPreview],
  )

  const previewPipelineNodes = useMemo(
    () => workflowPreview.filter((node) => node.agentType !== 'COLLECTOR'),
    [workflowPreview],
  )

  const previewCollectorCards = useMemo(
    () =>
      previewCollectorNodes.map((node) => {
        const insight = getCollectorNodeInsight(node)
        return {
          node,
          insight,
          summaryData: node.configSummaryData,
          dependencies: getDependencyNames(node.dependsOn),
        }
      }),
    [previewCollectorNodes],
  )

  const previewGroups = useMemo(() => {
    const groups = new Map<string, typeof previewCollectorCards>()
    previewCollectorCards.forEach((item) => {
      const key = item.insight.competitorName || '未命名竞品'
      const existing = groups.get(key) || []
      existing.push(item)
      groups.set(key, existing)
    })
    return Array.from(groups.entries())
  }, [previewCollectorCards])

  const previewSummary = useMemo(() => {
    const browserEnabledCount = previewCollectorCards.filter((item) => item.insight.browserSearchEnabled).length
    const queryCount = previewCollectorCards.reduce((sum, item) => sum + item.insight.searchQueries.length, 0)
    return {
      competitorCount: previewGroups.length,
      collectorCount: previewCollectorCards.length,
      browserEnabledCount,
      pipelineCount: previewPipelineNodes.length,
      queryCount,
    }
  }, [previewCollectorCards, previewGroups.length, previewPipelineNodes.length])

  const handleReset = () => {
    form.resetFields()
    window.setTimeout(() => {
      void refreshPreview()
    }, 0)
  }

  return (
    <div>
      <div className="page-toolbar">
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
          返回
        </Button>
      </div>

      <div className="page-header">
        <h2>创建竞品分析任务</h2>
        <p>先定义分析目标，再通过启动前 DAG 预览确认并行采集范围、补源策略和后续主链路。</p>
      </div>

      <Row gutter={[16, 16]} align="top">
        <Col xs={24} lg={14}>
          <Card title="任务配置" className="work-card">
            <Form
              form={form}
              layout="vertical"
              onFinish={handleSubmit}
              onValuesChange={() => {
                void refreshPreview()
              }}
              initialValues={{
                competitors: [{ name: '', url: '' }],
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
          <Card title="启动前工作流预览" className="work-card">
            {workflowPreview.length > 0 ? (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <div className="create-preview-hero">
                  <div className="create-preview-stat">
                    <span className="create-preview-value">{previewSummary.competitorCount}</span>
                    <span className="create-preview-label">竞品泳道</span>
                  </div>
                  <div className="create-preview-stat">
                    <span className="create-preview-value">{previewSummary.collectorCount}</span>
                    <span className="create-preview-label">采集节点</span>
                  </div>
                  <div className="create-preview-stat">
                    <span className="create-preview-value">{previewSummary.queryCount}</span>
                    <span className="create-preview-label">预设 Query</span>
                  </div>
                  <div className="create-preview-stat">
                    <span className="create-preview-value">{previewSummary.browserEnabledCount}</span>
                    <span className="create-preview-label">浏览器补源</span>
                  </div>
                </div>

                <Alert
                  type="info"
                  showIcon
                  message="系统会先规划采集，再并行执行各竞品采集分支"
                  description="默认只展示业务摘要和关键补源策略；Query、候选来源与执行脚本等高级细节会收纳在折叠区里。"
                />

                <div>
                  <div className="preview-section-title">
                    <SearchOutlined />
                    <span>采集泳道</span>
                  </div>
                  <div className="preview-lane-board">
                    {previewGroups.map(([competitor, items]) => (
                      <div className="preview-lane" key={competitor}>
                        <div className="preview-lane-header">
                          <Space direction="vertical" size={2}>
                            <Text strong>{competitor}</Text>
                            <Text type="secondary">
                              {items.length > 1 ? `将并行启动 ${items.length} 个采集分支` : '将启动单一采集分支'}
                            </Text>
                          </Space>
                          <Tag>{items.length > 1 ? '并行采集' : '单分支采集'}</Tag>
                        </div>

                        <div className="preview-lane-grid">
                          {items.map(({ node, insight, summaryData, dependencies }) => (
                            <div className="preview-lane-node" key={node.nodeName}>
                              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                                <div>
                                  <Text strong>{summaryData?.sourceTypeLabel || getSourceTypeText(insight.sourceType)}</Text>
                                  <div className="preview-node-summary">
                                    {collectorPreviewSummary(summaryData, insight.candidateCount, insight.searchQueries.length)}
                                  </div>
                                </div>

                                <div className="preview-node-meta">
                                  <span>{collectorPreviewMeta(summaryData, {
                                    searchMode: insight.searchMode,
                                    verifyResultPage: insight.verifyResultPage,
                                    minVerifiedCandidates: insight.minVerifiedCandidates,
                                  }).searchModeText}</span>
                                  <span>
                                    {collectorPreviewMeta(summaryData, {
                                      searchMode: insight.searchMode,
                                      verifyResultPage: insight.verifyResultPage,
                                      minVerifiedCandidates: insight.minVerifiedCandidates,
                                    }).verificationText}
                                  </span>
                                  <span>
                                    {dependencies.length > 0
                                      ? `依赖 ${dependencies.join('、')}`
                                      : '无上游依赖，可直接并行执行'}
                                  </span>
                                </div>

                                <Space wrap>
                                  <Tag>{`范围 ${(summaryData?.sourceScope?.join('、') || insight.sourceScope.join('、')) || '未指定'}`}</Tag>
                                  <Tag>{`规划入口 ${(summaryData?.competitorUrls?.length ?? insight.competitorUrls.length)} 个`}</Tag>
                                  <Tag>{`候选 ${summaryData?.candidateCount ?? insight.candidateCount} 条`}</Tag>
                                  {(summaryData?.browserSearchEnabled ?? insight.browserSearchEnabled) && <Tag color="cyan">浏览器补源开启</Tag>}
                                </Space>

                                <Collapse
                                  size="small"
                                  items={[
                                    insight.searchQueries.length > 0
                                      ? {
                                          key: 'queries',
                                          label: `搜索 Query (${insight.searchQueries.length})`,
                                          children: (
                                            <Space wrap>
                                              {insight.searchQueries.map((query) => (
                                                <Tag key={query}>{query}</Tag>
                                              ))}
                                            </Space>
                                          ),
                                        }
                                      : {
                                          key: 'queries',
                                          label: '搜索 Query',
                                          children: <Text type="secondary">当前未配置显式 Query</Text>,
                                        },
                                    insight.searchExecutionPlan?.steps?.length
                                      ? {
                                          key: 'plan',
                                          label: `搜索执行脚本 (${insight.searchExecutionPlan.steps.length} 步)`,
                                          children: (
                                            <div className="preview-step-list">
                                              {insight.searchExecutionPlan.steps.map((step, index) => (
                                                <div className="preview-step-item" key={step.stepCode}>
                                                  <Text strong>{`${index + 1}. ${step.goal || step.stepCode}`}</Text>
                                                  {step.dependency && (
                                                    <Text type="secondary">{`依赖 ${step.dependency}`}</Text>
                                                  )}
                                                </div>
                                              ))}
                                            </div>
                                          ),
                                        }
                                      : {
                                          key: 'plan',
                                          label: '搜索执行脚本',
                                          children: <Text type="secondary">当前未返回结构化执行脚本</Text>,
                                        },
                                    insight.sourceCandidates.length > 0
                                      ? {
                                          key: 'candidates',
                                          label: `候选来源预览 (${insight.sourceCandidates.length})`,
                                          children: (
                                            <Space wrap>
                                              {insight.sourceCandidates.map((candidate) => (
                                                <Tag key={`${candidate.url}-${candidate.title || ''}`}>
                                                  {candidateLabel(candidate)}
                                                </Tag>
                                              ))}
                                            </Space>
                                          ),
                                        }
                                      : {
                                          key: 'candidates',
                                          label: '候选来源预览',
                                          children: <Text type="secondary">当前没有显式候选来源</Text>,
                                        },
                                  ]}
                                />

                                {((summaryData?.preferredDomains?.length || 0) > 0 || summaryData?.discoveryNotes || insight.discoveryNotes || node.nodeNotes) && (
                                  <div className="preview-node-footnote">
                                    {(summaryData?.preferredDomains?.length ?? insight.preferredDomains.length) > 0 && (
                                      <Text type="secondary">{`优先域名：${summarizeList(summaryData?.preferredDomains || insight.preferredDomains, 3)}`}</Text>
                                    )}
                                    {(summaryData?.discoveryNotes || insight.discoveryNotes) && (
                                      <Text type="secondary">{summaryData?.discoveryNotes || insight.discoveryNotes}</Text>
                                    )}
                                    {node.nodeNotes && <Text type="secondary">{node.nodeNotes}</Text>}
                                  </div>
                                )}
                              </Space>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="dag-connector">{`以上 ${previewSummary.collectorCount} 个采集节点完成后，将汇聚到统一分析主链路`}</div>

                <div>
                  <div className="preview-section-title">
                    <ApartmentOutlined />
                    <span>汇聚主链路</span>
                  </div>
                  <div className="preview-pipeline-board">
                    {previewPipelineNodes.map((node) => (
                      <div className="preview-pipeline-node" key={node.nodeName}>
                        <Text strong>{getNodeDisplayName(node)}</Text>
                        <Text type="secondary">{getAgentTypeText(node.agentType)}</Text>
                        <Text type="secondary">{pipelinePreviewSummary(node)}</Text>
                      </div>
                    ))}
                  </div>
                </div>
              </Space>
            ) : (
              <Alert
                type="info"
                showIcon
                message="这里将显示启动前预览"
                description="填写任务名称、本方产品和至少一个竞品后，系统会自动生成并行采集与后续处理链路的动态预览。"
              />
            )}
            {previewLoading && <Text type="secondary">正在刷新预览...</Text>}
          </Card>

          <Card className="work-card" style={{ marginTop: 16 }}>
            <Space>
              <ThunderboltOutlined className="hint-icon" />
              <Text>预览会直接告诉你哪些采集节点可并行启动、哪些启用了浏览器补源，以及最终会汇聚到哪条主链路。</Text>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
