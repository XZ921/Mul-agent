import { Alert, Button, Card, Empty, Form, Input, List, Radio, Select, Space, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { ingestKnowledgeSource, listKnowledgeDomainDocuments, listKnowledgeDomains } from '../../api/client'
import type { KnowledgeDocumentInfo, KnowledgeDomainInfo, KnowledgeIngestionPayload } from '../../types'
import { appMessage } from '../../utils/appMessage'
import { buildGovernanceActionFailureMessage } from '../../utils/governancePresentation'

const { Paragraph, Text } = Typography
const { TextArea } = Input

interface KnowledgeSourceIntakeCardProps {
  taskId?: number | null
  competitorName?: string | null
  title?: string
}

interface KnowledgeSourceFormValues {
  domainKey?: string
  sourceCategory?: string
  title?: string
  url?: string
  connectorKey?: string
  sourceUrlsText?: string
  contentText?: string
}

const SOURCE_CATEGORY_LABELS: Record<string, string> = {
  UPLOADED_DOCUMENTS: '上传资料',
  AUTHENTICATED_SOURCES: '受控连接器',
  USER_PROVIDED: '人工补充',
  AI_DISCOVERED: 'AI 发现',
}

/**
 * 统一把主链接和补充链接清洗成后端约定的 sourceUrls，
 * 避免页面层在每次提交时重复处理去重和空白数据。
 */
function buildSourceUrls(url?: string, sourceUrlsText?: string) {
  const deduplicated = new Set<string>()
  const candidates = [
    url,
    ...(sourceUrlsText || '')
      .split(/[\n,，]/)
      .map((item) => item.trim()),
  ]

  candidates.forEach((candidate) => {
    if (candidate) {
      deduplicated.add(candidate)
    }
  })

  return Array.from(deduplicated)
}

function buildReadableError(error: unknown, fallback: string) {
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

function buildCategoryOptions(domain?: KnowledgeDomainInfo | null) {
  const categories = domain?.allowedSourceCategories?.length
    ? domain.allowedSourceCategories
    : ['UPLOADED_DOCUMENTS', 'USER_PROVIDED']
  return categories.map((category) => ({
    label: SOURCE_CATEGORY_LABELS[category] || category,
    value: category,
  }))
}

export default function KnowledgeSourceIntakeCard({
  taskId,
  competitorName,
  title = '资料接入',
}: KnowledgeSourceIntakeCardProps) {
  const [form] = Form.useForm<KnowledgeSourceFormValues>()
  const [domains, setDomains] = useState<KnowledgeDomainInfo[]>([])
  const [documents, setDocuments] = useState<KnowledgeDocumentInfo[]>([])
  const [latestDocument, setLatestDocument] = useState<KnowledgeDocumentInfo | null>(null)
  const [loadingDomains, setLoadingDomains] = useState(false)
  const [loadingDocuments, setLoadingDocuments] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const selectedDomainKey = Form.useWatch('domainKey', form)
  const selectedSourceCategory = Form.useWatch('sourceCategory', form)
  const selectedDomain = domains.find((item) => item.domainKey === selectedDomainKey) || null
  const categoryOptions = buildCategoryOptions(selectedDomain)

  useEffect(() => {
    void loadDomains()
  }, [])

  useEffect(() => {
    if (!selectedDomainKey) {
      return
    }

    const currentCategory = form.getFieldValue('sourceCategory')
    const nextCategories = buildCategoryOptions(selectedDomain)
    const firstAllowedCategory = nextCategories[0]?.value
    if (firstAllowedCategory && !nextCategories.some((option) => option.value === currentCategory)) {
      form.setFieldValue('sourceCategory', firstAllowedCategory)
    }

    void loadDomainDocuments(selectedDomainKey)
  }, [form, selectedDomain, selectedDomainKey])

  async function loadDomains() {
    setLoadingDomains(true)
    try {
      const response = await listKnowledgeDomains()
      const nextDomains = response.data || []
      setDomains(nextDomains)
      if (!nextDomains.length) {
        return
      }

      const currentDomainKey = form.getFieldValue('domainKey')
      const activeDomain = nextDomains.find((domain) => domain.domainKey === currentDomainKey) || nextDomains[0]
      form.setFieldsValue({
        domainKey: activeDomain.domainKey,
        sourceCategory: buildCategoryOptions(activeDomain)[0]?.value,
      })
    } catch (error) {
      appMessage.error(`知识域加载失败：${buildReadableError(error, '请稍后重试')}`)
      setDomains([])
    } finally {
      setLoadingDomains(false)
    }
  }

  async function loadDomainDocuments(domainKey: string) {
    setLoadingDocuments(true)
    try {
      const response = await listKnowledgeDomainDocuments(domainKey)
      setDocuments(response.data || [])
    } catch (error) {
      appMessage.warning(`资料摘要加载失败：${buildReadableError(error, '暂时无法查看已有资料')}`)
      setDocuments([])
    } finally {
      setLoadingDocuments(false)
    }
  }

  async function handleSubmit(values: KnowledgeSourceFormValues) {
    const payload: KnowledgeIngestionPayload = {
      taskId: taskId ?? undefined,
      competitorName: competitorName || undefined,
      domainKey: values.domainKey || '',
      sourceCategory: values.sourceCategory || '',
      title: values.title?.trim() || undefined,
      url: values.url?.trim() || undefined,
      connectorKey: values.connectorKey?.trim() || undefined,
      sourceUrls: buildSourceUrls(values.url, values.sourceUrlsText),
      contentText: values.contentText?.trim() || undefined,
    }

    setSubmitting(true)
    try {
      const response = await ingestKnowledgeSource(payload)
      setLatestDocument(response.data)
      appMessage.success('资料已接入知识域')
      form.setFieldsValue({
        title: undefined,
        url: undefined,
        connectorKey: undefined,
        sourceUrlsText: undefined,
        contentText: undefined,
      })
      if (payload.domainKey) {
        await loadDomainDocuments(payload.domainKey)
      }
    } catch (error) {
      appMessage.error(buildGovernanceActionFailureMessage(error, '资料接入失败', '请检查输入后重试'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card title={title} className="work-card">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="先把可复用资料沉淀到知识域"
          description="这里会保留来源分类、sourceUrls 和后续任务消费摘要，避免资料进入系统后失去追溯链路。"
        />

        <Form<KnowledgeSourceFormValues> form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="domainKey"
            label="知识域"
            rules={[{ required: true, message: '请选择知识域' }]}
          >
            <Select
              loading={loadingDomains}
              placeholder="请选择知识域"
              options={domains.map((domain) => ({
                label: domain.domainName,
                value: domain.domainKey,
              }))}
            />
          </Form.Item>

          <Form.Item
            name="sourceCategory"
            label="资料来源"
            rules={[{ required: true, message: '请选择资料来源' }]}
          >
            <Radio.Group optionType="button" buttonStyle="solid" options={categoryOptions} />
          </Form.Item>

          <Form.Item
            name="title"
            label="资料标题"
            rules={[{ required: true, message: '请输入资料标题' }]}
          >
            <Input placeholder="例如：产品发布手册" />
          </Form.Item>

          <Form.Item
            name="url"
            label="主链接"
            rules={[{ required: true, message: '请输入主链接' }]}
          >
            <Input placeholder="https://docs.example.com/launch-guide.pdf" />
          </Form.Item>

          {selectedSourceCategory === 'AUTHENTICATED_SOURCES' && (
            <Form.Item
              name="connectorKey"
              label="连接器标识"
              rules={[{ required: true, message: '受控连接器资料必须填写连接器标识' }]}
            >
              <Input placeholder="例如：feishu-drive" />
            </Form.Item>
          )}

          <Form.Item name="sourceUrlsText" label="补充来源链接">
            <TextArea rows={3} placeholder="可按换行补充更多 sourceUrls，用于后续证据回指" />
          </Form.Item>

          <Form.Item
            name="contentText"
            label="正文内容"
            rules={[{ required: true, message: '请输入正文内容' }]}
          >
            <TextArea rows={4} placeholder="可粘贴正文、摘要或清洗后的文档内容" />
          </Form.Item>

          <Button type="primary" htmlType="submit" loading={submitting}>
            接入资料
          </Button>
        </Form>

        {selectedDomain && (
          <Card size="small" title={selectedDomain.domainName} extra={<Tag>{selectedDomain.defaultTrustLevel}</Tag>}>
            <Paragraph type="secondary" style={{ marginBottom: 0 }}>
              {selectedDomain.description || '当前知识域用于沉淀可复用资料。'}
            </Paragraph>
          </Card>
        )}

        {latestDocument && (
          <Alert
            type="success"
            showIcon
            message={latestDocument.title}
            description={
              <Space direction="vertical" size={4}>
                <Text>{latestDocument.traceSummary || '资料已接入，可继续用于后续任务。'}</Text>
                {!!latestDocument.consumedEvidenceIds.length && (
                  <Space wrap>
                    {latestDocument.consumedEvidenceIds.map((evidenceId) => (
                      <Tag key={evidenceId}>{evidenceId}</Tag>
                    ))}
                  </Space>
                )}
              </Space>
            }
          />
        )}

        <div>
          <Text strong>已有资料摘要</Text>
          <div style={{ marginTop: 12 }}>
            {documents.length === 0 ? (
              <Card size="small" loading={loadingDocuments}>
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={loadingDocuments ? '正在加载资料摘要' : '当前知识域还没有资料'}
                />
              </Card>
            ) : (
              <List
                loading={loadingDocuments}
                dataSource={documents}
                renderItem={(document) => (
                  <List.Item>
                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                      <Space wrap>
                        <Text strong>{document.title}</Text>
                        <Tag>{SOURCE_CATEGORY_LABELS[document.sourceCategory] || document.sourceCategory}</Tag>
                        <Tag color="blue">{document.trustLevel}</Tag>
                      </Space>
                      <Text type="secondary">{document.traceSummary || document.url}</Text>
                    </Space>
                  </List.Item>
                )}
              />
            )}
          </div>
        </div>
      </Space>
    </Card>
  )
}
