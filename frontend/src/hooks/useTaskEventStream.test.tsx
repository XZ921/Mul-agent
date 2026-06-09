import { act, renderHook } from '@testing-library/react'
import { buildFixtureEvent } from '../test/fixtures/taskEventStream'
import { parseTaskStreamEvent, useTaskEventStream } from './useTaskEventStream'

class MockEventSource {
  static latestInstance: MockEventSource | null = null
  static instances: MockEventSource[] = []

  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  listeners = new Map<string, EventListener[]>()
  url: string
  closed = false

  constructor(url: string) {
    this.url = url
    MockEventSource.latestInstance = this
    MockEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: EventListener) {
    const next = this.listeners.get(type) || []
    next.push(listener)
    this.listeners.set(type, next)
  }

  removeEventListener(type: string, listener: EventListener) {
    const next = (this.listeners.get(type) || []).filter((item) => item !== listener)
    this.listeners.set(type, next)
  }

  close() {
    this.closed = true
  }

  emit(type: string, payload: string, lastEventId = '') {
    const event = new MessageEvent(type, { data: payload, lastEventId })
    ;(this.listeners.get(type) || []).forEach((listener) => listener(event))
  }
}

describe('useTaskEventStream', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    MockEventSource.latestInstance = null
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('subscribes task events and exposes open state after handshake', () => {
    const onEvent = vi.fn()
    const { result } = renderHook(() =>
      useTaskEventStream({
        taskId: 24,
        eventStreamPath: '/api/task/24/events',
        onEvent,
      }),
    )

    const source = MockEventSource.latestInstance
    expect(source?.url).toBe('/api/task/24/events')

    act(() => {
      source?.onopen?.()
      source?.emit(
        'TASK_SNAPSHOT',
        JSON.stringify(
          buildFixtureEvent({
            cursor: '24-2',
            eventType: 'TASK_SNAPSHOT',
          }),
        ),
        '24-2',
      )
    })

    expect(result.current.connectionStatus).toBe('open')
    expect(result.current.fallbackPollingActive).toBe(false)
    expect(result.current.lastEventCursor).toBe('24-2')
    expect(onEvent).toHaveBeenCalledTimes(1)
  })

  it('switches to fallback mode when sse channel reports error', () => {
    const { result } = renderHook(() =>
      useTaskEventStream({
        taskId: 24,
        eventStreamPath: '/api/task/24/events',
      }),
    )

    act(() => {
      MockEventSource.latestInstance?.onerror?.()
    })

    expect(result.current.connectionStatus).toBe('fallback')
    expect(result.current.fallbackPollingActive).toBe(true)
    expect(result.current.lastError).toContain('自动刷新')
    expect(result.current.lastError).not.toContain('SSE')
  })

  it('reconnects sse channel after transient error', () => {
    const { result } = renderHook(() =>
      useTaskEventStream({
        taskId: 24,
        eventStreamPath: '/api/task/24/events',
      }),
    )

    const firstSource = MockEventSource.latestInstance

    act(() => {
      firstSource?.onerror?.()
    })

    expect(result.current.connectionStatus).toBe('fallback')
    expect(result.current.fallbackPollingActive).toBe(true)
    expect(firstSource?.closed).toBe(true)

    act(() => {
      vi.advanceTimersByTime(3000)
    })

    expect(MockEventSource.instances).toHaveLength(2)
    expect(MockEventSource.latestInstance).not.toBe(firstSource)
    expect(result.current.connectionStatus).toBe('connecting')

    act(() => {
      MockEventSource.latestInstance?.onopen?.()
    })

    expect(result.current.connectionStatus).toBe('open')
    expect(result.current.fallbackPollingActive).toBe(false)
  })

  it('backs off reconnect delay when the channel keeps failing repeatedly', () => {
    renderHook(() =>
      useTaskEventStream({
        taskId: 24,
        eventStreamPath: '/api/task/24/events',
      }),
    )

    act(() => {
      MockEventSource.latestInstance?.onerror?.()
      vi.advanceTimersByTime(3000)
    })

    expect(MockEventSource.instances).toHaveLength(2)

    act(() => {
      MockEventSource.latestInstance?.onerror?.()
      vi.advanceTimersByTime(5999)
    })

    expect(MockEventSource.instances).toHaveLength(2)

    act(() => {
      vi.advanceTimersByTime(1)
    })

    expect(MockEventSource.instances).toHaveLength(3)
  })

  it('parses structured json event payload and falls back to lastEventId', () => {
    const parsed = parseTaskStreamEvent(
      new MessageEvent('TASK_STATUS', {
        data: JSON.stringify({
          taskId: 24,
          eventType: 'TASK_STATUS',
          payload: { status: 'RUNNING' },
        }),
        lastEventId: '24-8',
      }),
      'TASK_STATUS',
    )

    expect(parsed?.cursor).toBe('24-8')
    expect(parsed?.eventType).toBe('TASK_STATUS')
  })
})
