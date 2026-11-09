import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyEmotionAnalysis, normalizeEmotionAnalysis, hasEmotionAnalysis, emotionAnalysisLabel, emotionStatusFromError } from '../src/utils/emotionAnalysisState.js'

test('empty or failed analysis never turns neutral placeholder values into a real result', () => {
  assert.equal(hasEmotionAnalysis(emptyEmotionAnalysis()), false)
  assert.equal(emotionAnalysisLabel(emptyEmotionAnalysis()), '尚未分析')
  const fallback = normalizeEmotionAnalysis({ primaryEmotion: '中性', emotionScore: 50, analysisStatus: 'TIMED_OUT' })
  assert.equal(hasEmotionAnalysis(fallback), false)
  assert.equal(emotionAnalysisLabel(fallback), '分析超时')
  const backendNeutralFallback = normalizeEmotionAnalysis({ primaryEmotion: '中性', emotionScore: 50,
    analysisStatus: 'TIMED_OUT', cached: false, stale: true, analyzedAt: null })
  assert.equal(hasEmotionAnalysis(backendNeutralFallback), false)
  assert.equal(emotionAnalysisLabel(backendNeutralFallback), '分析超时')
})

test('cached stale analysis remains visible with an explicit freshness and failure label', () => {
  const cached = normalizeEmotionAnalysis({ primaryEmotion: '平静', emotionScore: 60, analyzedAt: '2026-11-06T10:00:00', analysisStatus: 'TIMED_OUT', cached: true, stale: true })
  assert.equal(hasEmotionAnalysis(cached), true)
  assert.match(emotionAnalysisLabel(cached), /分析超时.*旧结果.*未覆盖最新消息/)
  assert.match(emotionAnalysisLabel({ ...cached, analysisStatus: 'REJECTED' }), /额度不足/)
})

test('successful legacy payloads remain compatible while malformed suggestions are removed', () => {
  const success = normalizeEmotionAnalysis({ primaryEmotion: '平静', emotionScore: 60, analyzedAt: '2026-11-06T10:00:00', improvementSuggestions: null })
  assert.equal(success.analysisStatus, 'SUCCEEDED')
  assert.equal(hasEmotionAnalysis(success), true)
  assert.deepEqual(success.improvementSuggestions, [])
  assert.equal(emotionAnalysisLabel(success, true), '正在分析')
  assert.equal(hasEmotionAnalysis({ ...success, analyzedAt: null }), false)
  assert.equal(hasEmotionAnalysis({ ...success, analyzedAt: 'invalid' }), false)
  assert.equal(hasEmotionAnalysis({ ...success, analysisStatus: 'FAILED', cached: false, stale: true }), false)
})

test('HTTP limits and deadlines are distinguished from other analysis failures', () => {
  assert.equal(emotionStatusFromError({ status: 429 }), 'REJECTED')
  assert.equal(emotionStatusFromError({ status: 504 }), 'TIMED_OUT')
  assert.equal(emotionStatusFromError({ cause: { code: 'ECONNABORTED' } }), 'TIMED_OUT')
  assert.equal(emotionStatusFromError({ status: 502 }), 'FAILED')
})
