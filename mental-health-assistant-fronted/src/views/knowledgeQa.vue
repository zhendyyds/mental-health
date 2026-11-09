<template>
  <div class="qa-page">
    <header class="qa-intro">
      <router-link to="/knowledge">返回知识库</router-link>
      <h1>知识问答</h1>
      <p>提出一个心理健康知识问题，查看资料支持的回答和原文出处。</p>
    </header>

    <form class="qa-form" @submit.prevent="submitNewQuestion">
      <label for="qa-question">你的问题</label>
      <textarea id="qa-question" v-model="question" :maxlength="questionLimit" :disabled="busy || confirming"
        rows="4" placeholder="例如：考试前紧张时，有哪些日常放松方法？" aria-describedby="qa-privacy" />
      <div class="qa-actions">
        <span class="qa-count">{{ question.length }} / {{ questionLimit }}</span>
        <el-button v-if="canReplay" native-type="button" :disabled="busy || confirming" @click="sendOriginal">
          {{ result ? '核对原结果' : '重试原请求' }}
        </el-button>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="!question.trim() || confirming">
          {{ attempt ? '发起新请求' : '查找资料并回答' }}
        </el-button>
      </div>
      <p id="qa-privacy" class="qa-note">每次只处理一个问题。请避免输入个人信息。问答历史不会保存，临时结果保留约 15 分钟供查询。</p>
      <p v-if="attempt && question.trim() !== attempt.question && canReplay" class="qa-note">核对或重试时使用上次提交的问题。发起新请求时使用当前输入。</p>
    </form>

    <div v-if="busy" class="qa-feedback" role="status" aria-live="polite">
      <strong>{{ checking ? '正在处理原请求' : '正在查找资料并整理回答' }}</strong>
      <p>请稍候。</p>
    </div>
    <div v-else-if="feedback" class="qa-feedback" role="status" aria-live="polite">
      <strong>{{ feedback.title }}</strong>
      <p>{{ feedback.detail }}</p>
      <small v-if="feedback.requestId">请求 ID {{ feedback.requestId }}</small>
    </div>
    <section v-else-if="result" class="qa-result" :class="{ 'has-sources': result.grounded }" aria-label="问答结果">
      <div class="qa-answer">
        <h2>{{ result.grounded ? '资料回答' : '未找到足够资料' }}</h2>
        <p class="qa-answer-text">{{ result.answer }}</p>
        <small v-if="result.requestId">请求 ID {{ result.requestId }}</small>
      </div>
      <aside v-if="result.grounded" class="qa-sources" aria-labelledby="qa-sources-heading">
        <h2 id="qa-sources-heading">引用来源</h2>
        <ol>
          <li v-for="citation in result.citations" :key="citation.id">
            <router-link :to="{ name: 'knowledge-article', params: { id: citation.articleId } }"
              target="_blank" rel="noopener noreferrer">
              <span class="qa-source-id">[{{ citation.id }}]</span> {{ citation.title }}
            </router-link>
            <blockquote v-if="citation.excerpt">{{ citation.excerpt }}</blockquote>
          </li>
        </ol>
      </aside>
    </section>
    <p v-else class="qa-empty">回答和资料出处会显示在这里。</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { askKnowledgeQuestion } from '@/api/frontend'
import { createClientRequestId } from '@/utils/replyStream'
import { knowledgeQaErrorState } from '@/utils/knowledgeQaState'

const questionLimit = 1000
// The result has a shorter server-side cache. Never replay an ID beyond the
// idempotency window, where the server may treat the same ID as new work.
const requestIdWindowMs = 24 * 60 * 60 * 1000
const question = ref('')
const attempt = ref(null)
const result = ref(null)
const feedback = ref(null)
const busy = ref(false)
const checking = ref(false)
const confirming = ref(false)
const canReplay = computed(() => Boolean(attempt.value && (result.value || feedback.value?.canReplay)))
let mounted = true
onBeforeUnmount(() => { mounted = false })

const run = async (request, replay = false) => {
  if (busy.value) return
  busy.value = true
  checking.value = replay
  // Rechecking a result also revalidates its sources. Hide the earlier answer
  // while checking so a source-changed error cannot leave stale text visible.
  result.value = null
  feedback.value = null
  try {
    result.value = await askKnowledgeQuestion(request.question, request.clientRequestId)
  } catch (error) {
    feedback.value = knowledgeQaErrorState(error)
  } finally {
    busy.value = false
  }
}

