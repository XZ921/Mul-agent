import { Alert, Space, Typography } from 'antd'
import type { SourceStrategyLaneInfo, SourceStrategyOverviewInfo } from '../../types'

const { Text } = Typography

interface SourceStrategySummaryProps {
  overview: SourceStrategyOverviewInfo
  hasReadyPreview: boolean
}

function summarizeItems(items: string[], limit = 3) {
  if (!items.length) {
    return '待补充'
  }
  if (items.length <= limit) {
    return items.join('、')
  }
  return `${items.slice(0, limit).join('、')} 等 ${items.length} 项`
}

/**
 * 首屏来源策略只讲“采什么、从哪里开始、何时补充与验证”，
 * 不直接把 Query、候选链接或技术枚举摊在默认路径里。
 */
function buildLaneSummary(lane: SourceStrategyLaneInfo) {
  const coverage = lane.sourceScope.length ? lane.sourceScope : lane.sourceLabels
  return `优先覆盖 ${summarizeItems(coverage)}，共规划 ${lane.branchCount} 条资料分支。`
}

function buildLaneDetail(lane: SourceStrategyLaneInfo) {
  const startText = lane.entryUrlCount > 0
    ? `会先从 ${lane.entryUrlCount} 个已知入口开始`
    : '会先自动寻找首批可信入口'
  const supplementText = lane.browserSupplementEnabled ? '，必要时补充网页检索' : ''
  const verifyText = lane.verificationEnabled
    ? `，正式采集前会验证至少 ${lane.minVerifiedCandidates || 1} 条结果页`
    : ''
  return `${startText}${supplementText}${verifyText}。`
}

export default function SourceStrategySummary({
  overview,
  hasReadyPreview,
}: SourceStrategySummaryProps) {
  if (!hasReadyPreview) {
    return (
      <Alert
        type="info"
        showIcon
        message="这里会先说明资料策略"
        description="补全任务目标和竞品后，系统会在这里告诉你优先采集哪些资料、从哪里开始，以及何时补充网页检索。"
      />
    )
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div className="plan-preview-hero">
        <div className="plan-preview-stat">
          <span className="plan-preview-value">{overview.competitorCount}</span>
          <span className="plan-preview-label">竞品对象</span>
        </div>
        <div className="plan-preview-stat">
          <span className="plan-preview-value">{overview.collectorCount}</span>
          <span className="plan-preview-label">资料分支</span>
        </div>
        <div className="plan-preview-stat">
          <span className="plan-preview-value">{overview.browserSupplementCount}</span>
          <span className="plan-preview-label">需要扩展检索</span>
        </div>
      </div>

      <Alert
        type="info"
        showIcon
        message="默认先讲资料覆盖与补充策略"
        description="首屏不直接展示原始检索词、候选来源链接和技术枚举标签，先帮助你判断每个竞品会采哪些资料、从哪里开始以及如何补充。"
      />

      <div className="preview-lane-board">
        {overview.lanes.map((lane) => (
          <div className="preview-lane" key={lane.competitorName}>
            <div className="preview-lane-header">
              <Space direction="vertical" size={2}>
                <Text strong>{lane.competitorName}</Text>
                <Text type="secondary">{buildLaneSummary(lane)}</Text>
              </Space>
              <Text type="secondary">{`${lane.branchCount} 条分支`}</Text>
            </div>

            <div className="preview-node-meta">
              <span>{buildLaneDetail(lane)}</span>
              <span>{`计划覆盖：${summarizeItems(lane.sourceScope.length ? lane.sourceScope : lane.sourceLabels)}`}</span>
              <span>{`预估候选来源：${lane.candidateCount} 条`}</span>
            </div>

            {(lane.preferredDomains.length > 0 || lane.notes.length > 0) && (
              <div className="preview-node-footnote">
                {lane.preferredDomains.length > 0 && (
                  <Text type="secondary">{`优先关注：${summarizeItems(lane.preferredDomains)}`}</Text>
                )}
                {lane.notes.map((note) => (
                  <Text key={note} type="secondary">
                    {note}
                  </Text>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </Space>
  )
}
