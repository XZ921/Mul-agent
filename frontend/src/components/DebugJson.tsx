import { Alert, Typography } from 'antd'
import { formatDiagnosticJson } from '../utils/taskPresentation'

const { Paragraph } = Typography

type DebugJsonProps = {
  value: string | object | null | undefined
  emptyText: string
}

export default function DebugJson({ value, emptyText }: DebugJsonProps) {
  const content = formatDiagnosticJson(value)

  if (!content) {
    return <Alert type="info" showIcon message={emptyText} />
  }

  return <Paragraph code className="code-block">{content}</Paragraph>
}
