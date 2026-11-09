<template>
    <div class="emotionDiary-container">
        <div class="header-section">
            <div class="header-content">
                <el-image :src="iconUrl" style="width: 60px;height: 60px"></el-image>
                <h1>情绪日志</h1>
            </div>
        </div>
        <div class="content">
            <el-tabs v-model="activeTab" @tab-change="handleTabChange">
                <!-- 写日记 -->
                <el-tab-pane label="写日记" name="write">
                    <!-- 日期与情绪评分 -->
                    <div class="diary-card">
                        <div class="title">日记日期与情绪评分</div>
                        <div class="section">
                            <div class="form-label">日记日期（可补写过去的日期）</div>
                            <el-date-picker
                                v-model="diaryForm.diaryDate"
                                type="date"
                                value-format="YYYY-MM-DD"
                                placeholder="选择日期"
                                :disabled-date="disableFutureDate"
                                style="width: 100%"
                            />
                        </div>
                        <div class="section">
                            <p>您当天的整体情绪状态如何？(1-10分)</p>
                            <div class="rate">
                                <el-rate
                                    v-model="diaryForm.moodScore"
                                    :texts="emotionStatus"
                                    show-texts
                                    :max="10"
                                    size="large"
                                />
                            </div>
                        </div>
                    </div>
                    <!-- 主要情绪 -->
                    <div class="diary-card">
                        <div class="title">主要情绪</div>
                        <div class="emotion-grid">
                            <div v-for="emotion in emotionOptions" :key="emotion.name" class="emotion-card" :class="{'selected': emotion.name === diaryForm.dominantEmotion}" @click="selectEmotion(emotion.name)">
                                <el-image :src="emotion.url" style="width: 50px;height: 50px"></el-image>
                                <div class="emotion-name">{{emotion.name}}</div>
                            </div>
                        </div>
                    </div>
                    <!-- 详细记录 -->
                    <div class="diary-card">
                        <div class="title">详细记录</div>
                        <div class="detail-form">
                            <div class="form-group">
                                <div class="form-label">情绪触发因素</div>
                                <el-input v-model="diaryForm.emotionTriggers" placeholder="那天什么事情影响了您的情绪？" type="textarea" :rows="3" maxLength="1000" show-word-limit></el-input>
                            </div>
                             <div class="form-group">
                                <div class="form-label">今日感想</div>
                                <el-input v-model="diaryForm.diaryContent" placeholder="写下您当天的想法、感受或发生的有趣事情..." type="textarea" :rows="5" maxLength="2000" show-word-limit></el-input>
                            </div>
                            <!-- 生活指标 -->
                            <div class="life-indicators">
                                <div class="indicator-group">
                                    <div class="form-label">睡眠质量</div>
                                   <el-select v-model="diaryForm.sleepQuality" placeholder="请选择">
                                        <el-option label="很差" :value="1"></el-option>
                                        <el-option label="较差" :value="2"></el-option>
                                        <el-option label="一般" :value="3"></el-option>
                                        <el-option label="良好" :value="4"></el-option>
                                        <el-option label="优秀" :value="5"></el-option>
                                    </el-select>
                                </div>
                                <div class="indicator-group">
                                    <div class="form-label">压力水平</div>
                                    <el-select v-model="diaryForm.stressLevel" placeholder="请选择">
                                        <el-option label="很低" :value="1"></el-option>
                                        <el-option label="较低" :value="2"></el-option>
                                        <el-option label="中等" :value="3"></el-option>
                                        <el-option label="较高" :value="4"></el-option>
                                        <el-option label="很高" :value="5"></el-option>
                                    </el-select>
                                </div>
                            </div>
                            <div class="action-buttons">
                                <el-button @click="resetForm">重置</el-button>
                                <el-button type="primary" :loading="submitting" @click="submit">提交记录</el-button>
                            </div>
                        </div>
                    </div>
                    <!-- AI 情绪分析结果：提交后弹窗内展示（见底部等待/结果弹窗），不常驻表单 -->
                </el-tab-pane>
                <!-- 我的日记 -->
                <el-tab-pane label="我的日记" name="history">
                    <div class="diary-card" v-loading="historyLoading">
                        <div class="title">我的日记</div>
                        <div v-if="historyList.length === 0 && !historyLoading" class="history-empty">还没有日记记录，去「写日记」记录今天的心情吧</div>
                        <div v-for="diary in historyList" :key="diary.id" class="history-item" @click="openHistoryDetail(diary)">
                            <div class="history-head">
                                <span class="history-date">{{ diary.diaryDate }}</span>
                                <el-tag :type="getHistoryEmotionTagType(diary.dominantEmotion)">{{ diary.dominantEmotion }}</el-tag>
                            </div>
                            <div class="history-body">
                                <el-rate :model-value="diary.moodScore" :max="10" disabled />
                                <div class="history-indicators" v-if="diary.sleepQuality || diary.stressLevel">
                                    <span v-if="diary.sleepQuality">睡眠 {{ diary.sleepQuality }}/5</span>
                                    <span v-if="diary.stressLevel">压力 {{ diary.stressLevel }}/5</span>
                                </div>
                            </div>
                            <div class="history-content" v-if="diary.diaryContent">{{ diary.diaryContent }}</div>
                        </div>
                        <el-pagination
                            style="margin-top: 20px"
                            :current-page="historyPagination.current"
                            :page-size="historyPagination.size"
                            layout="prev, pager, next"
                            :total="historyPagination.total"
                            @current-change="handleHistoryPageChange"
                        />
                    </div>
                </el-tab-pane>
            </el-tabs>
        </div>

        <!-- 历史详情弹窗 -->
        <el-dialog v-model="historyDetailVisible" title="日记详情" width="700px" :close-on-click-modal="false">
            <div class="history-detail" v-if="currentHistory">
                <el-descriptions :column="2" border>
                    <el-descriptions-item label="日记日期">{{ currentHistory.diaryDate }}</el-descriptions-item>
                    <el-descriptions-item label="主要情绪">{{ currentHistory.dominantEmotion }}</el-descriptions-item>
                    <el-descriptions-item label="情绪评分（1-10）">{{ currentHistory.moodScore }}</el-descriptions-item>
                    <el-descriptions-item label="睡眠/压力">{{ currentHistory.sleepQuality || '-' }}/5 · {{ currentHistory.stressLevel || '-' }}/5</el-descriptions-item>
                    <el-descriptions-item label="情绪触发因素" :span="2">{{ currentHistory.emotionTriggers || '无' }}</el-descriptions-item>
                    <el-descriptions-item label="日记内容" :span="2">{{ currentHistory.diaryContent || '无' }}</el-descriptions-item>
                </el-descriptions>
                <div class="detail-analysis-title">AI 情绪分析</div>
                <EmotionAnalysisCard :analysis="currentHistory.aiEmotionAnalysis" :retrying="retryingAnalysis" @retry="retryAnalysis(currentHistory.id)" />
            </div>
            <template #footer>
                <el-button @click="historyDetailVisible = false">关闭</el-button>
            </template>
        </el-dialog>

        <!-- 提交等待/结果弹窗：等待态可切后台（请求继续，完成后通知）；完成后原地展示分析结果，关闭即清除 -->
        <el-dialog v-model="waitingVisible" :title="resultReady ? 'AI 情绪分析结果' : 'AI 情绪分析中'" width="560px" :close-on-click-modal="false" @close="handleWaitingClose">
            <div class="waiting-body" v-if="!resultReady">
                <div class="waiting-spinner"></div>
                <div class="waiting-title">AI 正在分析你的日记…</div>
                <div class="waiting-subtitle">通常需要几秒到十几秒</div>
            </div>
            <EmotionAnalysisCard v-else :analysis="analysisResult.aiEmotionAnalysis" :retrying="retryingAnalysis" @retry="retryAnalysis(analysisResult.id)" />
            <template #footer>
                <el-button v-if="!resultReady" @click="goBackground">切到后台，完成后通知我</el-button>
                <el-button v-else type="primary" @click="waitingVisible = false">完成</el-button>
            </template>
        </el-dialog>
    </div>
