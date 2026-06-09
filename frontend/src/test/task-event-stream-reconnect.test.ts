import { act, renderHook } from '@testing-library/react'
import { buildFixtureEvent } from './fixtures/taskEventStream'
import { buildTaskEventStreamConnectionUrl, useTaskEventStream } from '../hooks/useTaskEventStream'

class ReconnectAwareEventSource {
  static latestInstance: ReconnectAwareEventSource | null = null
  static instances: ReconnectAwareEventSource[] = []

  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  listeners = new Map<string, EventListener[]>()
  url: string

  constructor(url: string) {
    this.url = url
    ReconnectAwareEventSource.latestInstance = this
    ReconnectAwareEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: EventListener) {
    const next = this.listeners.get(type) || []
    next.push(listener)
    this.listeners.set(type, next)
  }

  removeEventListener(type: string, listener: EventListener) {
    this.listeners.set(
      type,
      (this.listeners.get(type) || []).filter((item) => item !== listener),
    )
  }

  close() {}

  emit(type: string, payload: string, lastEventId = '') {
    const event = new MessageEvent(type, { data: payload, lastEventId })
    ;(this.listeners.get(type) || []).forEach((listener) => listener(event))
  }
}

describe('task event stream reconnect baseline', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    ReconnectAwareEventSource.latestInstance = null
    ReconnectAwareEventSource.instances = []
    vi.stubGlobal('EventSource', ReconnectAwareEventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('appends cursor when rebuilding event stream url for reconnect', () => {
    expect(buildTaskEventStreamConnectionUrl('/api/task/24/events', null)).toBe('/api/task/24/events')
    expect(buildTaskEventStreamConnectionUrl('/api/task/24/events', '24-9')).toBe('/api/task/24/events?cursor=24-9')
    expect(buildTaskEventStreamConnectionUrl('/api/task/24/events?tenant=cn', '24-9')).toBe(
      '/api/task/24/events?tenant=cn&cursor=24-9',
    )
  })

  it('trims the cursor before rebuilding the reconnect url', () => {
    expect(buildTaskEventStreamConnectionUrl('/api/task/24/events', ' 24-9 \n')).toBe(
      '/api/task/24/events?cursor=24-9',
    )
  })

  it('recovers snapshot before reconnecting with the latest cursor', async () => {
    const onRecoverSnapshot = vi.fn(async () => undefined)

    renderHook(() =>
      useTaskEventStream({
        taskId: 24,
        eventStreamPath: '/api/task/24/events',
        onRecoverSnapshot,
      }),
    )

    act(() => {
      ReconnectAwareEventSource.latestInstance?.onopen?.()
      ReconnectAwareEventSource.latestInstance?.emit(
        'TASK_STATUS',
        JSON.stringify(
          buildFixtureEvent({
            cursor: '24-9',
            eventType: 'TASK_STATUS',
            payload: { status: 'RUNNING' },
          }),
        ),
        '24-9',
      )
      ReconnectAwareEventSource.latestInstance?.onerror?.()
    })

    await act(async () => {
      vi.advanceTimersByTime(3000)
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(onRecoverSnapshot).toHaveBeenCalledTimes(1)
    expect(ReconnectAwareEventSource.instances).toHaveLength(2)
    expect(ReconnectAwareEventSource.instances[1]?.url).toBe('/api/task/24/events?cursor=24-9')
  })
})
