import {
  buildSourceStrategyOverview,
  buildSourceStrategyOverviewFromPreview,
  buildTaskPlanPreview,
  buildTaskPlanPreviewFromContract,
  getCollectorNodeInsight,
} from './taskNodeInsights'
import type { CreateTaskRequest, TaskNodeInfo, TaskPlanPreviewContract } from '../types'

function buildCollectorNode(overrides: Partial<TaskNodeInfo> = {}): TaskNodeInfo {
  return {
    id: 26,
    nodeName: 'collect_sources_01_01',
    displayName: '采集官网',
    nodeConfig: '{}',
    configSummary: null,
    configSummaryData: {
      competitorName: 'Notion AI',
      sourceType: 'DOCS',
      sourceTypeLabel: '文档',
      sourceScope: ['官网'],
      competitorUrls: ['https://www.notion.so'],
      browserSearchEnabled: true,
      verificationEnabled: true,
      candidateCount: 0,
    },
    collectorInsight: null,
    nodeNotes: null,
    allowFailedDependency: false,
    agentType: 'COLLECTOR',
    dependsOn: '[]',
    required: true,
    retryable: true,
    maxRetries: 3,
    retryCount: 0,
    status: 'RUNNING',
    controlState: 'NONE',
    errorMessage: null,
    interventionReason: null,
    executionOrder: 1,
    inputSummary: null,
    outputSummary: null,
    inputData: null,
    outputData: null,
    startedAt: '2026-06-03T20:00:00',
    completedAt: null,
    ...overrides,
  }
}

function buildPipelineNode(overrides: Partial<TaskNodeInfo>): TaskNodeInfo {
  return {
    ...buildCollectorNode({
      id: 40,
      nodeName: 'extract_schema',
      displayName: '结构化提取',
      agentType: 'EXTRACTOR',
      configSummaryData: {
        summaryText: '将按产品功能、目标用户进行结构化抽取',
        dimensions: ['产品功能', '目标用户'],
      },
      collectorInsight: null,
      dependsOn: '["collect_sources_01_01"]',
      executionOrder: 2,
    }),
    ...overrides,
  }
}

function buildPreviewContract(overrides: Partial<TaskPlanPreviewContract> = {}): TaskPlanPreviewContract {
  return {
    contractType: 'TASK_PLAN_PREVIEW_V1',
    goal: '围绕企业级 RAG 平台开展竞品研究',
    competitorCount: 1,
    collectorCount: 1,
    pipelineCount: 4,
    lanes: [],
    stages: [
      {
        key: 'goal',
        stageCode: 'GOAL',
        title: '明确任务目标',
        summary: 'AI 知识库竞品分析',
        detail: '围绕企业级 RAG 平台开展竞品研究',
        sourceUrls: [],
      },
      {
        key: 'source-strategy',
        stageCode: 'SOURCE_STRATEGY',
        title: '规划来源策略',
        summary: '优先覆盖官网、产品文档',
        detail: '按 fallback 顺序补充公网搜索',
        sourceUrls: [],
      },
    ],
    nodes: [
      {
        nodeName: 'collect_sources_01_01',
        displayName: 'Notion AI - DOCS采集',
        agentType: 'COLLECTOR',
        stageCode: 'SOURCE_STRATEGY',
        goal: '优先覆盖 DOCS 来源，并在必要时补充公网搜索',
        summary: 'Notion AI 计划优先覆盖 官网、产品文档',
        configSummaryData: {
          competitorName: 'Notion AI',
          sourceType: 'DOCS',
          sourceTypeLabel: '产品文档',
          sourceScope: ['官网', '产品文档'],
          competitorUrls: ['https://www.notion.so/product/ai'],
          candidateCount: 4,
          queryCount: 2,
          browserSearchEnabled: true,
          verificationEnabled: true,
          minVerifiedCandidates: 1,
          preferredDomains: ['notion.so'],
          discoveryNotes: '优先官网，不足时再补充公网搜索',
        },
        dependsOn: [],
        required: true,
        executionOrder: 0,
        fallbackOrder: ['PLANNED', 'BROWSER', 'HEURISTIC', 'HTTP'],
        sourceUrls: [],
      },
    ],
    sourceUrls: [],
    ...overrides,
  }
}

