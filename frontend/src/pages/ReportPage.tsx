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
import {
  getNodeNameLabel,
  getNodeStatusText,
  getReviewPassedText,
  getReviewSectionText,
  getReviewSeverityText,
  getReviewTypeText,
  normalizeReportSummary,
  normalizeReportTitle,
} from '../utils/display'

function issueColor(severity: QualityIssue['severity']) {
  if (severity === 'ERROR') return 'red'
  if (severity === 'WARNING') return 'orange'
  return 'blue'
}

function ReviewCard({ title, review }: { title: string; review: ReviewCheckpointInfo | null }) {
  if (!review) {
    return null
  }

  return (
    <Card title={title} className="work-card" style={{ marginTop: 16 }}>
      <Space wrap style={{ marginBottom: 12 }}>
        <Tag color={review.passed ? 'green' : review.passed === false ? 'orange' : 'default'}>
          {review.passed == null ? getNodeStatusText(review.nodeStatus) : getReviewPassedText(review.passed)}
        </Tag>
        {review.score != null && <Tag color="blue">评分 {review.score}/100</Tag>}
        <Tag>{getNodeNameLabel(review.nodeName)}</Tag>
      </Space>

      {review.summary && (
        <Alert type="info" showIcon message="评审摘要" description={review.summary} style={{ marginBottom: 12 }} />
      )}

      {review.issues.length > 0 && (
        <Collapse
          items={review.issues.map((issue, index) => ({
            key: `${review.nodeName}-${index}`,
            label: (
              <Space>
                <Tag color={issueColor(issue.severity)}>{getReviewSeverityText(issue.severity)}</Tag>
                <span>{getReviewSectionText(issue.section)}</span>
                <span className="muted-text">{getReviewTypeText(issue.type)}</span>
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

  useEffect(() => {
    getReport(taskId)
      .then((res) => setReport(res.data))
      .catch(() => message.error('加载报告失败'))
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
        message="未找到报告"
        description="请先成功执行任务，再打开报告页面。"
      />
    )
  }

  return (
    <div>
      <div className="page-toolbar">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/task/${taskId}`)}>
            返回任务
          </Button>
          <Button icon={<DownloadOutlined />} onClick={() => window.open(getExportUrl(taskId))}>
            导出文本版
          </Button>
          <Button icon={<DownloadOutlined />} onClick={() => window.open(getHtmlExportUrl(taskId))}>
            导出网页版
          </Button>
          <Button icon={<SafetyCertificateOutlined />} onClick={() => setEvidenceOpen(true)}>
            查看证据
          </Button>
        </Space>
      </div>

      <div className="page-header">
        <h2>{normalizeReportTitle(report.title)}</h2>
        <Space wrap>
          {report.qualityScore != null && (
            <Tag color={report.qualityPassed ? 'green' : 'orange'}>
              质量 {report.qualityScore}/100 - {report.qualityPassed ? '已通过' : '需关注'}
            </Tag>
          )}
          <Tag>证据 {report.evidenceCount}</Tag>
          <Tag color={report.rewriteApplied ? 'orange' : 'default'}>
            {report.rewriteApplied ? '已执行改写' : '无需改写'}
          </Tag>
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={7}>
          <Card className="work-card">
            <Statistic title="质量评分" value={report.qualityScore ?? 0} suffix="/100" />
          </Card>
        </Col>
        <Col xs={24} lg={7}>
          <Card className="work-card">
            <Statistic title="证据来源数" value={report.evidenceCount} />
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card className="work-card">
            <Statistic title="质量问题数" value={qualityIssueCount} />
          </Card>
        </Col>
      </Row>

      <Card title="报告摘要" className="work-card" style={{ marginTop: 16 }}>
        <Descriptions column={1} size="small">
          <Descriptions.Item label="摘要">{normalizeReportSummary(report.summary) || '暂无摘要'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {report.revisionPlan && (
        <Card title="修订计划" className="work-card" style={{ marginTop: 16 }}>
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type={report.revisionPlan.rewriteRequired ? 'warning' : 'success'}
              showIcon
              message={report.revisionPlan.rewriteRequired ? '评审要求改写' : '无需改写'}
              description={report.revisionPlan.summary || '暂无修订说明'}
            />

            {report.revisionPlan.items.length > 0 && (
              <Collapse
                items={report.revisionPlan.items.map((item, index) => ({
                  key: String(index),
                  label: (
                    <Space>
                      <Tag color={issueColor(item.severity)}>{getReviewSeverityText(item.severity)}</Tag>
                      <span>{getReviewSectionText(item.section)}</span>
                      <span className="muted-text">{getReviewTypeText(item.type)}</span>
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
                header="改写指引"
                dataSource={report.revisionPlan.rewriteGuidelines}
                renderItem={(item) => <List.Item>{item}</List.Item>}
              />
            )}
          </Space>
        </Card>
      )}

      <ReviewCard title="初审结果" review={report.initialReview} />
      <ReviewCard title="终审结果" review={report.finalReview} />

      {report.competitorKnowledges.length > 0 && (
        <Card title="知识溯源" className="work-card" style={{ marginTop: 16 }}>
          <Collapse
            items={report.competitorKnowledges.map((knowledge) => ({
              key: knowledge.competitorName,
              label: (
                <Space wrap>
                  <span>{knowledge.competitorName}</span>
                  <Tag color="blue">来源 {knowledge.sourceUrls.length}</Tag>
                </Space>
              ),
              children: (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <p>{normalizeReportSummary(knowledge.summary) || '暂无摘要'}</p>
                  <List
                    size="small"
                    bordered
                    header="来源链接"
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
        <Card title="最新质量反馈" className="work-card" style={{ marginTop: 16 }}>
          <Collapse
            items={report.qualityIssues.map((issue, index) => ({
              key: String(index),
              label: (
                <Space>
                  <Tag color={issueColor(issue.severity)}>{getReviewSeverityText(issue.severity)}</Tag>
                  <span>{getReviewSectionText(issue.section)}</span>
                  <span className="muted-text">{getReviewTypeText(issue.type)}</span>
                </Space>
              ),
              children: <p>{issue.suggestion}</p>,
            }))}
          />
        </Card>
      )}

      <div style={{ marginTop: 16 }}>
        <MarkdownReport content={report.content} evidences={report.evidences} />
      </div>

      <Modal
        title={`证据来源（${report.evidences.length}）`}
        open={evidenceOpen}
        onCancel={() => setEvidenceOpen(false)}
        width={900}
        footer={null}
      >
        <EvidenceList evidences={report.evidences} />
      </Modal>
    </div>
  )
}
