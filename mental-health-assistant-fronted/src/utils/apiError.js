export const toApiError = (response, cause) => {
  const payload = response?.data
  const status = response?.status
  const fallback = cause?.code === 'ECONNABORTED' ? '请求超时，服务端可能仍在处理，请先查询记录' : '网络连接失败，请稍后重试'
  const error = new Error(payload?.msg || (status ? `请求失败（HTTP ${status}）` : fallback))
  error.name = 'ApiError'
  error.code = payload?.code == null ? undefined : String(payload.code)
  error.status = status
  error.requestId = payload?.data?.requestId || payload?.requestId
  error.requestStatus = payload?.data?.status
  error.saved = payload?.data?.saved
  error.stopping = payload?.data?.stopping
  error.response = response
  error.cause = cause
  return error
}

export const unwrapApiResponse = (response) => {
  if (response.status >= 200 && response.status < 300 && response.data?.code === '200') return response.data.data
  throw toApiError(response)
}

export const isAuthenticationFailure = (error) => error.status === 401 || error.code === '401'
