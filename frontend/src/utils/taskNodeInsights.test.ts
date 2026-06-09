import { buildSourceStrategyOverview, buildTaskPlanPreview, getCollectorNodeInsight } from './taskNodeInsights'
import type { CreateTaskRequest, TaskNodeInfo } from '../types'

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
      displayName: '结构化提炼',
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
      '结构化提炼',
      '汇总分析',
      '输出与复核',
    ])
    expect(preview.stages[0].detail).toContain('企业级 RAG 平台')
    expect(preview.stages[1].summary).toContain('优先覆盖 官网、产品文档')
    expect(preview.stages[2].detail).toContain('补充网页检索')
  })
})
