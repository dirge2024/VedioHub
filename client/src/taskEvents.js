import { apiRequest } from './api'

export function createTaskStreams() {
  const streams = new Map()
  const keyOf = (id, type, scope = '') => `${type}:${id}:${scope}`

  const stop = (id, type, scope = '') => {
    const key = keyOf(id, type, scope)
    streams.get(key)?.abort()
    streams.delete(key)
  }

  const stopAll = () => {
    for (const controller of streams.values()) controller.abort()
    streams.clear()
  }

  const stopMedia = id => {
    for (const [key, controller] of streams.entries()) {
      if (key.split(':', 2)[1] !== String(id)) continue
      controller.abort()
      streams.delete(key)
    }
  }

  const start = (id, type, scope, path, onEvent, onError) => {
    stop(id, type, scope)
    const key = keyOf(id, type, scope)
    const controller = new AbortController()
    streams.set(key, controller)
    let reconnectAttempt = 0

    const run = async () => {
      while (!controller.signal.aborted && streams.get(key) === controller) {
        try {
          const response = await apiRequest(path, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal
          })
          if (!response.ok || !response.body) throw new Error(await response.text())
          const terminal = await consumeStream(response.body, async event => {
            reconnectAttempt = 0
            await onEvent(event)
          }, controller.signal)
          if (terminal) {
            streams.delete(key)
            return
          }
        } catch (error) {
          if (controller.signal.aborted) return
          onError?.(error)
        }
        const delay = Math.min(15_000, 1_000 * 2 ** reconnectAttempt++)
        await waitForRetry(delay, controller.signal)
      }
    }

    run().catch(error => {
      if (!controller.signal.aborted) onError?.(error)
    })
  }

  return {
    has: (id, type, scope = '') => streams.has(keyOf(id, type, scope)),
    start,
    stop,
    stopMedia,
    stopAll
  }
}

function waitForRetry(delay, signal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise(resolve => {
    const timer = setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })

    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        const event = JSON.parse(data)
        await onEvent(event)
        if (event.state === 'COMPLETED' || event.state === 'FAILED') return true
      }
      if (done) return false
    }
    return false
  } finally {
    reader.releaseLock()
  }
}
