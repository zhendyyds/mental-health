import test from 'node:test'
import assert from 'node:assert/strict'
import { applyReplyEvent, applyReplyFailure, createClientRequestId, createReplyRecoveryBaseline, reconcileReplyHistory, restoreReplyHistory } from '../src/utils/replyStream.js'

const event = (name, payload) => ({ event: name, data: JSON.stringify(payload) })

test('explicit sends get secure distinct IDs, with a crypto fallback for HTTP deployments', () => {
  assert.notEqual(createClientRequestId(), createClientRequestId())
  assert.equal(createClientRequestId({ randomUUID: () => 'fixed-request-id' }), 'fixed-request-id')
  assert.equal(createClientRequestId({ getRandomValues: bytes => bytes.fill(0) }), '00000000-0000-4000-8000-000000000000')
  assert.throws(() => createClientRequestId({}), /安全请求 ID/)
})

test('request metadata persists through messages and saved completion', () => {
  const reply = { content: '' }
  applyReplyEvent(reply, event('message', { code: '200', data: { content: '原结果', requestId: 'persisted-1' } }))
  applyReplyEvent(reply, event('done', { requestId: 'persisted-1', status: 'SUCCEEDED', saved: true }))
  assert.equal(reply.requestId, 'persisted-1')
  assert.equal(reply.requestStatus, 'SUCCEEDED')
  assert.equal(reply.saved, true)
  assert.equal(reply.content, '原结果')
})

test('unknown result without persistence confirmation stays unconfirmed', () => {
  const reply = { content: '已显示内容' }
  applyReplyEvent(reply, event('error', { code: '409', msg: '请求结果待确认', data: { requestId: 'unknown-1', status: 'UNKNOWN', saved: null } }))
  assert.equal(reply.saved, undefined)
  assert.equal(reply.requestStatus, 'UNKNOWN')
  assert.equal(reply.content, '已显示内容')
  assert.match(reply.errorMessage, /刷新会话.*不要重复发送/)
})

test('unknown provider result keeps confirmed partial persistence and its failure after history recovery', () => {
  const reply = { content: '超时残句', recoveryBaseline: createReplyRecoveryBaseline([], { sessionId: 7, question: '问题' }) }
  applyReplyEvent(reply, event('error', { code: '504', msg: '上游执行结果未知', data: { requestId: 'partial-1', status: 'UNKNOWN', saved: true } }))
  assert.equal(reply.saved, true)
  const recovered = reconcileReplyHistory([{ id: 1, sessionId: 7, senderType: 2, content: '超时残句', requestId: 'partial-1' }], reply)
  assert.equal(recovered.confirmed, true)
  assert.equal(recovered.messages[0].saved, true)
  assert.equal(recovered.messages[0].failed, true)
  assert.equal(recovered.messages[0].requestStatus, 'UNKNOWN')
  assert.match(recovered.messages[0].errorMessage, /结果待确认/)
})

test('save failure retains generated text, marks unsaved and keeps request ID', () => {
  const reply = { content: '' }
  applyReplyEvent(reply, event('message', { code: '200', data: { content: '已生成片段' } }))
  assert.equal(applyReplyEvent(reply, event('error', { code: '500', msg: '回复保存失败', data: { saved: false, requestId: 'trace-2' } })), 'error')
  assert.equal(reply.content, '已生成片段')
  assert.equal(reply.saved, false)
  assert.equal(reply.requestId, 'trace-2')
  assert.equal(reply.errorMessage, '回复保存失败')
  assert.equal(applyReplyEvent(reply, { event: 'done', data: '[DONE]' }), null)
  assert.equal(reply.saved, false)
})

test('normal done marks a reply saved; empty heartbeat never enters content', () => {
  const reply = { content: '正文' }
  assert.equal(applyReplyEvent(reply, { event: '', data: '' }), null)
  assert.equal(applyReplyEvent(reply, { event: 'done', data: '[DONE]' }), 'done')
  assert.equal(reply.saved, true)
  assert.equal(reply.content, '正文')
})

test('transport failure retains partial text and leaves persistence unknown', () => {
  const reply = { content: '半句' }
  applyReplyFailure(reply, { message: '连接中断，正在确认回复状态' })
  assert.equal(reply.content, '半句')
  assert.equal(reply.saved, undefined)
})

test('legacy non-200 business events fail without appending an error as model text', () => {
  const reply = { content: '原文' }
  assert.equal(applyReplyEvent(reply, event('message', { code: '409', msg: '已有活跃回复' })), 'error')
  assert.equal(reply.content, '原文')
  assert.equal(reply.errorCode, '409')
  assert.equal(reply.saved, false)
})

const persisted = (id, senderType, content) => ({ id, sessionId: 7, senderType, content })
const previousRound = [persisted(1, 1, '同一个问题'), persisted(2, 2, '历史回复')]
const pendingReply = (question = '同一个问题') => ({
  content: '当前未确认片段',
  recoveryBaseline: createReplyRecoveryBaseline([...previousRound, { id: Date.now(), senderType: 1, content: question }], { sessionId: 7, question })
})

test('old AI and a repeated question cannot confirm the new round', () => {
  const reply = pendingReply()
  const history = [...previousRound, persisted(3, 1, '同一个问题')]
  const result = reconcileReplyHistory(history, reply)
  assert.equal(result.confirmed, false)
  assert.deepEqual(result.messages, [...history, reply])
  assert.equal(reply.saved, undefined)
})

