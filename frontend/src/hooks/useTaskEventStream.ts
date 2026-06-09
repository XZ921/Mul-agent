import { useEffect, useRef, useState } from 'react'
import { getTaskEventStreamUrl } from '../api/client'
import { getRealtimeConnectionFallbackDetail } from '../utils/taskPresentation'
import type {
  TaskEventConnectionStatus,
  TaskEventType,
  TaskStreamEvent,
} from '../types'

const TASK_EVENT_TYPES: TaskEventType[] = [
  'CONNECTED',
  'TASK_SNAPSHOT',
  'TASK_STATUS',
  'NODE_STATUS',
  'SEARCH_PROGRESS',
  'AGENT_OUTPUT',
  'DIAGNOSIS',
]
const SSE_RECONNECT_DELAY_MS = 3000
const MAX_SSE_RECONNECT_DELAY_MS = 30000

export interface UseTaskEventStreamOptions {
  taskId: number
  eventStreamPath?: string | null
  enabled?: boolean
  onEvent?: (event: TaskStreamEvent<Record<string, unknown>>) => void
  onRecoverSnapshot?: () => Promise<void> | void
}

export interface TaskEventStreamRuntime {
  connectionStatus: TaskEventConnectionStatus
  fallbackPollingActive: boolean
  lastEventCursor: string | null
  lastEventAt: string | null
  lastError: string | null
}

/**
 * 任务 SSE 订阅 Hook。
 * 它只负责维护连接生命周期、解析事件与暴露兜底信号，真正的状态合并交给 reducer 统一处理。
 */
