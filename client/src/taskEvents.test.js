import test from 'node:test'
import assert from 'node:assert/strict'
import { isTerminalStatus } from './taskEventsPolicy.js'

test('SSE only retries recoverable HTTP statuses', () => {
  assert.equal(isTerminalStatus(400), true)
  assert.equal(isTerminalStatus(404), true)
  assert.equal(isTerminalStatus(408), false)
  assert.equal(isTerminalStatus(429), false)
  assert.equal(isTerminalStatus(500), false)
})
