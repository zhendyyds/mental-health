<template>
  <div class="markdown-content" :class="{ 'ai-markdown': isAiMessage }">
    <div v-html="renderedContent"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'

const props = defineProps({
  content: {
    type: String,
    required: true
  },
  isAiMessage: {
    type: Boolean,
    default: false
  }
})

const renderedContent = computed(() => renderSafeMarkdown(props.content))
</script>

<style scoped>
.markdown-content {
  line-height: 1.6;
  color: inherit;
}

.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3) {
  margin: 1em 0 0.5em 0;
  font-weight: 600;
  line-height: 1.3;
}

.markdown-content :deep(h1) {
  font-size: 1.5em;
  border-bottom: 2px solid #e5e7eb;
  padding-bottom: 0.3em;
}

.markdown-content :deep(h2) {
  font-size: 1.3em;
  color: #374151;
}

.markdown-content :deep(h3) {
  font-size: 1.1em;
  color: #4b5563;
}

.markdown-content :deep(p) {
  margin: 0.5em 0;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  margin: 0.5em 0;
  padding-left: 1.5em;
}

.markdown-content :deep(li) {
  margin: 0.3em 0;
}

.markdown-content :deep(blockquote) {
  border-left: 4px solid #d1d5db;
  padding-left: 1em;
  margin: 1em 0;
  color: #6b7280;
  font-style: italic;
  background: #f9fafb;
  border-radius: 0 0.5em 0.5em 0;
  padding: 0.5em 1em;
}

.ai-markdown :deep(blockquote) {
  border-left-color: #3b82f6;
  background: #eff6ff;
}

.markdown-content :deep(hr) {
  border: none;
  border-top: 2px solid #e5e7eb;
  margin: 1.5em 0;
}

.markdown-content :deep(code) {
  background: #f3f4f6;
  padding: 0.2em 0.4em;
  border-radius: 0.25em;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.85em;
  color: #e11d48;
}

.ai-markdown :deep(code) {
  background: #dbeafe;
  color: #1e40af;
}

.markdown-content :deep(pre) {
  background: #1f2937;
  color: #f9fafb;
  padding: 1em;
  border-radius: 0.5em;
  overflow-x: auto;
  margin: 1em 0;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.85em;
  line-height: 1.4;
}

.markdown-content :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
}

.markdown-content :deep(a) {
  color: #3b82f6;
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition: border-color 0.2s ease;
}

.markdown-content :deep(a:hover) {
  border-bottom-color: #3b82f6;
}

.ai-markdown :deep(a) {
  color: #1e40af;
}

.ai-markdown :deep(a:hover) {
  border-bottom-color: #1e40af;
}

.markdown-content :deep(strong) {
  font-weight: 600;
  color: #374151;
}

.ai-markdown :deep(strong) {
  color: #1e40af;
}

.markdown-content :deep(em) {
  font-style: italic;
  color: #6b7280;
}
</style>