export function useTaskEventStream({
  taskId,
  eventStreamPath,
  enabled = true,
  onEvent,
  onRecoverSnapshot,
}: UseTaskEventStreamOptions): TaskEventStreamRuntime {
  const onEventRef = useRef(onEvent)
  const onRecoverSnapshotRef = useRef(onRecoverSnapshot)
  const sourceRef = useRef<EventSource | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)
  const reconnectAttemptRef = useRef(0)
  const connectionGenerationRef = useRef(0)
  const lastEventCursorRef = useRef<string | null>(null)
  const [connectionStatus, setConnectionStatus] = useState<TaskEventConnectionStatus>('idle')
  const [fallbackPollingActive, setFallbackPollingActive] = useState(false)
  const [lastEventCursor, setLastEventCursor] = useState<string | null>(null)
  const [lastEventAt, setLastEventAt] = useState<string | null>(null)
  const [lastError, setLastError] = useState<string | null>(null)

  useEffect(() => {
    onEventRef.current = onEvent
  }, [onEvent])

  useEffect(() => {
    onRecoverSnapshotRef.current = onRecoverSnapshot
  }, [onRecoverSnapshot])

  useEffect(() => {
    if (!enabled || !taskId) {
      if (reconnectTimerRef.current != null) {
        window.clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
      sourceRef.current?.close()
      sourceRef.current = null
      lastEventCursorRef.current = null
      setConnectionStatus('idle')
      setFallbackPollingActive(false)
      return
    }

    let active = true

    const clearReconnectTimer = () => {
      if (reconnectTimerRef.current != null) {
        window.clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
    }

    /**
     * 每次真正建立连接前，都会先把上一个失效连接完整关闭，避免重复 listener 与幽灵连接残留。
     */
    const closeSource = (source: EventSource | null) => {
      if (!source) return
      source.close()
      if (sourceRef.current === source) {
        sourceRef.current = null
      }
    }

    const connect = (resumeCursor?: string | null) => {
      if (!active) return
      clearReconnectTimer()
      closeSource(sourceRef.current)
      connectionGenerationRef.current += 1
      const currentGeneration = connectionGenerationRef.current

      const source = new EventSource(
        buildTaskEventStreamConnectionUrl(getTaskEventStreamUrl(taskId, eventStreamPath), resumeCursor),
      )
      sourceRef.current = source
      setConnectionStatus('connecting')
      setLastError(null)

      const handleStreamEvent = (messageEvent: MessageEvent<string>, eventType?: TaskEventType) => {
        const parsed = parseTaskStreamEvent(messageEvent, eventType)
        if (!parsed) return
        const resolvedCursor = parsed.cursor || messageEvent.lastEventId || null
        lastEventCursorRef.current = resolvedCursor
        setLastEventCursor(resolvedCursor)
        setLastEventAt(parsed.occurredAt || new Date().toISOString())
        onEventRef.current?.(parsed)
      }

      const listeners = TASK_EVENT_TYPES.map((currentEventType) => {
        const listener: EventListener = (event) => handleStreamEvent(event as MessageEvent<string>, currentEventType)
        source.addEventListener(currentEventType, listener)
        return { eventType: currentEventType, listener }
      })

      source.onopen = () => {
        if (!active || sourceRef.current !== source || connectionGenerationRef.current !== currentGeneration) {
          return
        }
        reconnectAttemptRef.current = 0
        setConnectionStatus('open')
        setFallbackPollingActive(false)
        setLastError(null)
      }

      source.onerror = () => {
        if (!active || sourceRef.current !== source || connectionGenerationRef.current !== currentGeneration) return
        listeners.forEach(({ eventType: currentEventType, listener }) =>
          source.removeEventListener(currentEventType, listener))
        closeSource(source)
        setConnectionStatus('fallback')
        setFallbackPollingActive(true)
        setLastError(getRealtimeConnectionFallbackDetail())
        if (reconnectTimerRef.current != null) {
          return
        }
        reconnectAttemptRef.current += 1
        const failedGeneration = currentGeneration
        const reconnectDelay = Math.min(
          SSE_RECONNECT_DELAY_MS * (2 ** (reconnectAttemptRef.current - 1)),
          MAX_SSE_RECONNECT_DELAY_MS,
        )
        reconnectTimerRef.current = window.setTimeout(() => {
          reconnectTimerRef.current = null
          if (!active || failedGeneration !== connectionGenerationRef.current) {
            return
          }
          const latestCursor = lastEventCursorRef.current
          const recoverSnapshot = onRecoverSnapshotRef.current
          if (!recoverSnapshot) {
            connect(latestCursor)
            return
          }
          Promise.resolve(recoverSnapshot())
            .catch(() => undefined)
            .finally(() => {
              if (!active) return
              connect(latestCursor)
            })
        }, reconnectDelay)
      }
    }

    connect()

    return () => {
      active = false
      clearReconnectTimer()
      closeSource(sourceRef.current)
      setConnectionStatus('closed')
    }
  }, [enabled, eventStreamPath, taskId])

  return {
    connectionStatus,
    fallbackPollingActive,
    lastEventCursor,
    lastEventAt,
    lastError,
  }
}

/**
 * 重连时需要把最后一次已消费游标拼回 SSE 地址，
 * 这样后端才能补齐断线期间遗漏的最小事件窗口。
 */
export function buildTaskEventStreamConnectionUrl(baseUrl: string, cursor?: string | null) {
  if (!cursor || !cursor.trim()) {
    return baseUrl
  }
  const normalizedCursor = cursor.trim()
  const separator = baseUrl.includes('?') ? '&' : '?'
  return `${baseUrl}${separator}cursor=${encodeURIComponent(normalizedCursor)}`
}

/**
 * 后端会把结构化 JSON 放进 SSE data 字段中。
 * 这里统一兜底解析 eventType 与 lastEventId，避免浏览器事件对象的兼容细节泄漏到页面层。
 */
export function parseTaskStreamEvent(
  messageEvent: MessageEvent<string>,
  eventType?: TaskEventType,
): TaskStreamEvent<Record<string, unknown>> | null {
  try {
    const parsed = JSON.parse(messageEvent.data) as TaskStreamEvent<Record<string, unknown>>
    return {
      ...parsed,
      eventType: parsed.eventType || eventType || 'CONNECTED',
      cursor: parsed.cursor || messageEvent.lastEventId || null,
    }
  } catch {
    return null
  }
}