describe('getCollectorNodeInsight', () => {
  it('filters malformed source candidates and selected targets before rendering', () => {
    const node = buildCollectorNode({
      collectorInsight: {
        competitorName: 'Notion AI',
        sourceType: 'DOCS',
        sourceTypeLabel: '文档',
        sourceScope: ['官网'],
        competitorUrls: ['https://www.notion.so'],
        searchMode: 'HYBRID',
        searchModeLabel: '混合模式',
        searchQueries: ['Notion AI docs'],
        browserSearchEnabled: true,
        verifyResultPage: true,
        minVerifiedCandidates: 1,
        preferredDomains: ['notion.so'],
        candidateCount: 3,
        selectedCount: 2,
        successCollected: 0,
        totalCollected: 0,
        discoveryNotes: '测试脏数据',
        searchProgress: null,
        searchExecutionPlan: {
          stage: 'COLLECT',
          steps: { stepCode: 'BROKEN' } as unknown as never[],
        },
        searchExecutionTrace: null,
        searchProgressSnapshots: [null, { currentStep: '验证候选来源', status: 'RUNNING' }] as unknown as never[],
        sourceCandidates: [
          null,
          'broken',
          {
            url: 'https://www.notion.so/product/ai',
            title: 'Notion AI',
            matchedSignals: [null, 'docs'],
            rankingReasons: ['官方域名', null],
          },
        ] as unknown as never[],
        selectedTargets: [
          null,
          {
            url: 'https://www.notion.so/product/ai',
            title: 'Notion AI',
            rankingReasons: ['已验证', null],
          },
        ] as unknown as never[],
      },
    })

    const insight = getCollectorNodeInsight(node)

    expect(insight.searchExecutionPlan?.steps).toEqual([])
    expect(insight.searchProgressSnapshots).toEqual([{ currentStep: '验证候选来源', status: 'RUNNING' }])
    expect(insight.sourceCandidates).toHaveLength(1)
    expect(insight.sourceCandidates[0].matchedSignals).toEqual(['docs'])
    expect(insight.sourceCandidates[0].rankingReasons).toEqual(['官方域名'])
    expect(insight.selectedTargets).toHaveLength(1)
    expect(insight.selectedTargets[0].rankingReasons).toEqual(['已验证'])
  })
  it('falls back to formal searchAudit when legacy runtime fields are absent', () => {
    const node = buildCollectorNode({
      collectorInsight: {
        competitorName: 'Notion AI',
        sourceType: 'DOCS',
        sourceScope: ['瀹樼綉'],
        competitorUrls: ['https://www.notion.so'],
        searchQueries: [],
        browserSearchEnabled: true,
        verifyResultPage: true,
        minVerifiedCandidates: 1,
        preferredDomains: ['notion.so'],
        candidateCount: 0,
        selectedCount: 0,
        successCollected: 0,
        totalCollected: 0,
        searchProgress: null,
        searchExecutionPlan: null,
        searchExecutionTrace: null,
        searchAudit: {
          executionTrace: {
            recoveryCheckpoint: 'SELECT_TARGETS',
          },
          latestProgress: {
            status: 'RUNNING',
            currentStep: 'SELECT_TARGETS',
          },
          executionPlan: {
            stage: 'COLLECT',
            steps: [{ stepCode: 'SELECT_TARGETS', goal: 'select', expectedDurationMs: 1000, dependency: 'VERIFY' }],
          },
          sourceCandidates: [
            {
              url: 'https://www.notion.so/product/ai',
              title: 'Notion AI',
            },
          ],
          selectedTargets: [
            {
              candidate: {
                url: 'https://www.notion.so/product/ai',
                title: 'Notion AI',
                selectionStage: 'SELECT_TARGETS',
              },
              collectedPage: {
                success: true,
              },
            },
          ],
          sourceUrls: ['https://www.notion.so/product/ai'],
        },
        searchProgressSnapshots: [],
        sourceCandidates: [],
        selectedTargets: [],
        sourceUrls: [],
      },
    })

    const insight = getCollectorNodeInsight(node)

    expect(insight.searchExecutionTrace?.recoveryCheckpoint).toBe('SELECT_TARGETS')
    expect(insight.searchProgress?.currentStep).toBe('SELECT_TARGETS')
    expect(insight.searchExecutionPlan?.steps).toHaveLength(1)
    expect(insight.sourceCandidates[0]?.url).toBe('https://www.notion.so/product/ai')
    expect(insight.selectedTargets[0]?.url).toBe('https://www.notion.so/product/ai')
    expect(insight.selectedTargets[0]?.hasPrefetchedPage).toBe(true)
    expect(insight.sourceUrls).toEqual(['https://www.notion.so/product/ai'])
  })
})

