import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import * as client from '../api/client'
import type { ReportInfo } from '../types'
import { appMessage } from '../utils/appMessage'
import { getTaskDetailEntryGuidance } from '../utils/taskPresentation'
import ReportPage from './ReportPage'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    getReport: vi.fn(),
    listTaskExports: vi.fn(),
    listReportEvidences: vi.fn(),
    rerunTaskNode: vi.fn(),
  }
})

vi.mock('../utils/appMessage', () => ({
  appMessage: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}))

function buildApiResponse<T>(data: T) {
  return {
    code: 0,
    message: 'ok',
    data,
    timestamp: '2026-06-04T16:00:00',
    traceId: 'trace-report-page-test',
  }
}

function buildMessageHandle() {
  return undefined as never
}

function buildReport(overrides: Partial<ReportInfo> = {}): ReportInfo {
  return {
    id: 9,
    taskId: 42,
    title: '企业级竞品分析报告',
    content: '# 企业级竞品分析报告\n\n结论段落引用了 [证据：E-001]。',
    summary: '当前版本已形成结论，但仍需补充关键证据。',
    qualityScore: 72,
    qualityPassed: false,
    qualityIssues: [
      {
        type: 'missing_evidence',
        section: '结论',
        severity: 'ERROR',
        level: 'BLOCKER',
        dimensionCode: 'EVIDENCE_TRACEABILITY',
        dimensionName: '证据可追溯性',
        evidenceBasis: '关键结论缺少可回指的官网依据。',
        evidenceIds: ['E-001'],
        sourceUrls: ['https://docs.notion.so/security'],
        suggestion: '补充官网安全能力证据，再决定是否重写结论。',
      },
    ],
    initialReview: null,
    revisionPlan: {
      rewriteRequired: true,
      summary: '先补齐官网证据，再决定是否触发重写。',
      items: [
        {
          type: 'missing_evidence',
          section: '结论',
          severity: 'ERROR',
          suggestion: '补充官网安全能力证据，再决定是否重写结论。',
        },
      ],
      rewriteGuidelines: ['先补强证据，再重写结论段落。'],
    },
    rewriteApplied: false,
    finalReview: null,
    evidenceCount: 1,
    evidences: [
      {
        evidenceId: 'E-001',
        title: 'Notion 安全文档',
        url: 'https://docs.notion.so/security',
        contentSnippet: 'Notion 提供企业安全与权限说明。',
        competitorName: 'Notion AI',
        collectedAt: '2026-06-04 15:00:00',
        sourceType: 'DOCS',
        discoveryMethod: 'SEARCH',
        sourceDomain: 'docs.notion.so',
        discoveryReason: '官网文档命中安全主题',
        publishedAt: '2026-06-01',
        sourceScore: 0.92,
        verified: true,
        verificationReason: '标题和正文都明确提到企业安全能力',
        searchQuery: 'Notion AI security',
        searchEngine: 'bing',
        resultRank: 1,
        browserTraceId: 'trace-001',
        selectionReason: '与结论中的安全能力判断直接相关',
        selectionStage: 'SELECTED',
        matchedSignals: ['security'],
        pageMetadata: {
          discoveryMethod: 'SEARCH',
          searchQuery: 'Notion AI security',
          searchEngine: 'bing',
          resultRank: 1,
          sourceType: 'DOCS',
        },
      },
    ],
    searchAuditOverview: null,
    evidenceCoverageOverview: null,
    deliverySummary: {
      readyForDelivery: false,
      deliveryStatus: 'BLOCKED',
      summary: '当前报告暂不可正式交付，存在 1 个阻塞问题和 1 个证据缺口。',
      primaryIssue: '关键结论缺少来源引用',
      recommendedAction: '补充官网安全能力证据，再决定是否重写结论。',
      blockerCount: 1,
      evidenceGapCount: 1,
      sourceUrls: ['https://docs.notion.so/security'],
    },
    evidenceEntryPoint: {
      summary: '可优先核对证据：Notion 安全文档',
      sectionKey: 'report_conclusion',
      sectionTitle: '报告结论',
      evidenceId: 'E-001',
      title: 'Notion 安全文档',
      url: 'https://docs.notion.so/security',
      sourceType: 'DOCS',
      sourceUrls: ['https://docs.notion.so/security'],
    },
    auditSummary: {
      summary: '采集节点 1 个，已记录轨迹 1 个，最终选中候选 1 个；检索查询：Notion AI security',
      searchAuditSummary: '采集节点 1 个，已记录轨迹 1 个，最终选中候选 1 个',
      taskRagAuditSummary: '检索查询：Notion AI security',
      sourceUrls: ['https://docs.notion.so/security'],
    },
    competitorKnowledges: [],
    reportDiagnosis: {
      diagnosisCount: 1,
      blockerCount: 1,
      evidenceGapCount: 1,
      sourceUrls: ['https://docs.notion.so/security'],
      sections: [
        {
          section: '结论',
          evidenceInsufficient: true,
          sourceUrls: ['https://docs.notion.so/security'],
          repairSuggestions: ['补充官网安全能力证据，再决定是否重写结论。'],
          diagnoses: [
            {
              reviewStage: 'INITIAL_REVIEW',
              diagnosis: {
                type: 'missing_evidence',
                section: '结论',
                severity: 'ERROR',
                level: 'BLOCKER',
                title: '关键结论缺少来源引用',
                detail: '结论章节中存在无法回指官网证据的判断。',
                evidenceBasis: '关键结论缺少可回指的官网依据。',
                evidenceIds: ['E-001'],
                sourceUrls: ['https://docs.notion.so/security'],
                repairSuggestion: '补充官网安全能力证据，再决定是否重写结论。',
              },
              evidenceReferences: [
                {
                  evidenceId: 'E-001',
                  title: 'Notion 安全文档',
                  url: 'https://docs.notion.so/security',
                  competitorName: 'Notion AI',
                  sourceType: 'DOCS',
                  contentSnippet: 'Notion 提供企业安全与权限说明。',
                },
              ],
            },
          ],
        },
      ],
      nextActions: [
        {
          title: '前往任务页补充证据',
          description: '先回到任务页补齐官网安全能力来源，再决定是否重写。',
          actionType: 'SUPPLEMENT_EVIDENCE',
          targetNode: 'collect_sources_web',
          priority: 'HIGH',
        },
      ],
      revisionDirectives: [
        {
          category: 'SEARCH_QUALITY',
          actionType: 'SUPPLEMENT_EVIDENCE',
          priority: 'HIGH',
          targetNode: 'collect_sources_web',
          targetSection: '结论',
          summary: '补充官网安全能力证据',
          searchFeedback: '当前搜索结果缺少安全专题页面。',
          sourceUrls: ['https://docs.notion.so/security'],
          expectedOutcome: '让报告结论能直接回指官网说明。',
        },
      ],
    } as unknown as ReportInfo['reportDiagnosis'],
    sectionEvidenceBundles: [
      {
        stage: 'WRITE',
        sectionType: 'CONCLUSION',
        sectionKey: 'report_conclusion',
        sectionTitle: '报告结论',
        summary: '关键结论目前主要引用官网安全文档。',
        gapSummary: '仍需补齐 1 个产品能力证据后再触发终审。',
        hasGap: true,
        fieldNames: ['安全能力'],
        missingFields: ['安全能力'],
        sourceUrls: ['https://docs.notion.so/security'],
        issueFlags: ['SECTION_EVIDENCE_GAP'],
        fields: [
          {
            fieldName: 'security',
            fieldLabel: '安全能力',
            coverageStatus: 'MISSING_EVIDENCE',
            gapComment: '当前只有结论判断，缺少稳定官网说明。',
            evidenceId: 'E-001',
            sourceUrl: 'https://docs.notion.so/security',
            title: 'Notion 安全文档',
            snippet: 'Notion 提供企业安全与权限说明。',
            issueFlags: ['SECTION_EVIDENCE_GAP'],
            evidence: {
              evidenceId: 'E-001',
              title: 'Notion 安全文档',
              url: 'https://docs.notion.so/security',
              competitorName: 'Notion AI',
              sourceType: 'DOCS',
              contentSnippet: 'Notion 提供企业安全与权限说明。',
            },
          },
        ],
        evidenceReferences: [
          {
            evidenceId: 'E-001',
            title: 'Notion 安全文档',
            url: 'https://docs.notion.so/security',
            competitorName: 'Notion AI',
            sourceType: 'DOCS',
            contentSnippet: 'Notion 提供企业安全与权限说明。',
          },
        ],
      },
    ] as unknown as ReportInfo['sectionEvidenceBundles'],
    createdAt: '2026-06-04 15:00:00',
    updatedAt: '2026-06-04 15:10:00',
    ...overrides,
  } as unknown as ReportInfo
}

