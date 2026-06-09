import stylesSource from '../../styles/index.css?raw'
import type { TaskInfo, TaskNodeInfo } from '../../types'
import { getTaskStageLabel, isTerminalNodeStatus, parseReviewPayload } from './shared'

function buildTask(overrides: Partial<TaskInfo> = {}): TaskInfo {
  return {
    id: 1,
    taskName: '测试任务',
    subjectProduct: '本方产品',
    competitorNames: '[]',
    competitorUrls: '[]',
    analysisDimensions: '[]',
    sourceScope: '[]',
    status: 'RUNNING',
    errorMessage: null,
    totalNodes: 6,
    completedNodes: 2,
    createdAt: '2026-06-04 10:00:00',
    updatedAt: '2026-06-04 10:00:00',
    completedAt: null,
    ...overrides,
  }
}

function buildNode(overrides: Partial<TaskNodeInfo> = {}): TaskNodeInfo {
  return {
    id: 2,
    nodeName: 'write_report',
    displayName: '生成分析报告',
    nodeConfig: '{}',
    agentType: 'WRITER',
    dependsOn: '[]',
    required: true,
    status: 'FAILED',
    errorMessage: '证据摘要不足',
    executionOrder: 4,
    inputSummary: null,
    outputSummary: null,
    startedAt: '2026-06-04 10:05:00',
    completedAt: null,
    ...overrides,
  } as TaskNodeInfo
}

describe('parseReviewPayload', () => {
  it('sanitizes malformed nested review payload fields from realtime events', () => {
    const payload = JSON.stringify({
      score: 82,
      passed: false,
      requiresHumanIntervention: false,
      autoRewriteAllowed: true,
      summary: '需要先补证据再继续改写',
      issues: [
        null,
        'bad-issue',
        {
          type: 'missing_evidence',
          section: 'conclusion',
          severity: 'ERROR',
          suggestion: '补充证据编号',
        },
      ],
      revisionPlan: {
        summary: '先修正关键结论',
        items: [
          null,
          {
            type: 'missing_evidence',
            section: 'conclusion',
            severity: 'ERROR',
            suggestion: '降低结论强度',
          },
        ],
        rewriteGuidelines: {
          shouldNot: 'crash',
        },
      },
      nextActions: [
        null,
        'bad-action',
        {
          title: '重跑采集节点',
          description: '先补证据再继续',
          actionType: 'RERUN_NODE',
          targetNode: 'collect_sources_web',
          priority: 'HIGH',
        },
      ],
    })

    expect(parseReviewPayload(payload)).toEqual({
      score: 82,
      passed: false,
      requiresHumanIntervention: false,
      autoRewriteAllowed: true,
      summary: '需要先补证据再继续改写',
      issues: [
        {
          type: 'missing_evidence',
          section: 'conclusion',
          severity: 'ERROR',
          suggestion: '补充证据编号',
        },
      ],
      revisionPlan: {
        rewriteRequired: undefined,
        summary: '先修正关键结论',
        items: [
          {
            type: 'missing_evidence',
            section: 'conclusion',
            severity: 'ERROR',
            suggestion: '降低结论强度',
          },
        ],
        rewriteGuidelines: [],
      },
      nextActions: [
        {
          title: '重跑采集节点',
          description: '先补证据再继续',
          actionType: 'RERUN_NODE',
          targetNode: 'collect_sources_web',
          priority: 'HIGH',
        },
      ],
    })
  })
})

describe('getTaskStageLabel', () => {
  it('prefers the blocked business stage when a downstream node fails during execution', () => {
    expect(getTaskStageLabel(buildTask(), [buildNode()])).toBe('报告生成受阻')
  })

  it('surfaces waiting-for-human-intervention before the generic running label', () => {
    expect(
      getTaskStageLabel(
        buildTask(),
        [
          buildNode({
            status: 'WAITING_INTERVENTION',
            errorMessage: null,
            interventionSummary: '等待人工确认补证策略',
          }),
        ],
      ),
    ).toBe('等待人工处理')
  })
})

describe('isTerminalNodeStatus', () => {
  it('treats compensated nodes as completed terminal states', () => {
    expect(isTerminalNodeStatus('COMPENSATED')).toBe(true)
  })
})

describe('phase 3 productization freeze', () => {
  it('keeps route-level styles on token or css-variable references instead of adding direct color literals', () => {
    const cssWithoutRootTokens = stylesSource.replace(/:root\s*\{[\s\S]*?\}\s*/u, '')
    const directColorMatches = cssWithoutRootTokens.match(/#[0-9A-Fa-f]{3,8}\b|rgba?\([^)]+\)|hsla?\([^)]+\)/gu)

    expect(directColorMatches).toBeNull()
  })
})
