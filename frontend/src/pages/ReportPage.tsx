import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  List,
  Modal,
  Row,
  Space,
  Spin,
  Statistic,
  Tag,
  message,
} from 'antd'
import {
  ArrowLeftOutlined,
  DownloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import { getExportUrl, getHtmlExportUrl, getReport } from '../api/client'
import EvidenceList from '../components/EvidenceList'
import MarkdownReport from '../components/MarkdownReport'
import type { QualityIssue, ReportInfo, ReviewCheckpointInfo } from '../types'

// 质量问题在多个区块复用同一套颜色语义，方便快速区分错误、警告与提示。
function issueColor(severity: QualityIssue['severity']) {
  if (severity === 'ERROR') return 'red'
  if (severity === 'WARNING') return 'orange'
  return 'blue'
}

// ReviewCard 负责渲染单次质检检查点，初审与终审复用同一套展示逻辑。
function ReviewCard({ title, review }: { title: string; review: ReviewCheckpointInfo | null }) {
  if (!review) {
    return null
  }

  return (
    <Card title={title} className="work-card" style={{ marginTop: 16 }}>
      <Space wrap style={{ marginBottom: 12 }}>
        <Tag color={review.passed ? 'green' : review.passed === false ? 'orange' : 'default'}>
          {review.passed ? 'Passed' : review.passed === false ? 'Needs revision' : review.nodeStatus}
        </Tag>
        {review.score != null && <Tag color="blue">Score {review.score}/100</Tag>}
        <Tag>{review.nodeName}</Tag>
      </Space>

      {review.summary && (
        <Alert
          type="info"
          showIcon
          message="Reviewer Summary"
          description={review.summary}
          style={{ marginBottom: 12 }}
        />
      )}

      {review.issues.length > 0 && (
        <Collapse
          items={review.issues.map((issue, index) => ({
            key: `${review.nodeName}-${index}`,
            label: (
              <Space>
                <Tag color={issueColor(issue.severity)}>{issue.severity}</Tag>
                <span>{issue.section || 'General'}</span>
                <span className="muted-text">{issue.type}</span>
              </Space>
            ),
            children: <p>{issue.suggestion}</p>,
          }))}
        />
      )}
    </Card>
  )
}

export default function ReportPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const taskId = Number(id)

  const [report, setReport] = useState<ReportInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [evidenceOpen, setEvidenceOpen] = useState(false)

  // 报告页以最终报告对象为中心，一次性加载评分、证据、修订计划与正文。
  useEffect(() => {
    getReport(taskId)
      .then((res) => setReport(res.data))
      .catch(() => message.error('Failed to load report'))
      .finally(() => setLoading(false))
  }, [taskId])

  const qualityIssueCount = useMemo(() => report?.qualityIssues?.length || 0, [report])

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 80 }} />
  }

  if (!report) {
    return (
      <Alert
        type="warning"
        showIcon
        message="Report not found"
        description="Run the task successfully before opening the report page."
      />
    )
  }

  return (
    <div>
      <div className="page-toolbar">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/task/${taskId}`)}>
            Back to Task
          </Button>
          <Button icon={<DownloadOutlined />} onClick={() => window.open(getExportUrl(taskId))}>
            Export Markdown
          </Button>
          <Button icon={<DownloadOutlined />} onClick={() => window.open(getHtmlExportUrl(taskId))}>
            Export HTML
          </Button>
          <Button icon={<SafetyCertificateOutlined />} onClick={() => setEvidenceOpen(true)}>
            View Evidence
          </Button>
        </Space>
      </div>

      <div className="page-header">
        <h2>{report.title}</h2>
        <Space wrap>
          {report.qualityScore != null && (
            <Tag color={report.qualityPassed ? 'green' : 'orange'}>
              Quality {report.qualityScore}/100 - {report.qualityPassed ? 'Passed' : 'Needs attention'}
            </Tag>
          )}
          <Tag>Evidence {report.evidenceCount}</Tag>
          <Tag color={report.rewriteApplied ? 'orange' : 'default'}>
            {report.rewriteApplied ? 'Rewrite applied' : 'No rewrite needed'}
          </Tag>
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        {/* 头部统计卡把报告质量、证据规模、问题数量聚合成一眼能看的概览。 */}
        <Col xs={24} lg={7}>
          <Card className="work-card">
            <Statistic title="Quality Score" value={report.qualityScore ?? 0} suffix="/100" />
          </Card>
        </Col>
        <Col xs={24} lg={7}>
          <Card className="work-card">
            <Statistic title="Evidence Sources" value={report.evidenceCount} />
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card className="work-card">
            <Statistic title="Quality Issues" value={qualityIssueCount} />
          </Card>
        </Col>
      </Row>

      <Card title="Report Summary" className="work-card" style={{ marginTop: 16 }}>
        <Descriptions column={1} size="small">
          <Descriptions.Item label="Summary">{report.summary || 'No summary available'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {report.revisionPlan && (
        <Card title="Revision Plan" className="work-card" style={{ marginTop: 16 }}>
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {/* 这里直接暴露 Reviewer 生成的修订计划，帮助用户理解为什么触发重写。 */}
            <Alert
              type={report.revisionPlan.rewriteRequired ? 'warning' : 'success'}
              showIcon
              message={report.revisionPlan.rewriteRequired ? 'Rewrite requested by reviewer' : 'Rewrite not required'}
              description={report.revisionPlan.summary || 'No revision summary provided'}
            />

            {report.revisionPlan.items.length > 0 && (
              <Collapse
                items={report.revisionPlan.items.map((item, index) => ({
                  key: String(index),
                  label: (
                    <Space>
                      <Tag color={issueColor(item.severity)}>{item.severity}</Tag>
                      <span>{item.section || 'General'}</span>
                      <span className="muted-text">{item.type}</span>
                    </Space>
                  ),
                  children: <p>{item.suggestion}</p>,
                }))}
              />
            )}

            {report.revisionPlan.rewriteGuidelines.length > 0 && (
              <List
                size="small"
                bordered
                header="Rewrite Guidelines"
                dataSource={report.revisionPlan.rewriteGuidelines}
                renderItem={(item) => <List.Item>{item}</List.Item>}
              />
            )}
          </Space>
        </Card>
      )}

      <ReviewCard title="Initial Review" review={report.initialReview} />
      <ReviewCard title="Final Review" review={report.finalReview} />

      {report.competitorKnowledges.length > 0 && (
        <Card title="Knowledge Traceability" className="work-card" style={{ marginTop: 16 }}>
          {/* 竞品知识块把摘要、来源链接、字段级覆盖情况串起来，支撑报告可溯源。 */}
          <Collapse
            items={report.competitorKnowledges.map((knowledge) => ({
              key: knowledge.competitorName,
              label: (
                <Space wrap>
                  <span>{knowledge.competitorName}</span>
                  <Tag color="blue">Sources {knowledge.sourceUrls.length}</Tag>
                </Space>
              ),
              children: (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <p>{knowledge.summary || 'No summary available'}</p>
                  <List
                    size="small"
                    bordered
                    header="Source URLs"
                    dataSource={knowledge.sourceUrls}
                    renderItem={(item) => <List.Item>{item}</List.Item>}
                  />
                  <pre className="code-block">{JSON.stringify(knowledge.evidenceCoverage, null, 2)}</pre>
                </Space>
              ),
            }))}
          />
        </Card>
      )}

      {qualityIssueCount > 0 && (
        <Card title="Latest Quality Feedback" className="work-card" style={{ marginTop: 16 }}>
          <Collapse
            items={report.qualityIssues.map((issue, index) => ({
              key: String(index),
              label: (
                <Space>
                  <Tag color={issueColor(issue.severity)}>{issue.severity}</Tag>
                  <span>{issue.section || 'General'}</span>
                  <span className="muted-text">{issue.type}</span>
                </Space>
              ),
              children: <p>{issue.suggestion}</p>,
            }))}
          />
        </Card>
      )}

      <div style={{ marginTop: 16 }}>
        {/* MarkdownReport 负责正文渲染，并把证据标记嵌回正文，形成阅读态溯源体验。 */}
        <MarkdownReport content={report.content} evidences={report.evidences} />
      </div>

      <Modal
        title={`Evidence Sources (${report.evidences.length})`}
        open={evidenceOpen}
        onCancel={() => setEvidenceOpen(false)}
        width={900}
        footer={null}
      >
        {/* 弹窗保留完整证据列表，便于从报告页继续追查原始来源。 */}
        <EvidenceList evidences={report.evidences} />
      </Modal>
    </div>
  )
}
