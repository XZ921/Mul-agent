import { render, screen } from '@testing-library/react'
import SourceStrategySummary from './SourceStrategySummary'
import type { SourceStrategyOverviewInfo } from '../../types'

const overview: SourceStrategyOverviewInfo = {
  competitorCount: 1,
  collectorCount: 2,
  browserSupplementCount: 1,
  verificationCount: 1,
  lanes: [
    {
      competitorName: 'Notion AI',
      branchCount: 2,
      sourceLabels: ['官网', '定价'],
      sourceScope: ['官网', '产品文档', '定价页面'],
      entryUrlCount: 1,
      candidateCount: 6,
      queryCount: 3,
      browserSupplementEnabled: true,
      verificationEnabled: true,
      minVerifiedCandidates: 1,
      preferredDomains: ['notion.so'],
      notes: ['先从官网入口开始。'],
    },
  ],
}

describe('SourceStrategySummary', () => {
  it('renders business-readable source strategy without exposing raw query or candidate labels', () => {
    render(<SourceStrategySummary overview={overview} hasReadyPreview />)

    expect(screen.getByText('默认先讲资料覆盖与补充策略')).toBeInTheDocument()
    expect(screen.getByText('Notion AI')).toBeInTheDocument()
    expect(screen.getByText('优先覆盖 官网、产品文档、定价页面，共规划 2 条资料分支。')).toBeInTheDocument()
    expect(screen.getByText('会先从 1 个已知入口开始，必要时补充网页检索，正式采集前会验证至少 1 条结果页。')).toBeInTheDocument()
    expect(screen.queryByText(/搜索 Query/)).not.toBeInTheDocument()
    expect(screen.queryByText(/候选来源预览/)).not.toBeInTheDocument()
  })

  it('shows placeholder guidance before preview is ready', () => {
    render(<SourceStrategySummary overview={overview} hasReadyPreview={false} />)

    expect(screen.getByText('这里会先说明资料策略')).toBeInTheDocument()
    expect(screen.getByText('补全任务目标和竞品后，系统会在这里告诉你优先采集哪些资料、从哪里开始，以及何时补充网页检索。')).toBeInTheDocument()
  })
})