const submitNewQuestion = async () => {
  const input = question.value.trim()
  if (!input || input.length > questionLimit || busy.value || confirming.value) return
  if (attempt.value) {
    confirming.value = true
    const prompt = feedback.value?.canReplay
      ? '原请求的结果还未确认。继续会用当前问题发起一次新的问答。'
      : '这会用当前问题发起一次新的问答。'
    const confirmed = await ElMessageBox.confirm(prompt, '发起新请求', {
      confirmButtonText: '发起新请求', cancelButtonText: '返回', type: 'info'
    }).then(() => true, () => false)
    confirming.value = false
    if (!confirmed || !mounted) return
  }
  let clientRequestId
  try {
    clientRequestId = createClientRequestId()
  } catch (error) {
    result.value = null
    feedback.value = { title: '请求无法发出', detail: error.message }
    return
  }
  attempt.value = { question: input, clientRequestId, startedAt: Date.now() }
  await run(attempt.value)
}

const sendOriginal = async () => {
  if (!canReplay.value || busy.value || confirming.value) return
  if (Date.now() - attempt.value.startedAt >= requestIdWindowMs) {
    result.value = null
    feedback.value = knowledgeQaErrorState({ requestStatus: 'RESULT_UNAVAILABLE' })
    return
  }
  await run(attempt.value, true)
}
</script>

<style scoped>
.qa-page {
  --qa-ink: #1f2937;
  --qa-muted: #526276;
  --qa-link: #235a9b;
  --qa-line: #dbe4ef;
  --qa-soft: #f1f6fb;
  color: var(--qa-ink);
  max-width: 1040px;
  min-height: 68vh;
  margin: 0 auto;
  padding: clamp(24px, 5vw, 56px) clamp(16px, 4vw, 32px) 64px;
}
.qa-intro { margin-bottom: 24px; }
.qa-intro h1 { margin: 12px 0 4px; font-size: clamp(28px, 5vw, 40px); }
.qa-intro p, .qa-note, .qa-empty, small { color: var(--qa-muted); }
.qa-page a { color: var(--qa-link); text-underline-offset: 3px; }
.qa-page a:hover { text-decoration: underline; }
.qa-page :focus-visible { outline: 2px solid var(--qa-link); outline-offset: 3px; }
.qa-form { padding: clamp(16px, 4vw, 24px); background: var(--qa-soft); border: 1px solid var(--qa-line); border-radius: 10px; }
.qa-form label { display: block; font-weight: 600; margin-bottom: 8px; }
.qa-form textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 12px;
  resize: vertical;
  border: 1px solid #aebed0;
  border-radius: 6px;
  font: inherit;
  color: var(--qa-ink);
}
.qa-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: flex-end; margin-top: 8px; }
.qa-actions .el-button + .el-button { margin-left: 0; }
.qa-count { color: var(--qa-muted); margin-right: auto; font-size: 13px; }
.qa-note { margin-top: 10px; font-size: 13px; line-height: 1.6; }
.qa-feedback, .qa-empty, .qa-result { margin-top: 24px; }
.qa-feedback { border-left: 3px solid #4381b9; padding: 12px 16px; background: var(--qa-soft); }
.qa-feedback p { margin: 6px 0; }
.qa-result.has-sources { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr); gap: 28px; }
.qa-result h2 { font-size: 20px; margin: 0 0 12px; }
.qa-answer-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; margin-bottom: 14px; }
.qa-sources { border-left: 1px solid var(--qa-line); padding-left: 20px; }
.qa-sources ol { list-style: none; padding: 0; margin: 0; }
.qa-sources li { border-top: 1px solid var(--qa-line); padding: 12px 0; overflow-wrap: anywhere; }
.qa-source-id { font-weight: 700; }
.qa-sources blockquote { margin: 6px 0 0; font-size: 14px; line-height: 1.6; color: var(--qa-muted); }
small { display: block; overflow-wrap: anywhere; }
@media (max-width: 700px) {
  .qa-result.has-sources { grid-template-columns: 1fr; gap: 20px; }
  .qa-sources { border-left: 0; padding-left: 0; }
}
</style>
