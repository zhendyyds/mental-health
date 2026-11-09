import test from 'node:test'
import assert from 'node:assert/strict'
import { isAuthenticationFailure, toApiError, unwrapApiResponse } from '../src/utils/apiError.js'

test('only a string 200 business code unwraps the success data', () => {
  assert.deepEqual(unwrapApiResponse({ status: 200, data: { code: '200', data: { id: 1 } } }), { id: 1 })
  for (const payload of [{ code: '409', msg: '已有活跃回复' }, { code: '-1', msg: '业务失败' }, {}, { code: 200 }]) {
    assert.throws(() => unwrapApiResponse({ status: 200, data: payload }), { name: 'ApiError' })
  }
})

test('HTTP failures keep the server message, response and trace metadata', () => {
  const response = { status: 504, data: { code: '504', msg: '分析超时', data: { requestId: 'trace-1', saved: false } } }
  assert.throws(() => unwrapApiResponse(response), (error) => {
    assert.equal(error.message, '分析超时')
    assert.equal(error.requestId, 'trace-1')
    assert.equal(error.saved, false)
    assert.equal(error.response, response)
    return true
  })
})

test('only explicit authentication failures clear login; forbidden and legacy -1 do not', () => {
  for (const [status, code, expected] of [[401, '-1', true], [200, '401', true], [403, '403', false], [200, '-1', false], [429, '429', false], [502, '502', false]]) {
    assert.equal(isAuthenticationFailure(toApiError({ status, data: { code } })), expected)
  }
  assert.match(toApiError(undefined, new Error('raw upstream secret')).message, /网络连接失败/)
  assert.match(toApiError(undefined, { code: 'ECONNABORTED' }).message, /请求超时.*查询记录/)
})

test('a pending stop preserves its trace and stopping flag without asserting persistence', () => {
  const response = { status: 409, data: { code: '409', msg: '停止结果待确认', data: { requestId: 'stop-pending-1', stopping: true } } }
  assert.throws(() => unwrapApiResponse(response), (error) => {
    assert.equal(error.stopping, true)
    assert.equal(error.requestId, 'stop-pending-1')
    assert.equal(error.saved, undefined)
    assert.equal(isAuthenticationFailure(error), false)
    return true
  })
})

test('an unknown persistent request keeps its state and never clears login', () => {
  const error = toApiError({ status: 409, data: { code: '409', msg: '结果待确认', data: { requestId: 'unknown-1', status: 'UNKNOWN' } } })
  assert.equal(error.requestStatus, 'UNKNOWN')
  assert.equal(error.saved, undefined)
  assert.equal(isAuthenticationFailure(error), false)
})