describe('buildSourceStrategyOverview', () => {
  it('groups collector nodes by competitor and returns business-readable coverage signals', () => {
    const overview = buildSourceStrategyOverview([
      buildCollectorNode({
        configSummaryData: {
          competitorName: 'Notion AI',
          sourceType: 'OFFICIAL',
          sourceTypeLabel: '官网',
          sourceScope: ['官网', '产品文档'],
          competitorUrls: ['https://www.notion.so/product/ai'],
          browserSearchEnabled: true,
          verificationEnabled: true,
          minVerifiedCandidates: 1,
          candidateCount: 4,
          queryCount: 2,
          preferredDomains: ['notion.so'],
          discoveryNotes: '先从官网入口开始。',
        },
      }),
      buildCollectorNode({
        id: 27,
        nodeName: 'collect_sources_01_02',
        displayName: '采集定价页',
        configSummaryData: {
          competitorName: 'Notion AI',
          sourceType: 'PRICING',
          sourceTypeLabel: '定价',
          sourceScope: ['定价页面'],
          competitorUrls: [],
          browserSearchEnabled: false,
          verificationEnabled: false,
          candidateCount: 2,
          queryCount: 1,
          preferredDomains: ['notion.so'],
          discoveryNotes: '补充定价资料。',
        },
      }),
    ])

    expect(overview.competitorCount).toBe(1)
    expect(overview.collectorCount).toBe(2)
    expect(overview.browserSupplementCount).toBe(1)
    expect(overview.verificationCount).toBe(1)
    expect(overview.lanes[0].sourceLabels).toEqual(['官网', '定价'])
    expect(overview.lanes[0].sourceScope).toEqual(['官网', '产品文档', '定价页面'])
    expect(overview.lanes[0].candidateCount).toBe(6)
    expect(overview.lanes[0].queryCount).toBe(3)
    expect(overview.lanes[0].notes).toEqual(['先从官网入口开始。', '补充定价资料。'])
  })
})

describe('buildSourceStrategyOverviewFromPreview', () => {
  it('falls back to preview nodes when backend lanes are not populated yet', () => {
    const overview = buildSourceStrategyOverviewFromPreview(buildPreviewContract())

    expect(overview.competitorCount).toBe(1)
    expect(overview.collectorCount).toBe(1)
    expect(overview.browserSupplementCount).toBe(1)
    expect(overview.verificationCount).toBe(1)
    expect(overview.lanes[0].sourceLabels).toEqual(['产品文档'])
    expect(overview.lanes[0].preferredDomains).toEqual(['notion.so'])
  })
})

describe('buildTaskPlanPreview', () => {
  it('builds stage-oriented preview text instead of exposing raw collector details', () => {
    const request: Pick<CreateTaskRequest, 'taskName' | 'subjectProduct'> = {
      taskName: 'AI 知识库竞品分析',
      subjectProduct: '企业级 RAG 平台',
    }

    const preview = buildTaskPlanPreview(request, [
      buildCollectorNode({
        configSummaryData: {
          competitorName: 'Notion AI',
          sourceType: 'OFFICIAL',
          sourceTypeLabel: '官网',
          sourceScope: ['官网', '产品文档'],
          competitorUrls: ['https://www.notion.so/product/ai'],
          browserSearchEnabled: true,
          verificationEnabled: true,
          minVerifiedCandidates: 1,
          candidateCount: 4,
          queryCount: 2,
        },
      }),
      buildPipelineNode({}),
      buildPipelineNode({
        id: 41,
        nodeName: 'analyze_competitors',
        displayName: '竞品分析',
        agentType: 'ANALYZER',
        configSummaryData: {
          summaryText: '汇总 1 个竞品，分析 2 个维度',
          competitorCount: 1,
          dimensionCount: 2,
        },
        executionOrder: 3,
      }),
      buildPipelineNode({
        id: 42,
        nodeName: 'write_report',
        displayName: '报告撰写',
        agentType: 'WRITER',
        configSummaryData: {
          summaryText: '输出中文 / 标准版报告',
          mode: 'initial',
          reportLanguage: '中文',
          reportTemplate: '标准版',
        },
        executionOrder: 4,
      }),
      buildPipelineNode({
        id: 43,
        nodeName: 'quality_review',
        displayName: '质量复核',
        agentType: 'REVIEWER',
        configSummaryData: {
          summaryText: '按标准质量评审复核 write_report',
          qualityPolicy: '标准质量评审',
          sourceNode: 'write_report',
        },
        executionOrder: 5,
      }),
    ])

    expect(preview.competitorCount).toBe(1)
    expect(preview.collectorCount).toBe(1)
    expect(preview.pipelineCount).toBe(4)
    expect(preview.stages.map((stage) => stage.title)).toEqual([
      '明确任务目标',
      '规划来源策略',
      '并行采集资料',
      '结构化提取',
      '汇总分析',
      '输出与复核',
    ])
    expect(preview.stages[0].detail).toContain('企业级 RAG 平台')
    expect(preview.stages[1].summary).toContain('官网、产品文档')
    expect(preview.stages[2].detail).toContain('网页搜索')
  })
})

describe('buildTaskPlanPreviewFromContract', () => {
  it('maps stage board directly from formal preview contract', () => {
    const preview = buildTaskPlanPreviewFromContract(buildPreviewContract())

    expect(preview.competitorCount).toBe(1)
    expect(preview.collectorCount).toBe(1)
    expect(preview.pipelineCount).toBe(4)
    expect(preview.stages.map((stage) => stage.title)).toEqual(['明确任务目标', '规划来源策略'])
    expect(preview.stages[1].detail).toContain('fallback')
  })
})
