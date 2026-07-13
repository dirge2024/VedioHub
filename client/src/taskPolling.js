export function createTaskPolling() {
  const jobs = new Map()
  const keyOf = (id, type) => `${type}:${id}`

  const stop = (id, type) => {
    const key = keyOf(id, type)
    const job = jobs.get(key)
    if (!job) return
    clearInterval(job.timer)
    clearTimeout(job.timeout)
    jobs.delete(key)
  }

  const stopAll = () => {
    for (const job of jobs.values()) {
      clearInterval(job.timer)
      clearTimeout(job.timeout)
    }
    jobs.clear()
  }

  const start = (id, type, poll, onTimeout) => {
    stop(id, type)
    const key = keyOf(id, type)
    const job = { timer: null, timeout: null, inFlight: false }

    const run = async () => {
      // 慢请求没回来就先等等，轮询不该越堆越多。
      if (job.inFlight || jobs.get(key) !== job) return
      job.inFlight = true
      try {
        await poll()
      } finally {
        job.inFlight = false
      }
    }

    job.timer = setInterval(run, 3000)
    job.timeout = setTimeout(() => {
      if (jobs.get(key) !== job) return
      stop(id, type)
      onTimeout()
    }, 300000)
    jobs.set(key, job)
    run()
  }

  return {
    has: (id, type) => jobs.has(keyOf(id, type)),
    start,
    stop,
    stopAll
  }
}
