export const emptyEmotionAnalysis = () => ({
  primaryEmotion: null,
  emotionScore: null,
  isNegative: null,
  riskLevel: null,
  suggestion: '',
  improvementSuggestions: [],
  analyzedAt: null,
  analysisStatus: 'EMPTY',
  cached: false,
  stale: false
})

const hasAnalysisTimestamp = (data) => typeof data?.analyzedAt === 'string'
  && /^\d{4}-\d{2}-\d{2}T/.test(data.analyzedAt) && !Number.isNaN(Date.parse(data.analyzedAt))

export const normalizeEmotionAnalysis = (data) => ({
  ...emptyEmotionAnalysis(),
  ...data,
  analysisStatus: data?.analysisStatus || (hasAnalysisTimestamp(data) && data?.primaryEmotion && Number.isFinite(data?.emotionScore) ? 'SUCCEEDED' : 'EMPTY'),
  improvementSuggestions: Array.isArray(data?.improvementSuggestions) ? data.improvementSuggestions : []
})

export const hasEmotionAnalysis = (data) => Boolean(
  hasAnalysisTimestamp(data) && (data?.analysisStatus === 'SUCCEEDED' || data?.cached === true)
  && data?.primaryEmotion && Number.isFinite(data?.emotionScore)
)

export const emotionAnalysisLabel = (data, loading = false) => {
  const labels = {
    SUCCEEDED: '分析已完成',
    TIMED_OUT: '分析超时',
    FAILED: '分析暂不可用',
    REJECTED: '请求过多或分析额度不足',
    EMPTY: '尚未分析'
  }
  let label = loading ? '正在分析' : labels[data?.analysisStatus] || '尚未分析'
  if (data?.stale && hasEmotionAnalysis(data)) label += '；当前展示旧结果，未覆盖最新消息'
  else if (data?.cached && hasEmotionAnalysis(data)) label += '；使用已缓存结果'
  return label
}

export const emotionStatusFromError = (error) => {
  if (error?.status === 429) return 'REJECTED'
  if (error?.status === 504 || error?.cause?.code === 'ECONNABORTED') return 'TIMED_OUT'
  return 'FAILED'
}
