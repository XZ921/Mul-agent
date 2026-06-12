import { buildFixtureDiagnosis, buildFixtureEvent, buildFixtureLog, buildFixtureNode, buildFixtureReport, buildFixtureTask } from '../test/fixtures/taskEventStream'
import { createInitialTaskEventRuntimeState, taskEventReducer } from './taskEventReducer'

describe('taskEventReducer', () => {
  it('hydrates snapshot data and merges search progress, logs and diagnosis events', () => {
    const initial = createInitialTaskEventRuntimeState()
    const hydrated = taskEventReducer(initial, {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [buildFixtureLog()],
      report: buildFixtureReport(),
    })

    const searchUpdated = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-2',
        eventType: 'SEARCH_PROGRESS',
        nodeName: 'collect_sources_01_01',
        payload: {
          nodeName: 'collect_sources_01_01',
          searchProgress: {
            status: 'RUNNING',
            currentStep: '验证候选来源',
            progressPercent: 55,
          },
        },
      }),
    })

    expect(searchUpdated.nodes[0].collectorInsight?.searchProgress?.currentStep).toBe('验证候选来源')

    const logUpdated = taskEventReducer(searchUpdated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-3',
        eventType: 'AGENT_OUTPUT',
        nodeName: 'collect_sources_01_01',
        payload: {
          agentType: 'COLLECTOR',
          agentName: 'CollectorAgent',
          status: 'SUCCESS',
          reasoningSummary: '已完成选源',
          createdAt: '2026-06-03T18:00:40',
          outputData:
            '{"competitor":"Notion AI","sourceType":"DOCS","selectedTargets":[{"url":"https://www.notion.so/product/ai","title":"Notion AI"}],"sourceCandidates":[{"url":"https://www.notion.so/product/ai","title":"Notion AI"}],"successCollected":1,"totalCollected":1}',
        },
      }),
    })

    expect(logUpdated.logs[0].agentName).toBe('CollectorAgent')
    expect(logUpdated.nodes[0].collectorInsight?.selectedCount).toBe(1)

    const diagnosisUpdated = taskEventReducer(logUpdated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-4',
        eventType: 'DIAGNOSIS',
        nodeName: 'quality_check',
        payload: {
          nodeName: 'quality_check',
          passed: false,
          score: 18,
          summary: '证据仍需补强',
          requiresHumanIntervention: true,
          diagnoses: buildFixtureDiagnosis().sections[0].diagnoses.map((item) => item.diagnosis),
          issues: [
            {
              type: 'missing_evidence',
              section: '结论',
              severity: 'ERROR',
              level: 'BLOCKER',
              sourceUrls: ['https://www.notion.so/product/ai'],
              suggestion: '补充证据编号并降低结论强度。',
            },
          ],
        },
      }),
    })

    expect(diagnosisUpdated.report?.reportDiagnosis?.blockerCount).toBe(1)
    expect(diagnosisUpdated.report?.reportDiagnosis?.sections[0].section).toBe('结论')
  })

  it('merges formal search audit fields from search progress events', () => {
    const hydrated = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [],
      report: null,
    })

    const updated = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-search-audit',
        eventType: 'SEARCH_PROGRESS',
        nodeName: 'collect_sources_01_01',
        payload: {
          contractType: 'SEARCH_PROGRESS_V1',
          nodeName: 'collect_sources_01_01',
          searchAudit: {
            executionTrace: {
              recoveryCheckpoint: 'SELECT_TARGETS',
            },
            sourceUrls: ['https://www.notion.so/product/ai'],
          },
          selectedTargets: [
            {
              url: 'https://www.notion.so/product/ai',
              title: 'Notion AI',
            },
          ],
          sourceUrls: ['https://www.notion.so/product/ai'],
        },
      }),
    })

    expect(updated.nodes[0].collectorInsight?.searchAudit?.executionTrace?.recoveryCheckpoint).toBe('SELECT_TARGETS')
    expect(updated.nodes[0].collectorInsight?.selectedTargets[0]?.url).toBe('https://www.notion.so/product/ai')
    expect(updated.nodes[0].collectorInsight?.selectedCount).toBe(1)
    expect(updated.nodes[0].collectorInsight?.sourceUrls).toEqual(['https://www.notion.so/product/ai'])
  })

  it('keeps reducer stable when existing report diagnosis contains malformed arrays', () => {
    const initial = createInitialTaskEventRuntimeState()
    const hydrated = taskEventReducer(initial, {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [buildFixtureLog()],
      report: {
        ...buildFixtureReport(),
        reportDiagnosis: {
          ...buildFixtureDiagnosis(),
          sourceUrls: null as unknown as string[],
          sections: { section: '坏数据' } as unknown as ReturnType<typeof buildFixtureDiagnosis>['sections'],
          nextActions: { actionType: 'RERUN_NODE' } as unknown as ReturnType<typeof buildFixtureDiagnosis>['nextActions'],
          contentEvidences: { evidenceId: 'E-001' } as unknown as ReturnType<typeof buildFixtureDiagnosis>['contentEvidences'],
        },
      },
    })

    const diagnosisUpdated = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-5',
        eventType: 'DIAGNOSIS',
        nodeName: 'quality_check',
        payload: {
          nodeName: 'quality_check',
          passed: false,
          score: 22,
          summary: '仍需补证据',
          requiresHumanIntervention: true,
          diagnoses: buildFixtureDiagnosis().sections[0].diagnoses.map((item) => item.diagnosis),
          issues: [
            {
              type: 'missing_evidence',
              section: '结论',
              severity: 'ERROR',
              level: 'BLOCKER',
              sourceUrls: ['https://www.notion.so/product/ai'],
              suggestion: '补充证据编号并降低结论强度。',
            },
          ],
        },
      }),
    })

    expect(diagnosisUpdated.report?.reportDiagnosis?.sections).toEqual(
      expect.arrayContaining([expect.objectContaining({ section: '结论' })]),
    )
    expect(Array.isArray(diagnosisUpdated.report?.reportDiagnosis?.contentEvidences)).toBe(true)
    expect(Array.isArray(diagnosisUpdated.report?.reportDiagnosis?.nextActions)).toBe(true)
  })

  it('keeps only the latest runtime logs to avoid unbounded growth during long tasks', () => {
    let state = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [],
      report: null,
    })

    for (let index = 1; index <= 205; index += 1) {
      const minute = Math.floor(index / 60)
      const second = index % 60
      const timestamp = `2026-06-03T18:${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`
      state = taskEventReducer(state, {
        type: 'apply-event',
        event: buildFixtureEvent({
          cursor: `24-${index}`,
          eventType: 'AGENT_OUTPUT',
          nodeName: 'collect_sources_01_01',
          payload: {
            agentType: 'COLLECTOR',
            agentName: 'CollectorAgent',
            status: 'RUNNING',
            reasoningSummary: `第 ${index} 条日志`,
            createdAt: timestamp,
          },
        }),
      })
    }

    expect(state.logs).toHaveLength(200)
    expect(state.logs[0]?.eventCursor).toBe('24-205')
    expect(state.logs.some((log) => log.eventCursor === '24-1')).toBe(false)
  })

  it('keeps reducer stable when incoming runtime log createdAt is not a string', () => {
    const hydrated = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [
        buildFixtureLog({
          id: 900,
          createdAt: '2026-06-03T18:00:20',
          eventCursor: '24-base',
        }),
      ],
      report: null,
    })

    const nextState = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-created-at-object',
        eventType: 'AGENT_OUTPUT',
        nodeName: 'collect_sources_01_01',
        payload: {
          agentType: 'COLLECTOR',
          agentName: 'CollectorAgent',
          status: 'RUNNING',
          reasoningSummary: 'createdAt 不是字符串',
          createdAt: { raw: '2026-06-03T18:00:21' } as unknown as string,
        },
      }),
    })

    expect(nextState.logs).toHaveLength(2)
    expect(typeof nextState.logs[0]?.createdAt).toBe('string')
    expect(nextState.logs.some((log) => log.eventCursor === '24-created-at-object')).toBe(true)
  })

  it('caps per-section diagnosis details when repeated diagnosis events keep arriving', () => {
    const oversizedDiagnoses = Array.from({ length: 60 }, (_, index) => ({
      ...buildFixtureDiagnosis().sections[0].diagnoses[0].diagnosis,
      title: `诊断 ${index + 1}`,
      detail: `第 ${index + 1} 条诊断说明`,
    }))

    const state = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-220',
        eventType: 'DIAGNOSIS',
        nodeName: 'quality_check',
        payload: {
          nodeName: 'quality_check',
          passed: false,
          score: 32,
          summary: '诊断持续回流',
          requiresHumanIntervention: true,
          diagnoses: oversizedDiagnoses,
          issues: [],
        },
      }),
    })

    const diagnoses = state.report?.reportDiagnosis?.sections[0]?.diagnoses || []
    expect(diagnoses.length).toBe(50)
    expect(diagnoses[diagnoses.length - 1]?.diagnosis.title).toBe('诊断 60')
  })

  it('keeps the original node array when search progress event targets an unknown node', () => {
    const hydrated = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [],
      report: null,
    })

    const nextState = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-301',
        eventType: 'SEARCH_PROGRESS',
        nodeName: 'missing_node',
        payload: {
          nodeName: 'missing_node',
          searchProgress: {
            status: 'RUNNING',
            currentStep: '不会命中任何节点',
            progressPercent: 10,
          },
        },
      }),
    })

    expect(nextState.nodes).toBe(hydrated.nodes)
  })

  it('keeps the original node reference when node status event brings no effective change', () => {
    const hydrated = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'hydrate',
      task: buildFixtureTask(),
      nodes: [buildFixtureNode()],
      logs: [],
      report: null,
    })

    const nextState = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-302',
        eventType: 'NODE_STATUS',
        nodeName: 'collect_sources_01_01',
        payload: {
          nodeName: 'collect_sources_01_01',
        },
      }),
    })

    expect(nextState.nodes).toBe(hydrated.nodes)
    expect(nextState.nodes[0]).toBe(hydrated.nodes[0])
  })

  it('counts compensated nodes as completed after runtime status updates', () => {
    const hydrated = taskEventReducer(createInitialTaskEventRuntimeState(), {
      type: 'hydrate',
      task: buildFixtureTask({ completedNodes: 0 }),
      nodes: [buildFixtureNode({ status: 'RUNNING', completedAt: null })],
      logs: [],
      report: null,
    })

    const nextState = taskEventReducer(hydrated, {
      type: 'apply-event',
      event: buildFixtureEvent({
        cursor: '24-303',
        eventType: 'NODE_STATUS',
        nodeName: 'collect_sources_01_01',
        payload: {
          nodeName: 'collect_sources_01_01',
          action: 'NODE_COMPLETED',
          status: 'COMPENSATED',
          completedAt: '2026-06-03T18:05:00',
        },
      }),
    })

    expect(nextState.task?.completedNodes).toBe(1)
    expect(nextState.nodes[0]?.status).toBe('COMPENSATED')
  })
})