function buildExportRecords() {
  return [
    {
      id: 91,
      taskId: 42,
      exportVersion: 3,
      exportFormat: 'MARKDOWN',
      exportStatus: 'REGISTERED',
      exportSummary: '当前报告暂不可正式交付，存在 1 个阻塞问题和 1 个证据缺口。',
      sourceUrls: ['https://docs.notion.so/security'],
      createdAt: '2026-06-04T15:20:00',
    },
    {
      id: 92,
      taskId: 42,
      exportVersion: 2,
      exportFormat: 'HTML',
      exportStatus: 'REGISTERED',
      exportSummary: '网页正式版已保留审计摘要与证据入口。',
      sourceUrls: ['https://docs.notion.so/security'],
      createdAt: '2026-06-04T15:15:00',
    },
  ]
}

function ConversationRouteProbe() {
  const location = useLocation()
  const params = new URLSearchParams(location.search)

  return (
    <div>
      <span data-testid="conversation-page-type">{params.get('pageType') || ''}</span>
      <span data-testid="conversation-task-id">{params.get('taskId') || ''}</span>
      <span data-testid="conversation-task-name">{params.get('taskName') || ''}</span>
      <span data-testid="conversation-report-id">{params.get('reportId') || ''}</span>
      <span data-testid="conversation-report-title">{params.get('reportTitle') || ''}</span>
    </div>
  )
}

