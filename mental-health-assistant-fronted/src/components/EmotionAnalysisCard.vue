<template>
    <!-- 日记情绪分析结果卡片（与情绪快照同构；null 显示缺失态） -->
    <div class="emotion-analysis-card">
        <template v-if="analysis">
            <div class="analysis-header">
                <div class="emotion-brief">
                    <span class="emotion-name">{{ analysis.primaryEmotion }}</span>
                    <el-tag :type="analysis.isNegative ? 'danger' : 'success'">{{ analysis.isNegative ? '负面情绪' : '正面情绪' }}</el-tag>
                    <el-tag :type="riskTagType">{{ riskText }}</el-tag>
                </div>
                <div class="emotion-score">
                    <span class="score-label">情绪强度</span>
                    <el-progress :percentage="analysis.emotionScore" :color="scoreColor" :stroke-width="8" />
                </div>
            </div>
            <div class="suggestion-block" v-if="analysis.suggestion">
                <div class="block-title">💝 给你的小建议</div>
                <div class="block-text">{{ analysis.suggestion }}</div>
            </div>
            <div class="improvement-block" v-if="analysis.improvementSuggestions && analysis.improvementSuggestions.length">
                <div class="block-title">✨ 治愈小行动</div>
                <div class="improvement-item" v-for="item in analysis.improvementSuggestions" :key="item">{{ item }}</div>
            </div>
            <div class="risk-block" v-if="analysis.isNegative && analysis.riskLevel > 1 && analysis.riskDescription">
                <div class="block-title">🤗 温馨提示</div>
                <div class="block-text">{{ analysis.riskDescription }}</div>
            </div>
            <!-- 危机干预静态保底卡片（纯静态渲染，不依赖 LLM 输出） -->
            <div class="crisis-notice" v-if="analysis.riskLevel === 3">
                <div class="notice-title">📞 专业支持</div>
                <div class="notice-text">如果您正处于较大的痛苦中，请及时寻求专业帮助：全国心理援助热线 12356（24小时）。您不必独自承受这一切。</div>
            </div>
        </template>
        <div v-else class="analysis-missing">
            <span>分析暂不可用</span>
            <!-- 失败恢复（ADR-0005 精化：null 可重试，已有结果后端拒绝重算） -->
            <el-button size="small" type="primary" plain :loading="retrying" @click="emit('retry')">重试分析</el-button>
        </div>
    </div>
</template>
<script setup>
import { computed } from 'vue'

const props = defineProps({
    // 情绪分析结果对象（与情绪快照同构）；null/undefined 显示缺失态
    analysis: { type: Object, default: null },
    // 重试按钮 loading 态（由父组件控制）
    retrying: { type: Boolean, default: false }
})

const emit = defineEmits(['retry'])

const riskTagMap = { 0: 'success', 1: 'info', 2: 'warning', 3: 'danger' }
const riskTextMap = { 0: '正常', 1: '关注', 2: '预警', 3: '危机' }

const riskTagType = computed(() => riskTagMap[props.analysis?.riskLevel] || 'info')
const riskText = computed(() => riskTextMap[props.analysis?.riskLevel] || '未知')

const scoreColor = computed(() => {
    const score = props.analysis?.emotionScore
    if (score >= 80) return '#f56c6c'
    if (score >= 60) return '#e6a23c'
    if (score >= 40) return '#909399'
    return '#67c23a'
})
</script>
<style lang="scss" scoped>
.emotion-analysis-card {
    .analysis-header {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-bottom: 16px;

        .emotion-brief {
            display: flex;
            align-items: center;
            gap: 10px;

            .emotion-name {
                font-size: 20px;
                font-weight: 600;
                color: #374151;
            }
        }

        .emotion-score {
            display: flex;
            align-items: center;
            gap: 12px;

            .score-label {
                font-size: 14px;
                color: #6B7280;
                white-space: nowrap;
            }

            .el-progress {
                flex: 1;
            }
        }
    }

    .suggestion-block,
    .improvement-block,
    .risk-block {
        margin-bottom: 16px;
        padding: 12px;
        border-radius: 8px;
        background: #F9FAFB;

        .block-title {
            margin-bottom: 8px;
            font-weight: 600;
            color: #374151;
        }

        .block-text {
            color: #4B5563;
            line-height: 1.6;
        }
    }

    .improvement-block .improvement-item {
        padding: 4px 0;
        color: #4B5563;
    }

    .crisis-notice {
        padding: 14px;
        border-radius: 8px;
        background: #FEF0F0;
        border: 1px solid #F56C6C;

        .notice-title {
            font-weight: 600;
            color: #F56C6C;
            margin-bottom: 6px;
        }

        .notice-text {
            color: #C45656;
            line-height: 1.6;
        }
    }

    .analysis-missing {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 12px;
        padding: 16px;
        text-align: center;
        color: #9CA3AF;
        background: #F9FAFB;
        border-radius: 8px;
    }
}
</style>
