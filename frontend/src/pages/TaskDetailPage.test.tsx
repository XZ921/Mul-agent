import { shouldFetchTaskReport } from './TaskDetailPage'
import type { TaskInfo, TaskNodeInfo } from '../types'

function buildTask(overrides: Partial<TaskInfo> = {}): TaskInfo {
  return {
    id: 24,
    taskName: '测试任务',
    subjectProduct: '本方产品',
    competitorNames: '["哔哩哔哩"]',
    competitorUrls: '[]',
    analysisDimensions: '["目标用户"]',
    sourceScope: '["官网"]',
    status: 'RUNNING',
    errorMessage: null,
    totalNodes: 7,
    completedNodes: 4,
    createdAt: '2026-06-03 13:00:00',
    updatedAt: '2026-06-03 13:00:00',
    completedAt: null,
    canExecute: false,
    canResume: false,
    canRetry: false,
    canStop: true,
    canViewReport: false,
    interventionSummary: null,
    ...overrides,
  }
}

function buildNode(overrides: Partial<TaskNodeInfo> = {}): TaskNodeInfo {
  return {
    id: 1,
    nodeName: 'extract_schema',
    displayName: '竞品结构化抽取',
    nodeConfig: '{}',
    configSummary: null,
    configSummaryData: null,
    collectorInsight: null,
    nodeNotes: null,
    allowFailedDependency: false,
    agentType: 'EXTRACTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 0,
    status: 'RUNNING',
    controlState: 'NONE',
    errorMessage: null,
    interventionReason: null,
    executionOrder: 5,
    inputSummary: null,
    outputSummary: null,
    inputData: null,
    outputData: null,
    startedAt: '2026-06-03 13:00:00',
    completedAt: null,
    canRerun: false,
    canUpdateConfigAndRerun: false,
    affectedNodeCount: 0,
    affectedNodeNames: [],
    canReuseCheckpoint: false,
    canPause: false,
    canResumeNode: false,
    canSkip: false,
    canTerminate: false,
    interventionSummary: null,
    ...overrides,
  }
}

describe('TaskDetailPage report gating', () => {
  it('does not fetch report while task is running before writer node succeeds', () => {
    const task = buildTask({ status: 'RUNNING', canViewReport: false })
    const nodes = [buildNode()]

    expect(shouldFetchTaskReport(task, nodes)).toBe(false)
  })

  it('fetches report after writer node succeeds even if task is not finished', () => {
    const task = buildTask({ status: 'RUNNING', canViewReport: false })
    const nodes = [
      buildNode({
        id: 2,
        nodeName: 'write_report',
        displayName: '生成分析报告',
        agentType: 'WRITER',
        status: 'SUCCESS',
        completedAt: '2026-06-03 13:10:00',
      }),
    ]

    expect(shouldFetchTaskReport(task, nodes)).toBe(true)
  })
})
