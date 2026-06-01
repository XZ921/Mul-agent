import { Alert, Typography } from 'antd'

const { Paragraph } = Typography

function formatJsonValue(value: string | object | null | undefined) {
  if (value == null || value === '') return ''
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

type DebugJsonProps = {
  value: string | object | null | undefined
  emptyText: string
}

export default function DebugJson({ value, emptyText }: DebugJsonProps) {
  const content = formatJsonValue(value)

  if (!content) {
    return <Alert type="info" showIcon message={emptyText} />
  }

  return <Paragraph code className="code-block">{content}</Paragraph>
}