</template>
<script setup>
    import { dayjs, ElMessage, ElNotification, ElMessageBox } from 'element-plus'
    import { ref, reactive, computed } from 'vue'
    import { addEmotionDiary, getEmotionDiaryPage, retryEmotionDiaryAnalysis } from '@/api/frontend'
    import EmotionAnalysisCard from '@/components/EmotionAnalysisCard.vue'

    // 情绪评分
    const emotionStatus = ['绝望崩溃', '消沉抑郁', '焦虑烦躁', '低落不悦', '平静淡然', '轻松惬意', '愉悦舒心', '欢欣满足', '兴奋欣喜', '极致幸福']

    // 情绪选项
    const emotionOptions = [
        { name: '开心', url: new URL('@/assets/images/开心.png', import.meta.url).href },
        { name: '平静', url: new URL('@/assets/images/平静.png', import.meta.url).href },
        { name: '焦虑', url: new URL('@/assets/images/焦虑.png', import.meta.url).href },
        { name: '悲伤', url: new URL('@/assets/images/悲伤.png', import.meta.url).href },
        { name: '兴奋', url: new URL('@/assets/images/兴奋.png', import.meta.url).href },
        { name: '疲惫', url: new URL('@/assets/images/疲惫.png', import.meta.url).href },
        { name: '惊讶', url: new URL('@/assets/images/惊讶.png', import.meta.url).href },
        { name: '困惑', url: new URL('@/assets/images/困惑.png', import.meta.url).href },
    ]

    // 与提交页选项一致的 tag 颜色映射
    const getHistoryEmotionTagType = (emotion) => {
        const emotionTypes = { '开心': 'success', '平静': 'info', '焦虑': 'warning', '悲伤': 'info', '兴奋': 'warning', '疲惫': 'info', '惊讶': 'warning', '困惑': 'info' }
        return emotionTypes[emotion] || 'info'
    }

    const selectEmotion = (emotion) => {
        diaryForm.dominantEmotion = emotion
    }

    const activeTab = ref('write')

    const diaryForm = reactive({
        diaryDate: dayjs().format('YYYY-MM-DD'),
        moodScore: null,
        dominantEmotion: '',
        emotionTriggers: '',
        diaryContent: '',
        sleepQuality: null,
        stressLevel: null
    })

    // 禁选未来日期（补写只开过去）
    const disableFutureDate = (date) => date.getTime() > Date.now()

    const resetForm = () => {
        Object.assign(diaryForm, {
            diaryDate: dayjs().format('YYYY-MM-DD'),
            moodScore: null,
            dominantEmotion: '',
            emotionTriggers: '',
            diaryContent: '',
            sleepQuality: null,
            stressLevel: null
        })
    }

    // 提交（后端同步一次性分析，ADR-0005；等待弹窗承载 loading，可切后台）
    const submitting = ref(false)
    const analysisResult = ref(null)
    const waitingVisible = ref(false)
    // 请求是否已出结果（区分"完成后的弹窗关闭"与"用户切后台的关闭"）
    const requestDone = ref(false)
    // 切后台模式：请求继续跑，完成后用通知告知
    const wentBackground = ref(false)
    let formSnapshot = null
    // 弹窗处于结果态（前台分析完成，原地展示）
    const resultReady = computed(() => Boolean(analysisResult.value))

    const submit = () => {
        if (!diaryForm.diaryDate) {
            ElMessage.error('请选择日记日期')
            return
        }
        if (!diaryForm.moodScore) {
            ElMessage.error('请选择情绪评分')
            return
        }
        if (!diaryForm.dominantEmotion) {
            ElMessage.error('请选择主要情绪')
            return
        }
        requestDone.value = false
        wentBackground.value = false
        submitting.value = true
        waitingVisible.value = true
        addEmotionDiary(diaryForm).then((res) => {
            requestDone.value = true
            historyLoaded.value = false
            if (wentBackground.value) {
                resetForm()
                // 危机风险：后台模式下结果不可见，用模态警示框兜底 12356
                // （不用常驻通知：通知堆叠会上移补位、被高 z-index 固定元素覆盖；模态遮罩保证置顶且必须确认）
                if (res?.aiEmotionAnalysis?.riskLevel === 3) {
                    ElMessageBox.alert(
                        '您的日记已保存。如果您正处于较大的痛苦中，请及时寻求专业帮助：全国心理援助热线 12356（24小时）。您不必独自承受这一切。',
                        '请重视此刻的状态',
                        {
                            confirmButtonText: '我知道了',
                            type: 'error',
                            customClass: 'crisis-message-box',
                            closeOnClickModal: false,
                            closeOnPressEscape: false
                        }
                    )
                } else {
                    ElNotification.success({ title: '日记已保存', message: 'AI 分析完成，可在「我的日记」中查看' })
                }
            } else {
                // 等待弹窗原地切换为结果态（不常驻表单页，关闭即清除）
                analysisResult.value = res
                resetForm()
            }
        }).catch((error) => {
            requestDone.value = true
            const message = error?.message || '日记可能未保存，请稍后查询记录'
            if (wentBackground.value) {
                ElNotification.error({ title: '提交失败', message })
                restoreForm()
            } else {
                waitingVisible.value = false
                ElMessage.error(message)
            }
        }).finally(() => {
            submitting.value = false
        })
    }

    // 弹窗被关闭：结果态 → 清除分析结果；等待态 → 切后台（快照表单并清空，成功不重复提交，失败可恢复）
    const handleWaitingClose = () => {
        if (requestDone.value) {
            analysisResult.value = null
            return
        }
        wentBackground.value = true
        formSnapshot = { ...diaryForm }
        resetForm()
    }

    const goBackground = () => {
        waitingVisible.value = false
    }

    // 重试失败的分析（提交结果弹窗与历史详情弹窗共用；成功后原地刷新所在上下文）
    const retryingAnalysis = ref(false)
    const retryAnalysis = (diaryId) => {
        retryingAnalysis.value = true
        retryEmotionDiaryAnalysis(diaryId).then((res) => {
            if (analysisResult.value && analysisResult.value.id === diaryId) {
                analysisResult.value = res
            }
            if (currentHistory.value && currentHistory.value.id === diaryId) {
                currentHistory.value = res
            }
            ElMessage.success('分析完成')
        }).catch((error) => {
            ElMessage.error(error?.message || '重试失败，请稍后再试')
        }).finally(() => {
            retryingAnalysis.value = false
        })
    }

    const restoreForm = () => {
        if (formSnapshot) {
            Object.assign(diaryForm, formSnapshot)
            formSnapshot = null
        }
    }

    // 历史日记（懒加载：首次进入 history tab 时拉取）
    const historyList = ref([])
    const historyLoading = ref(false)
    const historyLoaded = ref(false)
    const historyPagination = reactive({ current: 1, size: 10, total: 0 })

    const loadHistory = () => {
        historyLoading.value = true
        getEmotionDiaryPage({ current: historyPagination.current, size: historyPagination.size }).then((res) => {
            historyList.value = res?.records || []
            historyPagination.total = res?.total || 0
        }).catch(() => {
            ElMessage.error('日记列表加载失败')
        }).finally(() => {
            historyLoading.value = false
        })
    }

    const handleTabChange = (tab) => {
        if (tab === 'history' && !historyLoaded.value) {
            historyLoaded.value = true
            loadHistory()
        }
    }

    const handleHistoryPageChange = (page) => {
        historyPagination.current = page
        loadHistory()
    }

    // 历史详情
    const historyDetailVisible = ref(false)
    const currentHistory = ref(null)
    const openHistoryDetail = (diary) => {
        currentHistory.value = diary
        historyDetailVisible.value = true
    }

    const iconUrl = new URL('@/assets/images/like.png', import.meta.url).href
