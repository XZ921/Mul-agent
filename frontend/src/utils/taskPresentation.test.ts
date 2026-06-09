import type { TaskInfo } from '../types'
import {
  buildTaskListOverview,
  formatDiagnosticJson,
  getRealtimeConnectionLabel,
  getTaskDetailEntryGuidance,
  safeParseStringArray,
} from './taskPresentation'

function buildTask(overrides: Partial<TaskInfo> = {}): TaskInfo {
  return {
    id: 1,
    taskName: '任务 A',
    subjectProduct: '本方产品',
    competitorNames: '["竞品 A"]',
    competitorUrls: '[]',
    analysisDimensions: '["功能"]',
    sourceScope: '["官网"]',
    status: 'PENDING',
    errorMessage: null,
    totalNodes: 10,
    completedNodes: 0,
    createdAt: '2026-06-04 10:00:00',
    updatedAt: '2026-06-04 10:00:00',
    completedAt: null,
    ...overrides,
  }
}

describe('taskPresentation', () => {
  it('safely parses string arrays from persisted json payloads', () => {
    expect(safeParseStringArray('["Notion AI", null, "", "飞书"]')).toEqual(['Notion AI', '飞书'])
    expect(safeParseStringArray('{"bad":"shape"}')).toEqual([])
    expect(safeParseStringArray('not-json')).toEqual([])
  })

  it('formats raw diagnostic data into readable json without crashing', () => {
    expect(formatDiagnosticJson('{"ok":true}')).toContain('"ok": true')
    expect(formatDiagnosticJson('{bad-json')).toBe('{bad-json')
    expect(formatDiagnosticJson(null)).toBe('')
  })

  it('translates realtime connection copy into user language', () => {
    expect(getRealtimeConnectionLabel('fallback', false)).toContain('自动刷新')
    expect(getRealtimeConnectionLabel('fallback', false)).not.toContain('SSE')
    expect(getRealtimeConnectionLabel('open', false)).toContain('实时进度通道')
  })

  it('builds task list overview copy without leaking technical terms', () => {
    const overview = buildTaskListOverview([
      buildTask({ status: 'FAILED', completedNodes: 4, errorMessage: '节点失败' }),
      buildTask({ id: 2, status: 'RUNNING', completedNodes: 6 }),
      buildTask({ id: 3, status: 'SUCCESS', completedNodes: 10 }),
    ])

    expect(overview.summary.total).toBe(3)
    expect(overview.summary.failed).toBe(1)
    expect(overview.heroDescription).toContain('阻塞原因')
    expect(overview.entryHint).toContain('实时进度')
    expect(overview.heroDescription).not.toContain('SSE')
    expect(overview.entryHint).not.toContain('SSE')
  })

  it('shares task detail entry guidance without exposing technical channel labels', () => {
    const listGuidance = getTaskDetailEntryGuidance('list')
    const reportGuidance = getTaskDetailEntryGuidance('report')

    expect(listGuidance.message).toContain('任务详情页')
    expect(reportGuidance.message).toContain('任务详情页')
    expect(reportGuidance.description).toContain('实时进度')
    expect(reportGuidance.description).not.toContain('SSE')
  })
})
