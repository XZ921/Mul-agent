import type { MessageInstance } from 'antd/es/message/interface'

let messageInstance: MessageInstance | null = null

export function registerAppMessage(instance: MessageInstance) {
  messageInstance = instance
}

function createFallbackMessage(kind: 'success' | 'error' | 'info' | 'warning') {
  return (...args: Parameters<MessageInstance[typeof kind]>) => {
    const [content] = args
    const detail = typeof content === 'string'
      ? content
      : typeof content === 'object' && content && 'content' in content
        ? String(content.content)
        : ''

    if (kind === 'error') {
      console.error('[appMessage]', detail)
    } else if (kind === 'warning') {
      console.warn('[appMessage]', detail)
    } else {
      console.info('[appMessage]', detail)
    }
    return undefined as never
  }
}

const fallbackMessageInstance = {
  success: createFallbackMessage('success'),
  error: createFallbackMessage('error'),
  info: createFallbackMessage('info'),
  warning: createFallbackMessage('warning'),
} as unknown as MessageInstance

function getMessageInstance() {
  return messageInstance || fallbackMessageInstance
}

export const appMessage = {
  success: (...args: Parameters<MessageInstance['success']>) => getMessageInstance().success(...args),
  error: (...args: Parameters<MessageInstance['error']>) => getMessageInstance().error(...args),
  info: (...args: Parameters<MessageInstance['info']>) => getMessageInstance().info(...args),
  warning: (...args: Parameters<MessageInstance['warning']>) => getMessageInstance().warning(...args),
}