</script>
<style lang="scss" scoped>
    .emotionDiary-container {
    background: linear-gradient(135deg, #fafbfc 0%, #f7f9fc 50%, #f2f6fa 100%);
    .header-section {
        background: linear-gradient(135deg, #7ED321 0%, #F5A623 100%);
        color: white;
        padding: 48px;
        .header-content {
            display: flex;
            align-items: center;
            gap: 12px;
        }
    }
    .content {
        margin: 0 auto;
        width: 980px;
        padding: 20px;
        .diary-card {
            margin-bottom: 20px;
            background: white;
            border-radius: 10px;
            padding: 20px;
            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);
            .title {
                margin-bottom: 20px;
                font-size: 25px;
                font-weight: 600;
                color: #374151;
            }
            .section {
                margin-bottom: 20px;
                p {
                    font-size: 15px;
                    color: #6B7280;
                    margin-bottom: 15px;
                }
            }
            .emotion-grid {
                display: flex;
                flex-wrap: wrap;
                gap: 10px;
                .emotion-card {
                    padding: 15px;
                    border: 2px solid #E5E7EB;
                    border-radius: 15px;
                    text-align: center;
                    cursor: pointer;
                    background: #F9FAFB;
                    .emotion-name {
                        margin-top: 10px;
                        padding: 0 75px;
                        color: #374151;
                    }
                    &.selected {
                        border-color: #7ED321;
                        background: #F0FDF4;
                        transform: translateY(-3px);
                    }
                }
            }
            .detail-form {
                .form-label {
                    margin: 10px 0;
                    color: #374151;
                }
                .life-indicators {
                    display: flex;
                    gap: 20px;
                    .indicator-group {
                        flex: 1;
                    }
                }
                .action-buttons {
                    margin-top: 40px
                }
            }
            .history-empty {
                padding: 40px 0;
                text-align: center;
                color: #9CA3AF;
            }
            .history-item {
                padding: 16px;
                border: 1px solid #E5E7EB;
                border-radius: 10px;
                margin-bottom: 12px;
                cursor: pointer;
                transition: all 0.2s;
                &:hover {
                    border-color: #7ED321;
                    background: #F0FDF4;
                }
                .history-head {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    margin-bottom: 10px;
                    .history-date {
                        font-size: 16px;
                        font-weight: 600;
                        color: #374151;
                    }
                }
                .history-body {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    .history-indicators {
                        display: flex;
                        gap: 12px;
                        color: #6B7280;
                        font-size: 14px;
                    }
                }
                .history-content {
                    margin-top: 10px;
                    color: #6B7280;
                    font-size: 14px;
                    line-height: 1.6;
                    display: -webkit-box;
                    -webkit-line-clamp: 2;
                    -webkit-box-orient: vertical;
                    overflow: hidden;
                }
            }
        }
    }
    .history-detail {
        .detail-analysis-title {
            margin: 20px 0 12px;
            font-size: 16px;
            font-weight: 600;
            color: #374151;
        }
    }

    // 提交等待弹窗
    .waiting-body {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 12px 0 4px;

        .waiting-spinner {
            width: 36px;
            height: 36px;
            border: 3px solid #E5E7EB;
            border-top-color: #7ED321;
            border-radius: 50%;
            animation: waiting-spin 0.9s linear infinite;
            margin-bottom: 16px;
        }

        .waiting-title {
            font-size: 16px;
            font-weight: 600;
            color: #374151;
            margin-bottom: 6px;
        }

        .waiting-subtitle {
            font-size: 13px;
            color: #9CA3AF;
        }
    }

    @keyframes waiting-spin {
        to {
            transform: rotate(360deg);
        }
    }
}
</style>

// 危机警示模态框样式（ElMessageBox 挂 body，须为全局样式）
<style lang="scss">
.crisis-message-box {
    border-top: 4px solid #F56C6C;

    .el-message-box__title {
        color: #F56C6C;
        font-weight: 600;
    }

    .el-message-box__message {
        color: #C45656;
        font-size: 15px;
        line-height: 1.8;
    }
}
</style>

