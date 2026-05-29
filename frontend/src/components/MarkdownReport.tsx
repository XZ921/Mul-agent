import ReactMarkdown from 'react-markdown'
import { Tooltip } from 'antd'
import type { EvidenceInfo } from '../types'

interface Props {
  content: string
  evidences: EvidenceInfo[]
}

export default function MarkdownReport({ content, evidences }: Props) {
  const evidenceMap = new Map<string, EvidenceInfo>()
  for (const evidence of evidences) {
    evidenceMap.set(evidence.evidenceId, evidence)
  }

  const processedContent = content.replace(
    /\[证据:\s*([^\]]+)\]/g,
    (_match: string, id: string) => {
      const evidenceId = id.trim()
      const evidence = evidenceMap.get(evidenceId)
      return evidence ? `[证据: ${evidenceId}](${evidence.url} "${evidence.title}")` : `[证据: ${evidenceId}]`
    },
  )

  return (
    <div className="markdown-report">
      <ReactMarkdown
        components={{
          a: ({ href, children, ...props }) => {
            const evidence = href ? [...evidenceMap.values()].find((item) => item.url === href) : undefined
            if (evidence) {
              return (
                <Tooltip
                  title={
                    <div className="evidence-tooltip">
                      <strong>{evidence.title}</strong>
                      <div>[{evidence.evidenceId}] {evidence.competitorName}</div>
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
