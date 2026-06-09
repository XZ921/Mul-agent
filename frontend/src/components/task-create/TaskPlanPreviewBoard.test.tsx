import { render, screen } from '@testing-library/react'
import TaskPlanPreviewBoard from './TaskPlanPreviewBoard'
import type { TaskPlanPreviewInfo } from '../../types'

const preview: TaskPlanPreviewInfo = {
  competitorCount: 2,
  collectorCount: 4,
  pipelineCount: 3,
  stages: [
    {
      key: 'goal',
      title: '明确任务目标',
      summary: 'AI 知识库竞品分析',
      detail: '围绕企业级 RAG 平台展开竞品研究',
    },
    {
      key: 'source-strategy',
      title: '规划来源策略',
      summary: '按 2 个竞品拆分资料入口，优先覆盖 官网、产品文档',
      detail: '2 个竞品分支会在正式采集前做结果页验证',
    },
    {
      key: 'collect',
      title: '并行采集资料',
      summary: '4 个采集分支会并行收集官网、文档、定价或公开资料',
      detail: '其中 2 个竞品分支在入口不足时会自动补充网页检索',
    },
  ],
}

describe('TaskPlanPreviewBoard', () => {
  it('renders stage-oriented preview content when workflow data is ready', () => {
    render(<TaskPlanPreviewBoard plan={preview} hasReadyPreview />)

    expect(screen.getByText('系统会先规划来源，再并行采集，最后完成分析、报告与复核')).toBeInTheDocument()
    expect(screen.getByText('1. 明确任务目标')).toBeInTheDocument()
    expect(screen.getByText('AI 知识库竞品分析')).toBeInTheDocument()
    expect(screen.getByText('2. 规划来源策略')).toBeInTheDocument()
    expect(screen.queryByText(/搜索 Query/)).not.toBeInTheDocument()
    expect(screen.queryByText(/候选来源预览/)).not.toBeInTheDocument()
  })

  it('shows placeholder guidance before preview data is ready', () => {
    render(<TaskPlanPreviewBoard plan={preview} hasReadyPreview={false} />)

    expect(screen.getByText('这里会先展示执行计划')).toBeInTheDocument()
    expect(screen.getByText('系统会在任务启动前给出从来源规划、并行采集到分析、报告和复核的完整路径，帮助你先确认节奏再开始执行。')).toBeInTheDocument()
  })
})
