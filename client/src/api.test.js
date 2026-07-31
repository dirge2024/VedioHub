import assert from 'node:assert/strict'
import test from 'node:test'
import { apiRequest } from './api.js'

test('API network failures produce an actionable message', async () => {
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async () => {
    throw new TypeError('fetch failed')
  }

  await assert.rejects(apiRequest('/health'), /请确认后端已启动且地址配置正确/)
})
