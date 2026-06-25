import {
  buildConversationEntryUrl,
  buildConversationMessageRequest,
  getConversationActionHint,
  getConversationEvidenceStateText,
  getConversationOrchestrationDecisionText,
  readConversationEntryContext,
} from './conversationPresentation'

describe('conversationPresentation', () => {
  it('encodes report context into a unified conversation entry url', () => {
    expect(
      buildConversationEntryUrl({
        pageType: 'REPORT',
        taskId: 42,
        reportId: 9,
        taskName: '企业级竞品分析',
        reportTitle: '企业级竞品分析报告',
      }),
    ).toBe(
      '/conversation?pageType=REPORT&taskId=42&reportId=9&taskName=%E4%BC%81%E4%B8%9A%E7%BA%A7%E7%AB%9E%E5%93%81%E5%88%86%E6%9E%90&reportTitle=%E4%BC%81%E4%B8%9A%E7%BA%A7%E7%AB%9E%E5%93%81%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A',
    )
  })

  it('reads task detail context and keeps the page title business-readable', () => {
    const context = readConversationEntryContext(
      new URLSearchParams('pageType=TASK_DETAIL&taskId=24&taskName=%E6%B5%8B%E8%AF%95%E4%BB%BB%E5%8A%A1'),
    )

    expect(context.pageType).toBe('TASK_DETAIL')
    expect(context.taskId).toBe(24)
    expect(context.taskName).toBe('测试任务')
    expect(context.title).toBe('围绕当前任务继续追问')
  })

  it('falls back to task draft context when page type is unknown', () => {
    const context = readConversationEntryContext(
      new URLSearchParams('pageType=UNKNOWN&taskName=%20%20AI%20%E8%8D%89%E7%A8%BF%20%20'),
    )

    expect(context.pageType).toBe('TASK_CREATE')
    expect(context.contextName).toBe('AI 草稿')
    expect(context.sampleQuestions.length).toBeGreaterThan(0)
  })

  it('builds the message request from the stable conversation context only', () => {
    const context = readConversationEntryContext(
      new URLSearchParams('pageType=REPORT&taskId=42&reportId=9&reportTitle=%E4%BC%81%E4%B8%9A%E7%BA%A7%E7%AB%9E%E5%93%81%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A'),
    )

    expect(buildConversationMessageRequest(context, 12, '这条结论的证据来自哪里？')).toEqual({
      sessionId: 12,
      taskId: 42,
      reportId: 9,
      pageType: 'REPORT',
      message: '这条结论的证据来自哪里？',
    })
  })
  it('keeps default action guidance inside the unified entry instead of sending users back to legacy action pages', () => {
    expect(
      getConversationActionHint({
        actionType: 'RERUN_NODE',
        taskId: 24,
        targetNodeName: 'rewrite_report',
        title: '从 rewrite_report 开始重跑',
        actionSummary: '系统会先展示影响范围，再等待确认。',
        impactSummary: '将影响当前节点和下游链路。',
        riskLevel: 'LOW',
        requiresConfirmation: false,
        executable: true,
        sourceUrls: [],
      }),
    ).toContain('统一入口')
  })

  it('describes orchestration evidence states and decision summaries', () => {
    expect(getConversationEvidenceStateText('MISSING_SOURCE')).toBe('缺少可回指来源')
    expect(
      getConversationOrchestrationDecisionText({
        decisionType: 'WAIT_FOR_HUMAN',
        actionType: 'MANUAL_REVIEW',
      }),
    ).toBe('等待人工介入')
  })
})
