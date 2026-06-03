import { Alert, Button, Card, Empty, List, Space, Tag, Typography } from 'antd'
import type { DiagnosisSectionInfo, ReportDiagnosisInfo, ReviewNextAction } from '../../types'
import {
  getDiagnosisStageText,
  getNodeNameLabel,
  getReviewLevelText,
  getReviewSectionText,
  getReviewSeverityText,
  getReviewTypeText,
  getSourceTypeText,
} from '../../utils/display'

const { Paragraph, Text } = Typography

function severityColor(severity?: string | null) {
  if (severity === 'ERROR') return 'red'
  if (severity === 'WARNING') return 'orange'
  return 'blue'
}

function levelColor(level?: string | null) {
  if (level === 'BLOCKER') return 'red'
  if (level === 'MAJOR') return 'orange'
  return 'blue'
}

function actionTypeText(actionType?: string | null) {
  if (actionType === 'SUPPLEMENT_EVIDENCE') return '补充证据'
  if (actionType === 'RERUN_NODE') return '重跑节点'
  if (actionType === 'REWRITE_CLAIM') return '改写结论'
  if (actionType === 'MANUAL_REVIEW') return '人工复核'
  return actionType || '后续动作'
}

function actionPriorityColor(priority?: string | null) {
  if (priority === 'HIGH') return 'red'
  if (priority === 'MEDIUM') return 'orange'
  return 'blue'
}

interface ReportDiagnosisPanelProps {
  diagnosis: ReportDiagnosisInfo | null | undefined
  actionLoading?: boolean
  onAction?: (action: ReviewNextAction) => Promise<void> | void
  compact?: boolean
}

function renderSection(section: DiagnosisSectionInfo, actionLoading: boolean, onAction?: (action: ReviewNextAction) => Promise<void> | void) {
  return (
    <div className="diagnosis-section" key={section.section}>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          <Tag color={section.evidenceInsufficient ? 'red' : 'blue'}>{getReviewSectionText(section.section)}</Tag>
          {section.evidenceInsufficient && <Tag color="orange">证据不足</Tag>}
          {(section.sourceUrls || []).length > 0 && <Tag>{`来源 ${section.sourceUrls?.length || 0}`}</Tag>}
        </Space>

        {section.repairSuggestions && section.repairSuggestions.length > 0 && (
          <Alert
            type={section.evidenceInsufficient ? 'warning' : 'info'}
            showIcon
            message="修复建议"
            description={
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {section.repairSuggestions.map((suggestion) => (
                  <Text key={suggestion}>{suggestion}</Text>
                ))}
              </Space>
            }
          />
        )}

        <List
          size="small"
          dataSource={section.diagnoses}
          renderItem={(item, index) => (
            <List.Item key={`${section.section}-${item.reviewStage}-${index}`}>
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Space wrap>
                  <Tag color="geekblue">{getDiagnosisStageText(item.reviewStage)}</Tag>
                  <Tag color={severityColor(item.diagnosis.severity)}>{getReviewSeverityText(item.diagnosis.severity)}</Tag>
                  {item.diagnosis.level && <Tag color={levelColor(item.diagnosis.level)}>{getReviewLevelText(item.diagnosis.level)}</Tag>}
                  <Text strong>{item.diagnosis.title || getReviewTypeText(item.diagnosis.type)}</Text>
                </Space>

                <Text>{item.diagnosis.detail || item.diagnosis.repairSuggestion || '暂无详细说明'}</Text>

                {item.diagnosis.evidenceBasis && (
                  <Alert type="info" showIcon message="诊断依据" description={item.diagnosis.evidenceBasis} />
                )}

                {item.evidenceReferences.length > 0 && (
                  <div className="diagnosis-evidence-list">
                    {item.evidenceReferences.map((reference, referenceIndex) => (
                      <div className="diagnosis-evidence-item" key={`${reference.evidenceId || reference.url || 'ref'}-${referenceIndex}`}>
                        <Space wrap>
                          {reference.evidenceId && <Tag color="blue">{reference.evidenceId}</Tag>}
                          {reference.sourceType && <Tag color="cyan">{getSourceTypeText(reference.sourceType)}</Tag>}
                          {reference.competitorName && <Tag>{reference.competitorName}</Tag>}
                        </Space>
                        <Text strong>{reference.title || reference.url || '未命名证据'}</Text>
                        {reference.url && (
                          <a href={reference.url} target="_blank" rel="noopener noreferrer" className="diagnosis-link">
                            {reference.url}
                          </a>
                        )}
                        {reference.contentSnippet && <Paragraph className="diagnosis-snippet">“{reference.contentSnippet}”</Paragraph>}
                      </div>
                    ))}
                  </div>
                )}

                {onAction && item.reviewStage !== 'REPORT' && (
                  <Space wrap>
                    {(section.repairSuggestions || []).length > 0 && (
                      <Text type="secondary">建议先结合下方动作处理，再回看本章节。</Text>
                    )}
                  </Space>
                )}
              </Space>
            </List.Item>
          )}
        />
      </Space>
    </div>
  )
}

