import ReactMarkdown from 'react-markdown'
import { Tooltip } from 'antd'
import type { EvidenceInfo } from '../types'
import { normalizeReportContent } from '../utils/display'

interface Props {
  content: string
  evidences: EvidenceInfo[]
}

export default function MarkdownReport({ content, evidences }: Props) {
  const evidenceMap = new Map<string, EvidenceInfo>()
  const evidenceUrlMap = new Map<string, EvidenceInfo>()
  for (const evidence of evidences) {
    evidenceMap.set(evidence.evidenceId, evidence)
    evidenceUrlMap.set(evidence.url, evidence)
  }

  const normalizedContent = normalizeReportContent(content)

  /**
   * 把正文中的“证据占位符”翻译成业务用户可读的来源依据文案，
   * 避免首屏正文继续暴露只有实现者才熟悉的 evidenceId 标签。
   */
  const processedContent = normalizedContent.replace(
    /\[证据[:：]\s*([^\]]+)\]/g,
    (_match: string, id: string) => {
      const evidenceId = id.trim()
      const evidence = evidenceMap.get(evidenceId)
      if (!evidence) {
        return `来源依据：${evidenceId}`
      }
      const evidenceLabel = evidence.title?.trim() ? evidence.title : evidenceId
      return `[来源依据：${evidenceLabel}](${evidence.url} "${evidence.title}")`
    },
  )

  return (
    <div className="markdown-report">
      <ReactMarkdown
        components={{
          a: ({ href, children, ...props }) => {
            const evidence = href ? evidenceUrlMap.get(href) : undefined
            if (evidence) {
              return (
                <Tooltip
                  title={
                    <div className="evidence-tooltip">
                      <strong>{evidence.title}</strong>
                      <div>
                        [{evidence.evidenceId}] {evidence.competitorName}
                      </div>
                      {evidence.contentSnippet && <p>{evidence.contentSnippet.substring(0, 220)}</p>}
                      <span>点击打开原始来源</span>
                    </div>
                  }
                >
                  <a href={href} target="_blank" rel="noopener noreferrer" className="evidence-link" {...props}>
                    {children}
                  </a>
                </Tooltip>
              )
            }

            return (
              <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
                {children}
              </a>
            )
          },
        }}
      >
        {processedContent}
      </ReactMarkdown>
    </div>
  )
}
