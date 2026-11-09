import test from 'node:test'
import assert from 'node:assert/strict'
import { knowledgeQaErrorState } from '../src/utils/knowledgeQaState.js'

test('only uncertain or in-progress requests offer same-ID lookup', () => {
  for (const requestStatus of ['RUNNING', 'UNKNOWN']) {
    const state = knowledgeQaErrorState({ code: '409', requestStatus, requestId: 'qa-original' })
    assert.equal(state.canReplay, true)
    assert.equal(state.requestId, 'qa-original')
  }
  for (const requestStatus of ['CONFLICT', 'SOURCE_CHANGED', 'RESULT_UNAVAILABLE', 'FAILED']) {
    assert.equal(knowledgeQaErrorState({ code: '409', requestStatus }).canReplay, false)
  }
})

test('transport failures and server deadlines preserve uncertainty', () => {
  for (const error of [{ code: '504', requestStatus: 'UNKNOWN' }, { cause: { code: 'ECONNABORTED' } }, { cause: { code: 'ETIMEDOUT' } }]) {
    const state = knowledgeQaErrorState(error)
    assert.match(state.title, /超时.*待确认/)
    assert.equal(state.canReplay, true)
  }
  const network = knowledgeQaErrorState({ cause: { code: 'ERR_NETWORK' } })
  assert.match(network.title, /连接中断.*待确认/)
  assert.equal(network.canReplay, true)
})

test('rate limit and service outage stay separate from no-evidence responses', () => {
  const limited = knowledgeQaErrorState({ status: 429, response: { headers: { 'retry-after': '18' } } })
  assert.match(limited.title, /请求过于频繁/)
  assert.match(limited.detail, /18 秒/)
  assert.equal(limited.canReplay, false)
  assert.match(knowledgeQaErrorState({ code: '503', requestStatus: 'FAILED' }).title, /暂不可用/)
})
