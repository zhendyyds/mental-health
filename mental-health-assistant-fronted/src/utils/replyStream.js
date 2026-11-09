// Both generation and attach use this state transition. Errors never replace
// generated text, and a terminal error cannot later turn into a saved result.
export const createClientRequestId = (crypto = globalThis.crypto) => {
  if (crypto?.randomUUID) return crypto.randomUUID()
  if (!crypto?.getRandomValues) throw new Error('浏览器无法生成安全请求 ID，请更换浏览器后重试')
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export const applyReplyFailure = (reply, error) => {
  if (reply.recoveryPartial?.startsWith(reply.content)) reply.content = reply.recoveryPartial
  reply.errorMessage = error?.message || 'AI回复失败，请稍后重试'
  if (typeof error?.saved === 'boolean') reply.saved = error.saved
  if (error?.requestId) reply.requestId = error.requestId
  if (error?.requestStatus) reply.requestStatus = error.requestStatus
  if (reply.requestStatus === 'UNKNOWN') {
    if (!reply.errorMessage.includes('待确认')) reply.errorMessage += '；结果待确认'
    if (!reply.errorMessage.includes('不要重复发送')) reply.errorMessage += '；请刷新会话核对记录，不要重复发送'
  }
}

export const applyReplyEvent = (reply, event) => {
  if (reply.failed) return null
  if (event.event === 'done') {
    let data
    try { data = JSON.parse(event.data || '{}') } catch { /* Compatible with legacy [DONE]. */ }
    if (data?.requestId) reply.requestId = data.requestId
    if (data?.status) reply.requestStatus = data.status
    reply.saved = typeof data?.saved === 'boolean' ? data.saved : true
    reply.errorMessage = ''
    return 'done'
  }
  if (!event.data?.trim()) return null
  const payload = JSON.parse(event.data)
  if (payload.data?.requestId) reply.requestId = payload.data.requestId
  if (payload.data?.status) reply.requestStatus = payload.data.status
  if (event.event === 'error' || String(payload.code) !== '200') {
    reply.failed = true
    reply.errorCode = String(payload.code)
    applyReplyFailure(reply, {
      message: payload.msg || 'AI回复失败，请稍后重试',
      saved: typeof payload.data?.saved === 'boolean' ? payload.data.saved : payload.data?.status === 'UNKNOWN' ? undefined : false,
      requestId: payload.data?.requestId,
      requestStatus: payload.data?.status
    })
    return 'error'
  }
  if (typeof payload.data?.content === 'string') reply.content += payload.data.content
  return 'message'
}

export const restoreReplyHistory = (history, pendingReply) => {
  const answeredRequests = new Set(history.filter(message => message.senderType === 2 && message.requestId).map(message => message.requestId))
  if (pendingReply?.content && pendingReply.requestId) answeredRequests.add(pendingReply.requestId)
  return history.map(message => {
    if (!['UNKNOWN', 'FAILED'].includes(message.requestStatus)) return message
    if (message.senderType === 1 && (!message.requestId || answeredRequests.has(message.requestId))) {
      return message.replyStatusMessage ? { ...message, replyStatusMessage: undefined } : message
    }
    if (![1, 2].includes(message.senderType)) return message
    const reply = { ...message, failed: true, errorCode: String(message.requestErrorCode || (message.requestStatus === 'UNKNOWN' ? '409' : '500')) }
    applyReplyFailure(reply, {
      message: message.requestErrorMessage || (message.requestStatus === 'UNKNOWN' ? '原请求结果待确认' : '回复处理失败'),
      requestId: message.requestId,
      requestStatus: message.requestStatus,
      // Presence in persisted AI history confirms this fragment, not provider completion.
      saved: message.senderType === 2 ? true : undefined
    })
    return message.senderType === 1 ? { ...message, replyStatusMessage: reply.errorMessage } : reply
  })
}

const persistedMessages = (messages, sessionId) => messages
  .filter(message => String(message.sessionId) === String(sessionId) && /^[1-9]\d*$/.test(String(message.id)) && [1, 2].includes(message.senderType))
  .toSorted((left, right) => BigInt(left.id) < BigInt(right.id) ? -1 : BigInt(left.id) > BigInt(right.id) ? 1 : 0)

export const createReplyRecoveryBaseline = (history, { sessionId, question, attach = false }) => {
  const persisted = persistedMessages(history, sessionId)
  const last = persisted.at(-1)
  return {
    sessionId,
    knownIds: persisted.map(message => String(message.id)),
    maxKnownId: last ? String(last.id) : '0',
    question,
    // An attach can identify the current round only when history ends in an
    // unanswered persisted user message. A completed old round is not evidence.
    pendingUserId: attach && last?.senderType === 1 ? String(last.id) : undefined,
    requestId: attach && last?.senderType === 1 ? last.requestId : undefined
  }
}

export const reconcileReplyHistory = (history, reply) => {
  const baseline = reply?.recoveryBaseline
  let confirmed = false
  if (baseline) {
    const persisted = persistedMessages(history, baseline.sessionId)
    const requestId = reply.requestId || baseline.requestId
    if (requestId) {
      confirmed = persisted.some(message => message.senderType === 2 && message.requestId === requestId)
      const messages = confirmed && reply.failed
        ? history.map(message => message.senderType === 2 && message.requestId === requestId
          ? { ...message, failed: true, errorMessage: reply.errorMessage, errorCode: reply.errorCode, requestStatus: reply.requestStatus, saved: true }
          : message)
        : history
      return { confirmed, messages: confirmed || !reply?.content ? messages : [...history, reply] }
    }
    const isNew = message => !baseline.knownIds.includes(String(message.id)) && BigInt(message.id) > BigInt(baseline.maxKnownId)
    let userIndex = baseline.pendingUserId
      ? persisted.findIndex(message => String(message.id) === baseline.pendingUserId && message.senderType === 1)
      : persisted.findIndex(message => message.senderType === 1 && isNew(message))
    if (userIndex >= 0 && (baseline.question === undefined || persisted[userIndex].content === baseline.question)) {
      for (const message of persisted.slice(userIndex + 1)) {
        if (message.senderType === 1) break
        if (message.senderType === 2 && isNew(message)) { confirmed = true; break }
      }
    }
  }
  return { confirmed, messages: confirmed || !reply?.content ? history : [...history, reply] }
}