test('a new AI after this round user confirms persistence without text matching', () => {
  const reply = pendingReply()
  const history = [...previousRound, persisted(3, 1, '同一个问题'), persisted(4, 2, '数据库中完整回复')]
  assert.deepEqual(reconcileReplyHistory(history, reply), { confirmed: true, messages: history })
})

test('an AI belonging to a later user or another session cannot confirm this round', () => {
  const reply = pendingReply()
  const laterRound = [...previousRound, persisted(3, 1, '同一个问题'), persisted(4, 1, '下一个问题'), persisted(5, 2, '后一次回复')]
  assert.equal(reconcileReplyHistory(laterRound, reply).confirmed, false)
  const wrongSession = [...previousRound, persisted(3, 1, '同一个问题'), { ...persisted(4, 2, '碰巧相同片段'), sessionId: 8 }]
  assert.equal(reconcileReplyHistory(wrongSession, reply).confirmed, false)
  assert.equal(reconcileReplyHistory([...previousRound, persisted(3, 1, '其他标签页不同问题'), persisted(4, 2, reply.content)], reply).confirmed, false)
})

test('attach recovery anchors the unanswered persisted user, excluding completed old rounds', () => {
  const history = [...previousRound, persisted(3, 1, '进行中的问题')]
  const reply = { content: '回放片段', recoveryBaseline: createReplyRecoveryBaseline(history, { sessionId: 7, attach: true }) }
  assert.equal(reconcileReplyHistory(history, reply).confirmed, false)
  assert.equal(reconcileReplyHistory([...history, persisted(4, 2, '已保存完整回复')], reply).confirmed, true)
  const old = { content: '未知片段', recoveryBaseline: createReplyRecoveryBaseline(previousRound, { sessionId: 7, attach: true }) }
  assert.equal(reconcileReplyHistory(previousRound, old).confirmed, false)
})

test('an attach that disconnects before replay does not lose its previous partial', () => {
  const reply = { content: '', recoveryPartial: '原连接已有片段' }
  applyReplyFailure(reply, { message: '连接结束，保存待确认' })
  assert.equal(reply.content, '原连接已有片段')
  assert.equal(reply.saved, undefined)
})

test('persistent request association confirms only the exact reply in the owned session', () => {
  const reply = { ...pendingReply(), requestId: 'request-current' }
  const unrelated = [...previousRound, { ...persisted(3, 2, '同样片段'), requestId: 'request-other' }]
  assert.equal(reconcileReplyHistory(unrelated, reply).confirmed, false)
  const own = [...unrelated, { ...persisted(4, 2, '完整回复'), requestId: reply.requestId }]
  assert.equal(reconcileReplyHistory(own, reply).confirmed, true)
  assert.equal(reconcileReplyHistory([{ ...own.at(-1), sessionId: 8 }], reply).confirmed, false)
})

test('a full reload restores unknown saved fragments without claiming a complete result', () => {
  const history = [
    { id: 1, senderType: 1, content: '问题', requestStatus: 'UNKNOWN' },
    { id: 2, senderType: 2, content: '超时片段', requestId: 'reload-1', requestStatus: 'UNKNOWN', requestErrorCode: '504', requestErrorMessage: '回复生成超时', saved: true },
    { id: 3, senderType: 2, content: '正常回复', requestStatus: 'SUCCEEDED', saved: true }
  ]
  const restored = restoreReplyHistory(history)
  assert.equal(restored[0], history[0])
  assert.equal(restored[2], history[2])
  assert.equal(restored[1].saved, true)
  assert.equal(restored[1].failed, true)
  assert.equal(restored[1].errorCode, '504')
  assert.match(restored[1].errorMessage, /超时.*待确认.*不要重复发送/)
  assert.equal(restored[1].content, '超时片段')
})

test('a terminal request without an AI row puts a reply-status notice on its saved question', () => {
  for (const requestStatus of ['UNKNOWN', 'FAILED']) {
    const question = { id: 1, senderType: 1, content: '已保存的问题', requestId: 'unanswered-1', requestStatus, requestErrorMessage: '模型连接中断', saved: false }
    const restored = restoreReplyHistory([question])
    assert.equal(restored.length, 1)
    assert.equal(restored[0].content, '已保存的问题')
    assert.equal(restored[0].senderType, 1)
    assert.equal(restored[0].errorMessage, undefined)
    assert.equal(restored[0].failed, undefined)
    assert.match(restored[0].replyStatusMessage, /模型连接中断/)
    if (requestStatus === 'UNKNOWN') assert.match(restored[0].replyStatusMessage, /待确认.*不要重复发送/)
  }
})

test('a matching AI fragment owns the status notice, including an unconfirmed local fragment', () => {
  const question = { id: 1, senderType: 1, content: '问题', requestId: 'request-1', requestStatus: 'UNKNOWN' }
  const answered = restoreReplyHistory([question, { id: 2, senderType: 2, requestId: 'request-1', requestStatus: 'UNKNOWN', content: '残句' }])
  assert.equal(answered[0].replyStatusMessage, undefined)
  assert.match(answered[1].errorMessage, /待确认/)
  const local = { requestId: 'request-1', content: '未确认片段' }
  const restoredOnce = restoreReplyHistory([question])
  assert.equal(restoreReplyHistory(restoredOnce, local)[0].replyStatusMessage, undefined)
  assert.match(restoreReplyHistory([question], { requestId: 'other-request', content: '别轮片段' })[0].replyStatusMessage, /待确认/)
})
