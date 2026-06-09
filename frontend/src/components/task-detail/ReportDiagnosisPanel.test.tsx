import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ReportDiagnosisPanel from './ReportDiagnosisPanel'
import type { ReportDiagnosisInfo } from '../../types'

const diagnosis: ReportDiagnosisInfo = {
  diagnosisCount: 2,
  blockerCount: 1,
  evidenceGapCount: 1,
  sourceUrls: ['https://docs.notion.so/ai'],
  nextActions: [
    {
      title: '从采集节点补证据',
      description: '先补证据，再决定是否重写',
      actionType: 'RERUN_NODE',
      targetNode: 'collect_sources_web',
      priority: 'HIGH',
    },
  ],
  sections: [
    {
      section: '结论',
      evidenceInsufficient: true,
      sourceUrls: ['https://docs.notion.so/ai'],
      repairSuggestions: ['补充证据编号并降低结论强度。'],
      diagnoses: [
        {
          reviewStage: 'INITIAL_REVIEW',
          diagnosis: {
            type: 'missing_evidence',
            section: '结论',
            severity: 'ERROR',
            level: 'BLOCKER',
            title: '关键结论缺少来源引用',
            detail: '结论章节中存在无法回指证据的判断。',
            evidenceBasis: '关键结论缺少可回指的证据编号。',
            evidenceIds: ['E-001'],
            sourceUrls: ['https://docs.notion.so/ai'],
            repairSuggestion: '补充证据编号并降低结论强度。',
          },
          evidenceReferences: [
            {
              evidenceId: 'E-001',
              title: 'Notion AI Docs',
              url: 'https://docs.notion.so/ai',
              competitorName: 'Notion AI',
              sourceType: 'DOCS',
              contentSnippet: 'docs snippet',
            },
          ],
        },
      ],
    },
  ],
  contentEvidences: [
    {
      stage: 'WRITE',
      competitorName: 'Notion AI',
      fieldName: 'report',
      evidenceId: 'E-001',
      sourceUrl: 'https://docs.notion.so/ai',
      title: 'Notion AI Docs',
      snippet: 'docs snippet',
      issueFlags: ['MISSING_BASIS'],
    },
  ],
}

describe('ReportDiagnosisPanel', () => {
  it('renders nested diagnosis, evidence references and actions from reportDiagnosis', async () => {
    const user = userEvent.setup()
    const onAction = vi.fn()

    render(
      <ReportDiagnosisPanel
        diagnosis={diagnosis}
        onAction={onAction}
        streamStatusText="实时进度通道已连接，后续状态会持续同步到页面"
        streamStatusTone="success"
      />,
    )

    expect(screen.getByText('章节诊断')).toBeInTheDocument()
    expect(screen.getByText('实时进度通道已连接，后续状态会持续同步到页面')).toBeInTheDocument()
    expect(screen.getByText('关键结论缺少来源引用')).toBeInTheDocument()
    expect(screen.getByText('关键结论缺少可回指的证据编号。')).toBeInTheDocument()
    expect(screen.getAllByText('E-001').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Notion AI Docs').length).toBeGreaterThan(0)
    expect(screen.getByText('补充证据编号并降低结论强度。')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '从采集节点补证据' }))
    expect(onAction).toHaveBeenCalledTimes(1)
    expect(onAction).toHaveBeenCalledWith(expect.objectContaining({ actionType: 'RERUN_NODE', targetNode: 'collect_sources_web' }))
  })

  it('does not crash when diagnosis arrays are malformed during realtime merge', () => {
    render(
      <ReportDiagnosisPanel
        diagnosis={{
          diagnosisCount: 1,
          blockerCount: 0,
          evidenceGapCount: 0,
          sourceUrls: null as unknown as string[],
          sections: null as unknown as ReportDiagnosisInfo['sections'],
          nextActions: { actionType: 'RERUN_NODE' } as unknown as ReportDiagnosisInfo['nextActions'],
          contentEvidences: { evidenceId: 'E-001' } as unknown as ReportDiagnosisInfo['contentEvidences'],
        }}
      />,
    )

    expect(screen.getByText('当前没有章节级诊断问题')).toBeInTheDocument()
    expect(screen.getAllByText('0').length).toBeGreaterThan(0)
  })

  it('renders revision directives as user-readable repair guidance', () => {
    render(
      <ReportDiagnosisPanel
        diagnosis={{
          ...diagnosis,
          revisionDirectives: [
            {
              category: 'SEARCH_QUALITY',
              actionType: 'SUPPLEMENT_EVIDENCE',
              priority: 'HIGH',
              targetNode: 'collect_sources_web',
              targetSection: '结论',
              summary: '补充官网安全能力证据',
              searchFeedback: '当前搜索结果缺少安全专题页面。',
              sourceUrls: ['https://docs.notion.so/ai'],
              expectedOutcome: '让报告结论能直接回指官网说明。',
            },
          ],
        } as unknown as ReportDiagnosisInfo}
      />,
    )

    expect(screen.getByText('修订路径建议')).toBeInTheDocument()
    expect(screen.getByText('补充官网安全能力证据')).toBeInTheDocument()
    expect(screen.getByText('让报告结论能直接回指官网说明。')).toBeInTheDocument()
    expect(screen.getByText('当前搜索结果缺少安全专题页面。')).toBeInTheDocument()
  })
})