describe('ReportPage', () => {
  beforeEach(() => {
    vi.mocked(client.getReport).mockResolvedValue(buildApiResponse(buildReport()))
    ;(
      client as unknown as {
        listTaskExports: ReturnType<typeof vi.fn>
      }
    ).listTaskExports.mockResolvedValue(buildApiResponse(buildExportRecords()))
    vi.mocked(client.listReportEvidences).mockResolvedValue(buildApiResponse(buildReport().evidences))
    vi.mocked(appMessage.error).mockImplementation(buildMessageHandle)
    vi.mocked(appMessage.success).mockImplementation(buildMessageHandle)
    vi.mocked(appMessage.warning).mockImplementation(buildMessageHandle)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('surfaces delivery summary, audit summary, and formal export panel on the main report path', async () => {
    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect((await screen.findAllByText('企业级竞品分析报告')).length).toBeGreaterThan(0)
    const deliveryCard = screen.getByText('交付状态摘要').closest('.ant-card') as HTMLElement
    const auditCard = screen.getByText('审计摘要').closest('.ant-card') as HTMLElement
    const exportCard = screen.getByText('正式导出').closest('.ant-card') as HTMLElement

    expect(within(deliveryCard).getByText('当前报告暂不可正式交付，存在 1 个阻塞问题和 1 个证据缺口。')).toBeInTheDocument()
    expect(within(deliveryCard).getByText('关键结论缺少来源引用')).toBeInTheDocument()
    expect(within(deliveryCard).getByText('补充官网安全能力证据，再决定是否重写结论。')).toBeInTheDocument()
    expect(within(auditCard).getByText('采集节点 1 个，已记录轨迹 1 个，最终选中候选 1 个')).toBeInTheDocument()
    expect(within(auditCard).getByText('检索查询：Notion AI security')).toBeInTheDocument()
    expect(within(exportCard).getByText('MARKDOWN · v3')).toBeInTheDocument()
    expect(within(exportCard).getByText('HTML · v2')).toBeInTheDocument()
  })

  it('prioritizes delivery result, repair actions, and business-readable evidence tracing on the default path', async () => {
    const guidance = getTaskDetailEntryGuidance('report')

    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect((await screen.findAllByText('企业级竞品分析报告')).length).toBeGreaterThan(0)
    expect(screen.getByText('报告正文')).toBeInTheDocument()
    expect(screen.getByText('问题诊断看板')).toBeInTheDocument()
    expect(screen.getByText('修订计划与操作')).toBeInTheDocument()
    expect(screen.getByText('关键证据追溯')).toBeInTheDocument()
    expect(screen.getByText('报告结论')).toBeInTheDocument()
    expect(screen.getByText('关键结论目前主要引用官网安全文档。')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '来源依据：Notion 安全文档' })).toBeInTheDocument()
    expect(screen.getByText(guidance.message)).toBeInTheDocument()
    expect(screen.getByText(guidance.description)).toBeInTheDocument()
    expect(screen.queryByText(/SSE/i)).not.toBeInTheDocument()
    expect(screen.queryByText('sourceUrls')).not.toBeInTheDocument()
    expect(screen.queryByText('revisionDirectives')).not.toBeInTheDocument()
  })

  it('shows a phase boundary notice on the main report path instead of implying phase 6 autonomy is already delivered', async () => {
    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText(/MARKDOWN/)).toBeInTheDocument()
    expect(
      screen.getByText(/Phase 5 已正式交付组织知识、模型治理、回放恢复、正式导出与治理提示/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/当前工作台仍未进入 Phase 6 的 Planner Agent、Tool Router、Guardrail Engine 与自主循环/),
    ).toBeInTheDocument()
  })
  it('defers raw review checkpoints from the default report path', async () => {
    vi.mocked(client.getReport).mockResolvedValue(
      buildApiResponse(
        buildReport({
          // 这里显式补入初审/终审数据，验证默认主路径不会直接暴露原始评审结构。
          initialReview: {
            passed: false,
            score: 72,
            nodeName: 'quality_review',
            nodeStatus: 'FAILED',
            summary: '初审发现结论段落仍缺少关键来源依据。',
            requiresHumanIntervention: false,
            issues: [
              {
                severity: 'ERROR',
                section: '结论',
                type: 'missing_evidence',
                suggestion: '先补齐官网证据，再决定是否重写结论。',
              },
            ],
            nextActions: [],
          } as ReportInfo['initialReview'],
          finalReview: {
            passed: false,
            score: 68,
            nodeName: 'quality_gate',
            nodeStatus: 'FAILED',
            summary: '终审要求先完成证据补齐再进入正式交付。',
            requiresHumanIntervention: true,
            issues: [
              {
                severity: 'ERROR',
                section: '结论',
                type: 'manual_review',
                suggestion: '需要人工确认关键结论是否仍然成立。',
              },
            ],
            nextActions: [],
          } as ReportInfo['finalReview'],
        }),
      ),
    )

    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect((await screen.findAllByText('企业级竞品分析报告')).length).toBeGreaterThan(0)
    expect(screen.queryByText('初审结果')).not.toBeInTheDocument()
    expect(screen.queryByText('终审结果')).not.toBeInTheDocument()
  })

  it('keeps latest quality feedback inside advanced diagnostics instead of the main diagnosis card', async () => {
    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect((await screen.findAllByText('企业级竞品分析报告')).length).toBeGreaterThan(0)

    const diagnosisCard = screen.getByText('问题诊断看板').closest('.ant-card') as HTMLElement
    const advancedCard = screen.getByText('高级诊断与运行追踪').closest('.ant-card') as HTMLElement

    // 默认主路径应保留业务结论，不重复露出原始质量反馈卡片。
    expect(within(diagnosisCard).queryByText(/最新质量反馈/)).not.toBeInTheDocument()
    expect(within(advancedCard).getByText(/最新质量反馈/)).toBeInTheDocument()
  })

  it('uses business language inside the evidence drawer instead of raw technical labels', async () => {
    const user = userEvent.setup()

    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect((await screen.findAllByText('企业级竞品分析报告')).length).toBeGreaterThan(0)

    await user.click(screen.getAllByRole('button', { name: '查看证据' })[0])

    expect(await screen.findByText('检索线索：Notion AI security')).toBeInTheDocument()
    expect(screen.getByText('检索渠道：bing')).toBeInTheDocument()
    expect(screen.queryByText(/Query：/)).not.toBeInTheDocument()
  })

  it('opens the unified conversation entry with report reading context', async () => {
    const user = userEvent.setup()

    render(
      <MemoryRouter
        initialEntries={['/report/42']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/report/:id" element={<ReportPage />} />
          <Route path="/conversation" element={<ConversationRouteProbe />} />
        </Routes>
      </MemoryRouter>,
    )

    expect((await screen.findAllByText('企业级竞品分析报告')).length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '用对话完善草稿' }))

    expect(screen.getByTestId('conversation-page-type')).toHaveTextContent('REPORT')
    expect(screen.getByTestId('conversation-task-id')).toHaveTextContent('42')
    expect(screen.getByTestId('conversation-task-name')).toHaveTextContent('企业级竞品分析报告')
    expect(screen.getByTestId('conversation-report-id')).toHaveTextContent('9')
    expect(screen.getByTestId('conversation-report-title')).toHaveTextContent('企业级竞品分析报告')
  })
})
