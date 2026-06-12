import { Card, Empty, Space, Typography } from 'antd'
import dayjs from 'dayjs'
import type { ReplayPlanVersionSummary, ReplayTimelineEvent, SearchReplaySnapshotInfo, TaskReplayResponse } from '../../types'

const { Paragraph, Text } = Typography

function formatPlanVersion(planVersion?: number | null) {
  return planVersion == null ? '计划版本未知' : `计划版本 v${planVersion}`
}

function formatTimelineSummary(event: ReplayTimelineEvent) {
  if (event.summary.trim()) {
    return event.summary
  }
  const occurredAt = event.occurredAt && dayjs(event.occurredAt).isValid()
    ? dayjs(event.occurredAt).format('HH:mm')
    : null
  return occurredAt ? `${occurredAt} ${event.eventType}` : event.eventType
}

function resolvePlanVersionNumber(replay: TaskReplayResponse, planVersionId?: number | null) {
  if (planVersionId == null) {
    return replay.planVersions[0]?.planVersion ?? replay.recoveryCheckpoints[0]?.planVersion ?? null
  }

  return (
    replay.planVersions.find((item) => item.planVersionId === planVersionId)?.planVersion
    ?? replay.recoveryCheckpoints.find((item) => item.planVersionId === planVersionId)?.planVersion
    ?? null
  )
}

/**
 * 恢复窗口摘要要把“当前建议基于哪条计划分支与哪个版本”压缩成一行，
 * 让用户不用翻底层回放事件也能先判断这次恢复会落在哪个执行上下文里。
 */
function buildRecoveryWindowSummary(replay: TaskReplayResponse) {
  const recoveryWindow = replay.recoveryAdvice?.recoveryWindow
  if (!recoveryWindow) {
    return ''
  }

  const planVersion = resolvePlanVersionNumber(replay, recoveryWindow.planVersionId)
  return `恢复窗口：${recoveryWindow.windowScope} / ${recoveryWindow.branchKey || '未记录分支'} / ${formatPlanVersion(planVersion)}`
}

/**
 * 计划版本摘要把 replay 中的版本、分支和计划类型并排展示，
 * 这样页面首屏就能说明当前回放到底属于主链、恢复链还是动态回流链。
 */
function buildPlanVersionSummary(planVersion: ReplayPlanVersionSummary) {
  return `${formatPlanVersion(planVersion.planVersion)} · ${planVersion.branchKey || '未记录分支'} · ${planVersion.planType || '未记录类型'}`
}

function buildSearchReplaySummary(replay: SearchReplaySnapshotInfo) {
  const sourceUrls = Array.isArray(replay.sourceUrls) ? replay.sourceUrls : []
  const checkpoint = replay.searchAudit?.executionTrace?.recoveryCheckpoint ?? 'UNKNOWN'
  return `${replay.nodeName} · 检查点 ${checkpoint} · 来源 ${sourceUrls.length} 条`
}

type TaskReplayTimelineProps = {
  replay: TaskReplayResponse
}

export default function TaskReplayTimeline({ replay }: TaskReplayTimelineProps) {
  const primaryCheckpoint = replay.recoveryCheckpoints[0] || null
  const primaryPlanVersion = replay.planVersions.find((planVersion) => planVersion.active) || replay.planVersions[0] || null
  const recoveryWindowSummary = buildRecoveryWindowSummary(replay)
  const searchReplays = Array.isArray(replay.searchReplays) ? replay.searchReplays : []

  return (
    <Card className="work-card" title="正式回放与恢复">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {replay.recoveryAdvice ? (
          <div>
            <Text strong>恢复入口</Text>
            <Paragraph style={{ marginBottom: 8 }}>{`建议动作：${replay.recoveryAdvice.recommendedAction}`}</Paragraph>
            <Paragraph type="secondary" style={{ marginBottom: 8 }}>
              {replay.recoveryAdvice.summary}
            </Paragraph>
            {recoveryWindowSummary ? <Paragraph style={{ marginBottom: 0 }}>{recoveryWindowSummary}</Paragraph> : null}
          </div>
        ) : null}

        {replay.timeline.length > 0 ? (
          <div>
            <Text strong>回放时间线</Text>
            <Space direction="vertical" size={8} style={{ display: 'flex', marginTop: 8 }}>
              {replay.timeline.slice(0, 5).map((event) => (
                <Paragraph key={event.eventId} style={{ marginBottom: 0 }}>
                  {formatTimelineSummary(event)}
                </Paragraph>
              ))}
            </Space>
          </div>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有可展示的回放事件" />
        )}

        {searchReplays.length > 0 ? (
          <div>
            <Text strong>搜索现场回放</Text>
            <Space direction="vertical" size={8} style={{ display: 'flex', marginTop: 8 }}>
              {searchReplays.slice(0, 3).map((item) => (
                <Paragraph key={item.nodeName} style={{ marginBottom: 0 }}>
                  {buildSearchReplaySummary(item)}
                </Paragraph>
              ))}
            </Space>
          </div>
        ) : null}

        {primaryCheckpoint ? (
          <div>
            <Text strong>恢复检查点</Text>
            <Paragraph style={{ marginBottom: 0, marginTop: 8 }}>{primaryCheckpoint.summary}</Paragraph>
          </div>
        ) : null}

        {primaryPlanVersion ? (
          <div>
            <Text strong>计划版本</Text>
            <Paragraph style={{ marginBottom: 0, marginTop: 8 }}>
              {buildPlanVersionSummary(primaryPlanVersion)}
            </Paragraph>
          </div>
        ) : null}
      </Space>
    </Card>
  )
}