/**
 * 报告诊断面板直接消费后端返回的 reportDiagnosis。
 * 页面只负责传入动作回调，不再自己拼装“章节 -> 诊断 -> 证据引用”的嵌套关系。
 */
export default function ReportDiagnosisPanel({
  diagnosis,
  actionLoading = false,
  onAction,
  compact = false,
}: ReportDiagnosisPanelProps) {
  if (!diagnosis) {
    return <Empty description="当前报告尚未返回统一诊断模型" />
  }

  const sections = compact ? diagnosis.sections.slice(0, 2) : diagnosis.sections
  const contentEvidences = compact ? (diagnosis.contentEvidences || []).slice(0, 4) : diagnosis.contentEvidences || []

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div className="diagnosis-summary-grid">
        <div className="diagnosis-summary-card">
          <span className="diagnosis-summary-value">{diagnosis.diagnosisCount ?? 0}</span>
          <span className="diagnosis-summary-label">诊断项</span>
        </div>
        <div className="diagnosis-summary-card">
          <span className="diagnosis-summary-value">{diagnosis.blockerCount ?? 0}</span>
          <span className="diagnosis-summary-label">阻塞问题</span>
        </div>
        <div className="diagnosis-summary-card">
          <span className="diagnosis-summary-value">{diagnosis.evidenceGapCount ?? 0}</span>
          <span className="diagnosis-summary-label">证据缺口</span>
        </div>
        <div className="diagnosis-summary-card">
          <span className="diagnosis-summary-value">{(diagnosis.sourceUrls || []).length}</span>
          <span className="diagnosis-summary-label">关联来源</span>
        </div>
      </div>

      {(diagnosis.blockerCount || 0) > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`当前存在 ${diagnosis.blockerCount} 个阻塞级问题`}
          description="建议优先处理阻塞问题对应的章节与证据，再决定是否重跑节点或继续改写。"
        />
      )}

      {diagnosis.nextActions && diagnosis.nextActions.length > 0 && onAction && (
        <Card size="small" title="建议动作">
          <div className="diagnosis-next-actions">
            {diagnosis.nextActions.map((action, index) => (
              <div className="diagnosis-next-action" key={`${action.actionType || 'action'}-${index}`}>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag color={actionPriorityColor(action.priority)}>{action.priority || 'MEDIUM'}</Tag>
                    <Tag color="geekblue">{actionTypeText(action.actionType)}</Tag>
                    {action.targetNode && <Tag>{getNodeNameLabel(action.targetNode)}</Tag>}
                  </Space>
                  <Text strong>{action.title || '处理诊断问题'}</Text>
                  {action.description && <Text type="secondary">{action.description}</Text>}
                  <Space wrap>
                    <Button loading={actionLoading} type={action.actionType === 'RERUN_NODE' || action.actionType === 'REWRITE_CLAIM' ? 'primary' : 'default'} onClick={() => void onAction(action)}>
                      {action.title || actionTypeText(action.actionType)}
                    </Button>
                  </Space>
                </Space>
              </div>
            ))}
          </div>
        </Card>
      )}

      {sections.length > 0 ? (
        <Card size="small" title="章节诊断">
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            {sections.map((section) => renderSection(section, actionLoading, onAction))}
          </Space>
        </Card>
      ) : (
        <Alert type="success" showIcon message="当前没有章节级诊断问题" />
      )}

      {contentEvidences.length > 0 && (
        <Card size="small" title="正文证据回流">
          <List
            size="small"
            dataSource={contentEvidences}
            renderItem={(item, index) => (
              <List.Item key={`${item.evidenceId || item.sourceUrl || 'content'}-${index}`}>
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Space wrap>
                    {item.evidenceId && <Tag color="blue">{item.evidenceId}</Tag>}
                    {item.competitorName && <Tag>{item.competitorName}</Tag>}
                    {item.fieldName && <Tag color="purple">{item.fieldName}</Tag>}
                    {(item.issueFlags || []).map((flag) => (
                      <Tag color="orange" key={flag}>
                        {flag}
                      </Tag>
                    ))}
                  </Space>
                  <Text strong>{item.title || item.sourceUrl || '未命名正文证据'}</Text>
                  {item.sourceUrl && (
                    <a href={item.sourceUrl} target="_blank" rel="noopener noreferrer" className="diagnosis-link">
                      {item.sourceUrl}
                    </a>
                  )}
                  {item.snippet && <Paragraph className="diagnosis-snippet">“{item.snippet}”</Paragraph>}
                </Space>
              </List.Item>
            )}
          />
        </Card>
      )}
    </Space>
  )
}
