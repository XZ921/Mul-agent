import { Alert, Button, Card, Descriptions, Drawer, List, Space, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import AdvancedDiagnosticSection from '../AdvancedDiagnosticSection'
import DebugJson from '../DebugJson'
import type {
  SearchExecutionPlanInfo,
  SearchExecutionTraceInfo,
  SearchProgressInfo,
  SelectedTargetInfo,
  SourceCandidateInfo,
  TaskNodeInfo,
} from '../../types'
import {
  getAgentTypeText,
  getNodeDisplayName,
  getNodeNameLabel,
  getReviewPassedText,
  getReviewSectionText,
  getReviewSeverityText,
  getReviewTypeText,
  getTaskEventStreamStatusText,
} from '../../utils/display'
import { getDependencyNames } from '../../utils/taskNodeInsights'
import {
  actionPriorityColor,
  actionTypeText,
  candidateStageColor,
  discoveryMethodTag,
  displayValue,
  formatDurationMs,
  formatScore,
  getNodeActionSummary,
  getNodeHandlingReason,
  getNodeNoticeType,
  getNodeSituationSummary,
  isEvidenceNode,
  isEvidenceRiskIssue,
  isPresent,
  isReviewNode,
  progressStatusTag,
  searchModeText,
  stageLabel,
  statusTag,
  stepStatusTag,
  supplementMethodTag,
} from './shared'
import TaskInterventionBar from './TaskInterventionBar'
import type { ReadableField, ReviewPayload } from './types'

const { Text } = Typography

function SummaryBlock({
  title,
  fields,
  emptyText,
}: {
  title: string
  fields: ReadableField[]
  emptyText: string
}) {
  return (
    <Card size="small" title={title}>
      {fields.length > 0 ? (
        <Descriptions column={1} size="small" bordered className="readable-descriptions">
          {fields.map((field) => (
            <Descriptions.Item key={`${title}-${field.label}`} label={field.label}>
              {field.value}
            </Descriptions.Item>
          ))}
        </Descriptions>
      ) : (
        <Alert type="info" showIcon message={emptyText} />
      )}
    </Card>
  )
}

type NodeTraceDrawerProps = {
  open: boolean
  selectedNode: TaskNodeInfo | null
  actionLoading: boolean
  onClose: () => void
  onSelectNode: (nodeId: number) => void
  onPauseNode: (nodeName: string) => void
  onResumeNode: (nodeName: string) => void
  onSkipNode: (nodeName: string) => void
  onTerminateNode: (nodeName: string) => void
  onRerunNode: (nodeName: string) => void
  onOpenConfigEditor: (node: TaskNodeInfo) => void
  readableConfigFields: ReadableField[]
  readableInputFields: ReadableField[]
  readableOutputFields: ReadableField[]
  selectedSearchProgress: SearchProgressInfo | null
  selectedSearchExecutionTrace: SearchExecutionTraceInfo | null
  selectedSearchExecutionPlan: SearchExecutionPlanInfo | null
  selectedSearchProgressSnapshots: SearchProgressInfo[]
  selectedSourceCandidates: SourceCandidateInfo[]
  sourceCandidateStageSummary: Array<{ stage: string; count: number }>
  sourceCandidateGroups: Array<{ stage: string; candidates: SourceCandidateInfo[] }>
  selectedTargets: SelectedTargetInfo[]
  selectedReviewPayload: ReviewPayload | null
  selectedNodeDependencies: TaskNodeInfo[]
  selectedNodeEvidenceIds: string[]
  streamStatus: 'idle' | 'connecting' | 'open' | 'fallback' | 'closed'
  fallbackPollingActive: boolean
  lastEventAt?: string | null
}

export default function NodeTraceDrawer({
  open,
  selectedNode,
  actionLoading,
  onClose,
  onSelectNode,
  onPauseNode,
  onResumeNode,
  onSkipNode,
  onTerminateNode,
  onRerunNode,
  onOpenConfigEditor,
  readableConfigFields,
  readableInputFields,
  readableOutputFields,
  selectedSearchProgress,
  selectedSearchExecutionTrace,
  selectedSearchExecutionPlan,
  selectedSearchProgressSnapshots,
  selectedSourceCandidates,
  sourceCandidateStageSummary,
  sourceCandidateGroups,
  selectedTargets,
  selectedReviewPayload,
  selectedNodeDependencies,
  selectedNodeEvidenceIds,
  streamStatus,
  fallbackPollingActive,
  lastEventAt,
}: NodeTraceDrawerProps) {
  const affectedNodeNames = Array.isArray(selectedNode?.affectedNodeNames) ? selectedNode.affectedNodeNames : []
  const dependencyNames = getDependencyNames(selectedNode?.dependsOn)
  const dependencySummary = dependencyNames.length > 0 ? dependencyNames.join('、') : '无上游依赖'
  const impactSummary = affectedNodeNames.length > 0
    ? `${selectedNode?.affectedNodeCount || affectedNodeNames.length} 个节点：${affectedNodeNames.join('、')}`
    : '暂无影响范围信息'
  // 抽屉首屏只保留“用户为什么现在要关心这个节点”的摘要信息，
  // 避免再次把节点详情页做成原始字段与调试 JSON 的总览器。
  const nodeGoalSummary = selectedNode?.nodeNotes
    || selectedNode?.configSummaryData?.summaryText
    || selectedNode?.configSummary
    || '暂无这一步的目标说明'
  // 高级诊断摘要根据是否存在 trace / 候选审计 / 评审诊断来切换提示文案，
  // 让非实现者在展开前就知道里面是“技术排查层”而不是主路径内容。
  const advancedDiagnosticSummary = selectedSearchExecutionTrace
    || selectedSearchExecutionPlan
    || selectedSearchProgressSnapshots.length > 0
    || selectedSourceCandidates.length > 0
    || selectedTargets.length > 0
    || selectedNodeDependencies.length > 0
    || selectedNodeEvidenceIds.length > 0
    || selectedReviewPayload
    ? '这里收纳原始记录、搜索轨迹、补源审计、评审结论和上游证据，默认关闭，仅在排查技术细节时展开。'
    : '当前暂无额外搜索或评审诊断，只保留原始记录入口，默认仍保持关闭。'

  return (
    <Drawer title={selectedNode ? `${getNodeDisplayName(selectedNode)}追踪` : '节点追踪'} open={open} onClose={onClose} width={820}>
      {selectedNode && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type={fallbackPollingActive ? 'warning' : streamStatus === 'open' ? 'success' : 'info'}
            showIcon
            message={getTaskEventStreamStatusText(streamStatus, fallbackPollingActive)}
            description={lastEventAt ? `最近一次节点相关事件：${lastEventAt}` : '当前抽屉会跟随任务事件流持续刷新。'}
          />

          {(selectedNode.canPause ||
            selectedNode.canResumeNode ||
            selectedNode.canSkip ||
            selectedNode.canTerminate ||
            selectedNode.canRerun ||
            selectedNode.canUpdateConfigAndRerun) && (
            <TaskInterventionBar
              title="节点干预操作"
              description="这里的动作只影响当前节点及其受影响下游，系统会自动保留可复用的检查点。"
              tone={
                selectedNode.status === 'FAILED'
                  ? 'error'
                  : selectedNode.status === 'PAUSED' || selectedNode.controlState === 'TERMINATE_REQUESTED'
                    ? 'warning'
                    : 'info'
              }
              badgeText={selectedNode.status}
              actions={[
                ...(selectedNode.canPause
                  ? [{
                      key: 'pause',
                      label: '暂停节点',
                      loading: actionLoading,
                      onClick: () => onPauseNode(selectedNode.nodeName),
                    }]
                  : []),
                ...(selectedNode.canResumeNode
                  ? [{
                      key: 'resume',
                      label: '恢复节点',
                      type: 'primary' as const,
                      loading: actionLoading,
                      onClick: () => onResumeNode(selectedNode.nodeName),
                    }]
                  : []),
                ...(selectedNode.canSkip
                  ? [{
                      key: 'skip',
                      label: '手动跳过',
                      loading: actionLoading,
                      onClick: () => onSkipNode(selectedNode.nodeName),
                    }]
                  : []),
                ...(selectedNode.canTerminate
                  ? [{
                      key: 'terminate',
                      label: selectedNode.status === 'RUNNING' ? '请求终止' : '强制终止',
                      danger: true,
                      loading: actionLoading,
                      onClick: () => onTerminateNode(selectedNode.nodeName),
                    }]
                  : []),
                ...(selectedNode.canRerun
                  ? [{
                      key: 'rerun',
                      label: '从该节点重跑',
                      type: 'primary' as const,
                      loading: actionLoading,
                      onClick: () => onRerunNode(selectedNode.nodeName),
                    }]
                  : []),
                ...(selectedNode.canUpdateConfigAndRerun
                  ? [{
                      key: 'config-rerun',
                      label: '应用建议 / 高级修改',
                      onClick: () => onOpenConfigEditor(selectedNode),
                    }]
                  : []),
              ]}
            />
          )}

          {selectedNode.interventionSummary && (
            <Alert
              type={selectedNode.controlState === 'TERMINATE_REQUESTED' ? 'warning' : getNodeNoticeType(selectedNode.status)}
              showIcon
              message="节点级人工干预说明"
              description={selectedNode.interventionSummary}
            />
          )}

          <Card size="small" title="节点摘要">
            <Descriptions column={1} bordered size="small" className="readable-descriptions">
              <Descriptions.Item label="现在怎么了">{getNodeSituationSummary(selectedNode)}</Descriptions.Item>
              <Descriptions.Item label="为什么需要处理">{getNodeHandlingReason(selectedNode)}</Descriptions.Item>
              <Descriptions.Item label="可执行动作">{getNodeActionSummary(selectedNode)}</Descriptions.Item>
              <Descriptions.Item label="这一步的目标">{nodeGoalSummary}</Descriptions.Item>
              <Descriptions.Item label="节点定位">
                {`${getNodeNameLabel(selectedNode.nodeName)} / ${getAgentTypeText(selectedNode.agentType)}`}
              </Descriptions.Item>
              <Descriptions.Item label="依赖背景">{dependencySummary}</Descriptions.Item>
              <Descriptions.Item label="受影响范围">{impactSummary}</Descriptions.Item>
              <Descriptions.Item label="控制状态">
                {selectedNode.controlState === 'TERMINATE_REQUESTED' ? '已请求终止' : '无'}
              </Descriptions.Item>
              <Descriptions.Item label="检查点复用">
                {selectedNode.canReuseCheckpoint ? '可复用已有检查点' : '暂无可复用检查点'}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <SummaryBlock title="配置摘要" fields={readableConfigFields} emptyText="暂无可读的配置摘要" />
          <SummaryBlock title="输入摘要" fields={readableInputFields} emptyText="暂无可读的输入摘要" />
          <SummaryBlock title="输出摘要" fields={readableOutputFields} emptyText="暂无可读的输出摘要" />

          {selectedSearchProgress && (
            <Card size="small" title="搜索进度快照">
              <Descriptions column={1} size="small" bordered className="readable-descriptions">
                <Descriptions.Item label="当前状态">
                  <Space wrap>
                    {progressStatusTag(selectedSearchProgress.status)}
                    {selectedSearchProgress.degraded && <Tag color="orange">已触发降级链路</Tag>}
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="当前步骤">{displayValue(selectedSearchProgress.currentStep)}</Descriptions.Item>
                <Descriptions.Item label="完成进度">
                  {`${selectedSearchProgress.completedSteps ?? 0}/${selectedSearchProgress.totalSteps ?? 0}（${
                    selectedSearchProgress.progressPercent ?? 0
                  }%）`}
                </Descriptions.Item>
                <Descriptions.Item label="状态说明">{displayValue(selectedSearchProgress.message)}</Descriptions.Item>
                <Descriptions.Item label="降级原因">{displayValue(selectedSearchProgress.degradationReason)}</Descriptions.Item>
                <Descriptions.Item label="更新时间">
                  {selectedSearchProgress.updatedAt ? dayjs(selectedSearchProgress.updatedAt).format('YYYY-MM-DD HH:mm:ss') : '未提供'}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          )}

          <AdvancedDiagnosticSection summary={advancedDiagnosticSummary}>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {selectedSearchExecutionTrace && (
                <Card size="small" title="搜索执行轨迹">
                  {selectedSearchExecutionTrace.resumedFromCheckpoint && (
                    <Alert
                      type="success"
                      showIcon
                      style={{ marginBottom: 12 }}
                      message="本次采集已基于历史检查点恢复"
                      description={`检查点来源：${selectedSearchExecutionTrace.checkpointSource || 'NODE_CONFIG_CHECKPOINT'}，恢复建议：${
                        selectedSearchExecutionTrace.recoveryAdvice || '可继续从当前检查点排查'
                      }`}
                    />
                  )}
                  <Descriptions column={1} size="small" bordered className="readable-descriptions">
                    <Descriptions.Item label="补源方式">
                      <Space wrap>
                        {supplementMethodTag(selectedSearchExecutionTrace.supplementMethod)}
                        {selectedSearchExecutionTrace.providerFallbackUsed && <Tag color="gold">已触发回退链路</Tag>}
                        {selectedSearchExecutionTrace.circuitBroken && <Tag color="orange">已触发超时熔断</Tag>}
                      </Space>
                    </Descriptions.Item>
                    <Descriptions.Item label="搜索模式">{searchModeText(selectedSearchExecutionTrace.searchMode)}</Descriptions.Item>
                    <Descriptions.Item label="浏览器搜索引擎">
                      {displayValue(selectedSearchExecutionTrace.browserSearchEngine)}
                    </Descriptions.Item>
                    <Descriptions.Item label="执行摘要">{displayValue(selectedSearchExecutionTrace.browserSearchSummary)}</Descriptions.Item>
                    <Descriptions.Item label="候选统计">
                      {`规划 ${selectedSearchExecutionTrace.plannedCandidateCount ?? 0} 条 / 验证通过 ${
                        selectedSearchExecutionTrace.verifiedCandidateCount ?? 0
                      } 条 / 运行期补源 ${selectedSearchExecutionTrace.supplementedCandidateCount ?? 0} 条 / 最终选中 ${
                        selectedSearchExecutionTrace.selectedCandidateCount ?? 0
                      } 条`}
                    </Descriptions.Item>
                    <Descriptions.Item label="浏览器执行 Query">
                      {displayValue(selectedSearchExecutionTrace.browserExecutedQueries)}
                    </Descriptions.Item>
                    <Descriptions.Item label="回退顺序">{displayValue(selectedSearchExecutionTrace.fallbackOrder)}</Descriptions.Item>
                    <Descriptions.Item label="浏览器轨迹编号">
                      {displayValue(selectedSearchExecutionTrace.browserTraceId)}
                    </Descriptions.Item>
                    <Descriptions.Item label="回退决策">{displayValue(selectedSearchExecutionTrace.fallbackDecision)}</Descriptions.Item>
                    <Descriptions.Item label="搜索预算">
                      {selectedSearchExecutionTrace.searchTimeoutMillis != null
                        ? `${selectedSearchExecutionTrace.searchTimeoutMillis} ms`
                        : '未配置'}
                    </Descriptions.Item>
                    <Descriptions.Item label="搜索耗时">
                      {selectedSearchExecutionTrace.searchElapsedMillis != null
                        ? `${selectedSearchExecutionTrace.searchElapsedMillis} ms`
                        : '未提供'}
                    </Descriptions.Item>
                    <Descriptions.Item label="降级原因">{displayValue(selectedSearchExecutionTrace.degradationReason)}</Descriptions.Item>
                    <Descriptions.Item label="阻断信号">{displayValue(selectedSearchExecutionTrace.browserBlockedReason)}</Descriptions.Item>
                    <Descriptions.Item label="阻断次数">{displayValue(selectedSearchExecutionTrace.browserBlockedCount)}</Descriptions.Item>
                    <Descriptions.Item label="恢复检查点">{displayValue(selectedSearchExecutionTrace.recoveryCheckpoint)}</Descriptions.Item>
                    <Descriptions.Item label="检查点恢复">
                      <Space wrap>
                        {selectedSearchExecutionTrace.resumedFromCheckpoint ? (
                          <Tag color="green">本次执行已复用检查点</Tag>
                        ) : (
                          <Tag>本次执行未使用检查点</Tag>
                        )}
                        {selectedSearchExecutionTrace.checkpointSource && (
                          <Tag color="blue">{selectedSearchExecutionTrace.checkpointSource}</Tag>
                        )}
                      </Space>
                    </Descriptions.Item>
                    <Descriptions.Item label="恢复建议">{displayValue(selectedSearchExecutionTrace.recoveryAdvice)}</Descriptions.Item>
                    <Descriptions.Item label="轨迹版本">{displayValue(selectedSearchExecutionTrace.traceVersion)}</Descriptions.Item>
                  </Descriptions>
                </Card>
              )}

              {selectedSearchExecutionTrace?.runtimePolicy && (
                <Card size="small" title="搜索运行策略">
                  <Descriptions column={1} size="small" bordered className="readable-descriptions">
                    <Descriptions.Item label="启用结果页验证">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.verifyResultPage)}
                    </Descriptions.Item>
                    <Descriptions.Item label="最大重试次数">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.maxRetries)}
                    </Descriptions.Item>
                    <Descriptions.Item label="最小搜索间隔">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.minIntervalMillis)}
                    </Descriptions.Item>
                    <Descriptions.Item label="单任务最大 Query 数">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.maxSearchesPerTask)}
                    </Descriptions.Item>
                    <Descriptions.Item label="页面超时">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.pageTimeoutMillis)}
                    </Descriptions.Item>
                    <Descriptions.Item label="结果页打开上限">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.maxOpenResultPages)}
                    </Descriptions.Item>
                    <Descriptions.Item label="阻断信号列表">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.blockedSignals)}
                    </Descriptions.Item>
                    <Descriptions.Item label="默认恢复提示">
                      {displayValue(selectedSearchExecutionTrace.runtimePolicy.recoveryHint)}
                    </Descriptions.Item>
                  </Descriptions>
                </Card>
              )}

              {selectedSearchExecutionPlan && selectedSearchExecutionPlan.steps.length > 0 && (
                <Card size="small" title="搜索执行计划">
                  <List
                    size="small"
                    bordered
                    dataSource={selectedSearchExecutionPlan.steps}
                    renderItem={(step) => (
                      <List.Item>
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space wrap>
                            {stepStatusTag(step.status)}
                            <Text strong>{step.goal || step.stepCode}</Text>
                            <Tag>{step.stepCode}</Tag>
                            {step.dependency && <Tag color="default">依赖 {step.dependency}</Tag>}
                            {formatDurationMs(step.expectedDurationMs) && (
                              <Tag color="blue">预计 {formatDurationMs(step.expectedDurationMs)}</Tag>
                            )}
                          </Space>
                          {step.message && <Text>{step.message}</Text>}
                          {(step.startedAt || step.completedAt) && (
                            <Text type="secondary">
                              {step.startedAt ? `开始：${dayjs(step.startedAt).format('YYYY-MM-DD HH:mm:ss')}` : '未开始'}
                              {step.completedAt ? ` / 完成：${dayjs(step.completedAt).format('YYYY-MM-DD HH:mm:ss')}` : ''}
                            </Text>
                          )}
                        </Space>
                      </List.Item>
                    )}
                  />
                </Card>
              )}

              {selectedSearchProgressSnapshots.length > 0 && (
                <Card size="small" title="搜索进度历史">
                  <List
                    size="small"
                    bordered
                    dataSource={selectedSearchProgressSnapshots}
                    renderItem={(snapshot, index) => (
                      <List.Item key={`${snapshot.currentStepCode || 'snapshot'}-${index}`}>
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space wrap>
                            {progressStatusTag(snapshot.status)}
                            <Text strong>{snapshot.currentStep || snapshot.currentStepCode || '未知步骤'}</Text>
                            {snapshot.currentStepCode && <Tag>{snapshot.currentStepCode}</Tag>}
                            {snapshot.progressPercent != null && <Tag color="blue">{`${snapshot.progressPercent}%`}</Tag>}
                            {snapshot.degraded && <Tag color="orange">降级</Tag>}
                          </Space>
                          {snapshot.message && <Text>{snapshot.message}</Text>}
                          <Text type="secondary">
                            {`${snapshot.completedSteps ?? 0}/${snapshot.totalSteps ?? 0} 步已完成`}
                            {snapshot.updatedAt ? ` / ${dayjs(snapshot.updatedAt).format('YYYY-MM-DD HH:mm:ss')}` : ''}
                          </Text>
                        </Space>
                      </List.Item>
                    )}
                  />
                </Card>
              )}

              <Card size="small" title="原始配置记录">
                <DebugJson value={selectedNode.nodeConfig} emptyText="暂无配置记录" />
              </Card>
              <Card size="small" title="原始输入记录">
                <DebugJson value={selectedNode.inputData || selectedNode.inputSummary} emptyText="暂无输入记录" />
              </Card>
              <Card size="small" title="原始输出记录">
                <DebugJson value={selectedNode.outputData || selectedNode.outputSummary} emptyText="暂无输出记录" />
              </Card>

              {selectedSourceCandidates.length > 0 && (
                <Card size="small" title="补源候选审计">
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <Card size="small" type="inner" title="候选阶段分布">
                      <Space wrap>
                        {sourceCandidateStageSummary.map((item) => (
                          <Tag color={candidateStageColor(item.stage)} key={item.stage}>
                            {`${stageLabel(item.stage)} ${item.count} 条`}
                          </Tag>
                        ))}
                      </Space>
                    </Card>
                    {sourceCandidateGroups.map((group) => (
                      <Card
                        key={group.stage}
                        size="small"
                        type="inner"
                        title={
                          <Space wrap>
                            <Tag color={candidateStageColor(group.stage)}>{stageLabel(group.stage)}</Tag>
                            <Text type="secondary">{`${group.candidates.length} 条`}</Text>
                          </Space>
                        }
                      >
                        <List
                          size="small"
                          bordered
                          dataSource={group.candidates}
                          renderItem={(candidate) => (
                            <List.Item>
                              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                                <Space wrap>
                                  <Tag color="cyan">{String(candidate.sourceType || '未知类型')}</Tag>
                                  {discoveryMethodTag(candidate.discoveryMethod)}
                                  {candidate.totalScore != null && <Tag color="green">总分 {formatScore(candidate.totalScore)}</Tag>}
                                  {isPresent(candidate.trustTierLabel) && <Tag color="purple">{String(candidate.trustTierLabel)}</Tag>}
                                  {candidate.verified === true && <Tag color="green">验证通过</Tag>}
                                  {candidate.verified === false && <Tag color="red">验证未通过</Tag>}
                                  {isPresent(candidate.selectionStage) && (
                                    <Tag color={candidateStageColor(candidate.selectionStage)}>阶段 {String(candidate.selectionStage)}</Tag>
                                  )}
                                </Space>
                                <Text strong>{String(candidate.title || candidate.url || '未命名来源')}</Text>
                                <Text type="secondary">{String(candidate.url || '')}</Text>
                                <Space size={[4, 4]} wrap>
                                  {isPresent(candidate.reason) && <Text type="secondary">{`说明：${String(candidate.reason)}`}</Text>}
                                  {isPresent(candidate.rankingSummary) && (
                                    <Text type="secondary">{`排序摘要：${String(candidate.rankingSummary)}`}</Text>
                                  )}
                                  {isPresent(candidate.selectionReason) && (
                                    <Text type="secondary">{`选源理由：${String(candidate.selectionReason)}`}</Text>
                                  )}
                                  {isPresent(candidate.selectionSummary) && (
                                    <Text type="secondary">{`选中摘要：${String(candidate.selectionSummary)}`}</Text>
                                  )}
                                  {isPresent(candidate.verificationReason) && (
                                    <Text type="secondary">{`验证结论：${String(candidate.verificationReason)}`}</Text>
                                  )}
                                  {isPresent(candidate.domain) && <Text type="secondary">{`域名：${String(candidate.domain)}`}</Text>}
                                  {isPresent(candidate.publishedAt) && (
                                    <Text type="secondary">{`发布时间：${String(candidate.publishedAt)}`}</Text>
                                  )}
                                  {isPresent(candidate.searchQuery) && (
                                    <Text type="secondary">{`Query：${String(candidate.searchQuery)}`}</Text>
                                  )}
                                  {isPresent(candidate.browserTraceId) && (
                                    <Text type="secondary">{`浏览器轨迹：${String(candidate.browserTraceId)}`}</Text>
                                  )}
                                  {isPresent(candidate.resultRank) && (
                                    <Text type="secondary">{`排名：${String(candidate.resultRank)}`}</Text>
                                  )}
                                  {Array.isArray(candidate.matchedSignals) && candidate.matchedSignals.length > 0 && (
                                    <Text type="secondary">{`命中信号：${candidate.matchedSignals.join('、')}`}</Text>
                                  )}
                                  {Array.isArray(candidate.rankingReasons) && candidate.rankingReasons.length > 0 && (
                                    <Text type="secondary">{`排序原因：${candidate.rankingReasons.join('；')}`}</Text>
                                  )}
                                </Space>
                              </Space>
                            </List.Item>
                          )}
                        />
                      </Card>
                    ))}
                  </Space>
                </Card>
              )}

              {selectedTargets.length > 0 && (
                <Card size="small" title="最终选源">
                  <List
                    size="small"
                    bordered
                    dataSource={selectedTargets}
                    renderItem={(item) => (
                      <List.Item>
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space wrap>
                            <Text strong>{item.title || item.url}</Text>
                            {item.verified === true && <Tag color="green">已验证</Tag>}
                            {item.verified === false && <Tag color="gold">兜底选中</Tag>}
                            {isPresent(item.trustTierLabel) && <Tag color="purple">{String(item.trustTierLabel)}</Tag>}
                            {item.totalScore != null && <Tag color="green">总分 {formatScore(item.totalScore)}</Tag>}
                            {isPresent(item.selectionStage) && <Tag color="blue">{String(item.selectionStage)}</Tag>}
                            {item.hasPrefetchedPage && <Tag color="purple">已预抓取页面</Tag>}
                            {isPresent(item.browserTraceId) && <Tag color="cyan">{`轨迹 ${String(item.browserTraceId)}`}</Tag>}
                          </Space>
                          <Text type="secondary">{item.url}</Text>
                          {item.selectionReason && <Text type="secondary">{item.selectionReason}</Text>}
                          {item.selectionSummary && <Text type="secondary">{`选中摘要：${item.selectionSummary}`}</Text>}
                          {item.rankingSummary && <Text type="secondary">{`排序摘要：${item.rankingSummary}`}</Text>}
                          {Array.isArray(item.rankingReasons) && item.rankingReasons.length > 0 && (
                            <Text type="secondary">{`排序原因：${item.rankingReasons.join('；')}`}</Text>
                          )}
                        </Space>
                      </List.Item>
                    )}
                  />
                </Card>
              )}

              {isReviewNode(selectedNode) && selectedReviewPayload && (
                <Card size="small" title="评审结论">
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <Space wrap>
                      {selectedReviewPayload.score != null && <Tag color="blue">评分 {selectedReviewPayload.score}/100</Tag>}
                      {selectedReviewPayload.passed != null && (
                        <Tag color={selectedReviewPayload.passed ? 'green' : 'orange'}>
                          {getReviewPassedText(selectedReviewPayload.passed)}
                        </Tag>
                      )}
                    </Space>

                    {selectedReviewPayload.summary && (
                      <Alert type="info" showIcon message="评审摘要" description={selectedReviewPayload.summary} />
                    )}

                    {selectedReviewPayload.passed === false && selectedReviewPayload.requiresHumanIntervention && (
                      <Alert
                        type="error"
                        showIcon
                        message="当前评审已判定为重度失败"
                        description="系统不会继续自动改写。建议先补证据、扩大搜索范围或重跑采集链路，再决定是否进入改写。"
                      />
                    )}

                    {selectedReviewPayload.passed === false &&
                      selectedReviewPayload.requiresHumanIntervention === false &&
                      selectedReviewPayload.autoRewriteAllowed && (
                        <Alert
                          type="warning"
                          showIcon
                          message="当前评审允许自动改写"
                          description="这类问题更适合先按修订计划收紧表述或补充章节，再触发 rewrite_report。"
                        />
                      )}

                    {selectedReviewPayload.issues.length > 0 &&
                      selectedReviewPayload.issues.some((item) => isEvidenceRiskIssue(item)) && (
                        <Alert
                          type={
                            selectedReviewPayload.issues.some(
                              (item) => item.severity === 'ERROR' && isEvidenceRiskIssue(item),
                            )
                              ? 'error'
                              : 'warning'
                          }
                          showIcon
                          message="缺证据风险提示"
                          description={`当前节点识别到 ${
                            selectedReviewPayload.issues.filter((item) => isEvidenceRiskIssue(item)).length
                          } 个缺证据章节/结论问题，请优先处理这类问题并补上 [证据：EID] 引用。`}
                        />
                      )}

                    {selectedReviewPayload.issues.length > 0 && (
                      <List
                        size="small"
                        bordered
                        header="问题清单"
                        dataSource={selectedReviewPayload.issues}
                        renderItem={(item) => (
                          <List.Item>
                            <Space direction="vertical" size={2}>
                              <Space wrap>
                                <Tag color={item.severity === 'ERROR' ? 'red' : item.severity === 'WARNING' ? 'orange' : 'blue'}>
                                  {getReviewSeverityText(item.severity)}
                                </Tag>
                                <Text>{getReviewSectionText(item.section)}</Text>
                                <Text type="secondary">{getReviewTypeText(item.type)}</Text>
                              </Space>
                              <Text>{item.suggestion || '暂无建议说明'}</Text>
                            </Space>
                          </List.Item>
                        )}
                      />
                    )}

                    {selectedReviewPayload.revisionPlan && (
                      <List
                        size="small"
                        bordered
                        header="修订计划"
                        dataSource={[
                          ...(selectedReviewPayload.revisionPlan.summary ? [selectedReviewPayload.revisionPlan.summary] : []),
                          ...((selectedReviewPayload.revisionPlan.rewriteGuidelines || []) as string[]),
                        ]}
                        renderItem={(item) => <List.Item>{item}</List.Item>}
                      />
                    )}

                    {selectedReviewPayload.passed === false && selectedReviewPayload.nextActions.length > 0 && (
                      <List
                        size="small"
                        bordered
                        header="下一步操作"
                        dataSource={selectedReviewPayload.nextActions}
                        renderItem={(item) => (
                          <List.Item>
                            <Space direction="vertical" size={2} style={{ width: '100%' }}>
                              <Space wrap>
                                <Tag color={actionPriorityColor(item.priority)}>{item.priority || 'MEDIUM'}</Tag>
                                <Tag color="geekblue">{actionTypeText(item.actionType)}</Tag>
                                {item.targetNode && <Tag>{getNodeNameLabel(item.targetNode)}</Tag>}
                                <Text strong>{item.title || '处理终审问题'}</Text>
                              </Space>
                              <Text>{item.description || '请根据终审问题清单补充证据或调整报告结论。'}</Text>
                            </Space>
                          </List.Item>
                        )}
                      />
                    )}
                  </Space>
                </Card>
              )}

              {selectedNodeDependencies.length > 0 && (
                <Card size="small" title="上游节点">
                  <List
                    size="small"
                    bordered
                    dataSource={selectedNodeDependencies}
                    renderItem={(item) => (
                      <List.Item
                        actions={[
                          <Button key={item.id} type="link" onClick={() => onSelectNode(item.id)}>
                            查看追踪
                          </Button>,
                        ]}
                      >
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space wrap>
                            <Text strong>{getNodeDisplayName(item)}</Text>
                            {statusTag(item.status)}
                          </Space>
                          <Text type="secondary">{item.outputSummary || item.errorMessage || '暂无输出'}</Text>
                        </Space>
                      </List.Item>
                    )}
                  />
                </Card>
              )}

              {selectedNodeEvidenceIds.length > 0 && (
                <Card size="small" title={isEvidenceNode(selectedNode) ? '已捕获证据' : '证据追踪'}>
                  <Space wrap>
                    {selectedNodeEvidenceIds.map((evidenceId) => (
                      <Tag color="green" key={evidenceId}>
                        {evidenceId}
                      </Tag>
                    ))}
                  </Space>
                </Card>
              )}
            </Space>
          </AdvancedDiagnosticSection>
        </Space>
      )}
    </Drawer>
  )
}
