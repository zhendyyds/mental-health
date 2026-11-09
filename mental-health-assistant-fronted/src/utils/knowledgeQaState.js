const retryAfterSeconds = (headers) => {
  const value = headers?.['retry-after']
  if (typeof value !== 'string') return null
  if (/^\d+$/.test(value)) {
    const seconds = Number(value)
    return Number.isSafeInteger(seconds) ? seconds : null
  }
  const date = Date.parse(value)
  return Number.isFinite(date) ? Math.max(0, Math.ceil((date - Date.now()) / 1000)) : null
}

export const knowledgeQaErrorState = (error) => {
  const state = (title, detail, canReplay = false) => ({ title, detail, canReplay, requestId: error?.requestId })
  const code = error?.code || String(error?.status || '')
  const status = error?.requestStatus
  if (status === 'SOURCE_CHANGED') return state('来源已变化', '原回答已收起。可以发起新请求，查看当前可用的资料。')
  if (status === 'RESULT_UNAVAILABLE') return state('原结果已无法查看', '临时结果可能已过期。可以发起新请求。')
  if (status === 'CONFLICT') return state('原请求与问题不一致', '请发起新请求，提交当前问题。')
  if (status === 'RUNNING') return state('原请求仍在处理', '稍后可以重试原请求。', true)
  if (status === 'UNKNOWN' || code === '504' || ['ECONNABORTED', 'ETIMEDOUT'].includes(error?.cause?.code)) {
    const timeout = code === '504' || ['ECONNABORTED', 'ETIMEDOUT'].includes(error?.cause?.code)
    return state(timeout ? '问答超时，结果待确认' : '原请求结果待确认', '可以重试原请求。', true)
  }
  if (code === '429') {
    const seconds = retryAfterSeconds(error?.response?.headers)
    return state('请求过于频繁', seconds > 0 ? `请等待 ${seconds} 秒后再发起新请求。` : '请稍后再发起新请求。')
  }
  if (code === '503') return state('知识问答暂不可用', '服务恢复后可以发起新请求。')
  if (status === 'FAILED') return state('原请求失败', error?.message || '请稍后发起新请求。')
  if (!error?.response) return state('连接中断，结果待确认', '网络恢复后可以重试原请求。如果服务器还未收到，会提交原问题。', true)
  return state('问答失败', error?.message || '请稍后发起新请求。')
}
