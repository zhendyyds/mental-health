<template>
    <div class="consultation-container">
        <div class="sidebar">
            <!-- AI助手信息 -->
             <div class="ai-assistant-info">
                <div class="breathing-circle">
                    <el-image :src="iconUrl" style="width: 25px;height:25px" alt="AI助手" />
                </div>
                <h3 class="assistant-name">心理助手小暖</h3>
                <div class="online-status">
                    <div class="status-dot"></div>
                    在线服务中
                </div>
             </div>
             <!-- 情绪花园 -->
             <div class="emotion-garden">
                <div class="garden-header">
                    <div class="garden-title"> 情绪花园 </div>
                    <div class="garden-analyzed-at">{{ formatAnalyzedAt(currentEmotion.analyzedAt) }}</div>
                </div>
                <div class="emotion-info" v-loading="emotionLoading">
                    <div class="emotion-name">{{ hasCurrentEmotion ? currentEmotion.primaryEmotion : '暂无可用分析' }}</div>
                    <div class="emotion-score" v-if="hasCurrentEmotion">{{ currentEmotion.emotionScore }}</div>
                </div>
                <p class="analysis-state" role="status">{{ emotionAnalysisLabel(currentEmotion, emotionLoading) }}</p>
                <div class="warm-tips" v-if="hasCurrentEmotion">
                    <div class="emotion-status-text">
                        <span class="status-label">今天感觉</span>
                        <span class="status-emotion">{{ currentEmotion.isNegative ? '需要关注' : '很不错' }}</span>
                    </div>
                    <div class="emotion-intensity">
                        <span class="intensity-dots">
                            <span v-for="dot in 3" :key="dot" class="dot" :class="{'active': getIntensityClass(currentEmotion.emotionScore) >= dot}"></span>
                        </span>
                        <span class="intensity-text">
                            {{ getRiskText(currentEmotion.riskLevel) }}
                        </span>
                    </div>
                    <!-- 温暖建议卡片 -->
                     <div class="warm-suggestion" v-if="currentEmotion.suggestion">
                        <div class="suggestion-icon">💝</div>
                        <div class="suggestion-content">
                            <div class="suggestion-title">给你的小建议</div>
                            <div class="suggestion-text">{{ currentEmotion.suggestion }}</div>
                        </div>
                     </div>
                     <!-- 治愈行动 -->
                      <div class="healing-actions" v-if="currentEmotion.improvementSuggestions.length > 0">
                        <div class="actions-title">治愈小行动</div>
                        <div class="actions-list">
                            <div v-for="action in currentEmotion.improvementSuggestions" :key="action" class="action-item">
                                <div class="action-icon">✨</div>
                                <div class="action-text">{{ action }}</div>
                            </div>
                        </div>
                      </div>
                      <!-- 风险提示 -->
                    <div class="risk-notice" v-if="currentEmotion.isNegative && currentEmotion.riskLevel > 1">
                        <div class="notice-icon">🤗</div>
                        <div class="notice-content">
                            <div class="notice-title">温馨提示</div>
                            <div class="notice-text">{{ currentEmotion.riskDescription }}</div>
                        </div>
                    </div>
                      <!-- 危机干预静态保底卡片（纯静态渲染，不依赖LLM输出） -->
                    <div class="risk-notice crisis-notice" v-if="currentEmotion.riskLevel === 3">
                        <div class="notice-icon">📞</div>
                        <div class="notice-content">
                            <div class="notice-title">专业支持</div>
                            <div class="notice-text">如果您正处于较大的痛苦中，请及时寻求专业帮助：全国心理援助热线 12356（24小时）。您不必独自承受这一切。</div>
                        </div>
                    </div>
                </div>
             </div>
             <!-- 会话列表 -->
             <div class="session-history">
                <h4 class="section-title">会话列表</h4>
                <div class="session-list">
                    <div v-for="session in sessionList" :key="session.id" @click="handleSessionClick(session)" class="session-item">
                        <div class="session-info">
                            <div class="session-title">
                                <span>{{ session.sessionTitle }}</span>
                                <div class="session-meta">
                                    <span class="session-time">{{ session.startedAt }}</span>
                                </div>
                                <div class="session-preview">
                                    {{ session.lastMessageContent }}
                                </div>
                                <div class="session-stats">
                                    <span v-if="session.id === activeReplySessionId" class="generating-chip">生成中</span>
                                    <span>
                                        <el-icon>
                                            <ChatRound />
                                        </el-icon>
                                        {{ session.messageCount || 0 }}
                                    </span>
                                </div>
                            </div>
                            <div class="session-actions">
                                <!-- .stop 阻止冒泡到会话条目的 click：否则删除 B 会先切换视图到 B，
                                     删除响应回来时误判"删的是当前会话"而跳回新对话 -->
                                <el-button text type="danger" size="mini" @click.stop="handleDeleteSession(session.id)">
                                    <el-icon>
                                        <DeleteFilled />
                                    </el-icon>
                                </el-button>
                            </div>
                        </div>
                    </div>
                </div>
             </div>
        </div>
        <div class="chat-main">
            <div class="chat-header">
                <div class="header-left">
                    <div class="chat-avatar">
                        <el-image :src="iconUrl1" style="width: 30px;height: 30px" />
                    </div>
                    <div class="chat-info">
                        <h2>心理助手小暖</h2>
                        <p>您的贴心AI心理健康助手</p>
                    </div>
                </div>
                <el-button circle @click="createNewFrontendSession" title="新建会话">
                    <el-icon>
                        <Plus />
                    </el-icon>
                </el-button>
            </div>
            <!-- 聊天消息区域 -->
            <div class="chat-messages">
                <!-- 欢迎用语 -->
                <div class="message-item ai-message" v-if="messages.length === 0">
                    <div class="message-avatar">
                        <el-image :src="iconUrl" style="width: 18px;height: 18px" />
                    </div>
                    <div class="message-content">
                        <div class="message-bubble">
                            <p>您好！我是小暖，您的AI心理健康助手。很高兴陪伴您，为您提供温暖的心理支持。请告诉我，今天您感觉怎么样？有什么想要分享的吗？</p>
                        </div>
                        <div class="message-time">刚刚</div>
                    </div>
                </div>
                <!-- 消息列表 -->
                <div v-for="msg in messages" :key="msg.id" class="message-item" :class="msg.senderType === 1 ?  'user-message' : 'ai-message'">
                    <div class="message-avatar">
                        <el-image v-if="msg.senderType === 1" style="width: 18px; height:18px" :src="iconUrl2"></el-image>
                        <el-image v-if="msg.senderType === 2" style="width: 18px; height:18px" :src="iconUrl"></el-image>
                    </div>
                    <div class="message-content">
                        <div class="message-bubble">
                            <!-- AI正在思考中 -->
                            <div v-if="msg.senderType === 2 && isAiTyping && !msg.content && !msg.errorMessage" class="typing-indicator">
                                <div class="typing-dot"></div>
                                <div class="typing-dot"></div>
                                <div class="typing-dot"></div>
                            </div>
                            <!-- AI正常返回消息 -->
                            <MarkdownRenderer v-else-if="msg.senderType === 2 && msg.content" :content="msg.content" :is-ai-message="true" />
                            <p v-else-if="msg.content" class="user-text">{{ msg.content }}</p>
                            <div v-if="msg.errorMessage || msg.replyStatusMessage" class="error-message" role="status">
                                <p>{{ msg.senderType === 1 ? 'AI回复状态：' : msg.saved === false ? '未保存：' : msg.saved === true ? '当前片段已保存：' : '保存状态待确认：' }}{{ msg.errorMessage || msg.replyStatusMessage }}<span v-if="msg.requestId">（请求 ID：{{ msg.requestId }}）</span></p>
                            </div>
                        </div>
                        <div class="message-time">{{ msg.senderType === 2 && isAiTyping ? '正在输入中...' : msg.createdAt }}</div>
                    </div>
                </div>
            </div>
            <!-- 消息输入区域 -->
            <div class="chat-input">
                <div class="input-container">
                    <el-input
                        v-model="userMessage"
                        placeholder="请输入您想要分享的内容..."
                        type="textarea"
                        :rows="3"
                        :disabled="isAiTyping"
                        @keydown="handleKeyDown"
                        class="message-input"
                        clearable />
                        <div class="input-footer">
                            <span>按Enter发送，Shift+Enter换行</span>
                            <span>{{ userMessage.length }}/500</span>
                        </div>
                </div>
                <!-- 生成期间切换为停止按钮：停止是显式命令（ADR 0009），残句落库后返回 -->
                <el-button v-if="isAiTyping" type="warning" class="send-btn stop-btn" title="停止生成" @click="handleStop">
                    <el-icon>
                        <VideoPause />
                    </el-icon>
                </el-button>
                <el-button v-else :disabled="!userMessage.trim() || userMessage.length > 500" type="primary" class="send-btn" @click="sendMessage">
                    <el-icon>
                        <Promotion />
                    </el-icon>
                </el-button>
            </div>
        </div>
    </div>
</template>
<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { startSession, getSessionList, deleteSession, getSessionDetail, getSessionEmotion, stopActiveReply, getActiveReply } from '@/api/frontend'
import { ElMessage } from 'element-plus'
import { ChatRound, DeleteFilled, VideoPause } from '@element-plus/icons-vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { applyReplyEvent, applyReplyFailure, createClientRequestId, createReplyRecoveryBaseline, reconcileReplyHistory, restoreReplyHistory } from '@/utils/replyStream'
import { emptyEmotionAnalysis, normalizeEmotionAnalysis, hasEmotionAnalysis, emotionAnalysisLabel, emotionStatusFromError } from '@/utils/emotionAnalysisState'

const iconUrl = new URL('@/assets/images/robot-fill.png', import.meta.url).href
const iconUrl1 = new URL('@/assets/images/like.png', import.meta.url).href
const iconUrl2 = new URL('@/assets/images/users.png', import.meta.url).href

// 新建会话
const createNewFrontendSession = () => {
    // 导航自由（ADR 0009）：新建会话不再被生成状态拦截，
    // 仅"发起新回复"受单活跃流约束（发送时守卫 + 后端 409）
    // 创建一个新的会话对象
    const newSession = {
        sessionId: `temp_${Date.now()}`,
        status: 'TEMP',
        sessionTitle: '新对话'
    }
    currentSession.value = newSession
    emotionRequestSequence++
    emotionSessionId = null
    currentEmotion.value = emptyEmotionAnalysis()
    emotionLoading.value = false
    // 清空消息区：新会话不携带上一个会话的历史消息（否则历史消息会"串台"到新会话视图）
    messages.value = []
}

// 定义一个当前会话对象
const currentSession = ref(null)
const sessionList = ref([])

// 定义对话消息
const messages = ref([])
// 定义用户输入消息
const userMessage = ref('')
// 定义AI助手是否正在输入（本用户存在活跃回复，含本标签页发起与刷新/其他标签页恢复）
const isAiTyping = ref(false)
// 活跃回复所属会话（数据库数字 id）；null 表示无活跃回复
const activeReplySessionId = ref(null)
// 本标签页发起的流式连接的 AbortController（停止/删除会话时中止本地接收）
const activeCtrl = ref(null)
// 停止/删除引发的主动中止：吞掉 abort 触发的 onerror，避免误报"AI回复失败"
const deliberateStop = ref(false)
// 会话数字 id → 本标签页流式中的 AI 消息对象：切换会话后切回可续显（对象仍被 onmessage 更新）
const inflightAiMessages = new Map()
const unconfirmedReplies = new Map()
// 活跃状态轮询定时器：仅用于本标签页未持有流的场景（刷新恢复），完成后刷新视图
let activePollTimer = null

// 情绪花园
const currentEmotion = ref(emptyEmotionAnalysis())
const hasCurrentEmotion = computed(() => hasEmotionAnalysis(currentEmotion.value))
let emotionSessionId = null
let emotionRequestSequence = 0

const loadSessionEmotion = (sessionId) => {
   // 确保sessionID格式正确
    const id = sessionId.toString().startsWith('session_') ? sessionId : `session_${sessionId}`
    if (currentSession.value?.sessionId !== id) return
    if (emotionSessionId !== id) currentEmotion.value = emptyEmotionAnalysis()
    emotionSessionId = id
    const requestSequence = ++emotionRequestSequence
    const isCurrentRequest = () => requestSequence === emotionRequestSequence && currentSession.value?.sessionId === id
    emotionLoading.value = true
    getSessionEmotion(id).then(res => {
        if (isCurrentRequest()) currentEmotion.value = normalizeEmotionAnalysis(res)
    }).catch((error) => {
        if (!isCurrentRequest()) return
        const cached = hasEmotionAnalysis(currentEmotion.value)
        currentEmotion.value = {
            ...currentEmotion.value,
            analysisStatus: emotionStatusFromError(error),
            cached,
            stale: cached
        }
    }).finally(() => {
        if (isCurrentRequest()) emotionLoading.value = false
    })
}

// 分析中loading态
const emotionLoading = ref(false)

// 情绪快照时效标注
const formatAnalyzedAt = (t) => {
    if (!t) return '暂无分析'
    const d = new Date(t)
    if (isNaN(d.getTime())) return '暂无分析'
    const hh = String(d.getHours()).padStart(2, '0')
    const mm = String(d.getMinutes()).padStart(2, '0')
    return `分析于 ${hh}:${mm}`
}

const getIntensityClass = (score) => {
    if (score >= 61) {
        return 3
    }
    if (score >= 31) {
        return 2
    }
    return 1
}

const getRiskText = (level) => {
    switch (level) {
        case 0:
            return '正常'
        case 1:
            return '关注'
        case 2:
            return '预警'
        case 3:
            return '危机'
        default:
            return '正常'
    }
}

// 定义处理键盘事件
const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
        e.preventDefault()
        if (userMessage.value.length <= 500) sendMessage()
    }
}

// 用户发送消息
const sendMessage = () => {
    if (!userMessage.value.trim()) return

    if (isAiTyping.value) {
        ElMessage.error('您有一条AI回复正在生成中，请等待完成或点击停止')
        return
    }

    const message = userMessage.value.trim()
    userMessage.value = ''

    console.log('currentSession', currentSession.value)
    
    // 如果没有会话或者是临时会话，就需要创建一个新的会话
    if (currentSession.value.status === 'TEMP') {
        //已有会话: currentSession.value：{sessionId: 'session_42', status: 'ACTIVE', sessionTitle: '心理助手小暖 - 2026/9/16 11:26:37'}
        //新建会话: currentSession.value：{sessionId: 'temp_1789548948131', status: 'TEMP', sessionTitle: '新对话'}
        //sessionId不同是因为未落库
        // startNewSession之后，currentSession.value 会被更新为正式会话，sessionId 也会被更新
       startNewSession(message)
    } else {
        // 继续现有会话 
        messages.value.push({
            id: Date.now(),
            senderType: 1,
            content: message,
            createAt: new Date().toISOString()
        })
        startAIResponse(currentSession.value.sessionId, message)
    }
}

const startNewSession = (message) => {
    // 构建会话参数
    const sessionParams = {
        initialMessage: message
    }
    if (currentSession.value.sessionTitle === '新对话') {
        sessionParams.sessionTitle = `心理助手小暖 - ${new Date().toLocaleString()}`
    } else {
        // 如果历史会话记录
        sessionParams.sessionTitle = currentSession.value.sessionTitle
    }
    // 调用后端接口创建新会话
    startSession(sessionParams).then(res => {
       console.log('startSession', res)
       // 将后端返回的数据转为前端会话格式
       //
       const sessionData = {
            sessionId: res.sessionId,
            status: res.status,
            sessionTitle: sessionParams.sessionTitle
       }
       // 如果当前是临时会话，更新数据
       if (currentSession.value && currentSession.value.status === 'TEMP') {
            // 更新为正式会话 
            Object.assign(currentSession.value, sessionData)
       } else {
            // 否则，创建一个新的会话
            currentSession.value = sessionData
       }
       // 更新会话列表
       getSessionPage()

       // 添加初始用户消息
       messages.value.push({
        id: Date.now(),
        senderType: 1,
        content: message,
        createAt: new Date().toISOString()
       })

       // 开始流式对话
       startAIResponse(currentSession.value.sessionId, message)
    }).catch(error => {
        ElMessage.error(error?.response?.data?.msg || '会话创建失败，请稍后重试')
        if (!userMessage.value) userMessage.value = message
    })
}

const validateStreamResponse = async (response) => {
    const contentType = (response.headers.get('Content-Type') || '').toLowerCase()
    if (response.ok && contentType.startsWith('text/event-stream')) return

    let message = response.ok ? '服务器返回非流式数据' : `请求失败（HTTP ${response.status}）`
    let payload
    if (contentType.includes('application/json')) {
        try {
            payload = await response.json()
            message = payload.msg || message
        } catch {
            // 无法解析响应时仍显示 HTTP 状态或非流式响应提示
        }
    }
    const error = new Error(message)
    error.isStreamRejection = true
    error.saved = typeof payload?.data?.saved === 'boolean' ? payload.data.saved : payload?.data?.status === 'UNKNOWN' ? undefined : false
    error.requestId = payload?.data?.requestId
    error.requestStatus = payload?.data?.status
    throw error
}

const startAIResponse = (sessionId, userMessage) => {
    // 防止重复发送（输入框禁用为第一道锁，此处防御状态漂移与双开标签页）
    if (isAiTyping.value) {
        ElMessage.error('您有一条AI回复正在生成中，请等待完成或点击停止')
        return
    }
    
    let clientRequestId
    try { clientRequestId = createClientRequestId() }
    catch (error) { ElMessage.error(error.message); return }
    isAiTyping.value = true
    deliberateStop.value = false
    const numericId = Number(String(sessionId).replace('session_', ''))
    activeReplySessionId.value = numericId

    const aiMessage = reactive({
        id: `ai_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        senderType: 2,
        clientRequestId,
        content: '',
        recoveryBaseline: createReplyRecoveryBaseline(messages.value, { sessionId: numericId, question: userMessage }),
        createAt: new Date().toISOString()
    })
    // 对话框显示为AI头像，以及空白文本内容
    messages.value.push(aiMessage)
    // 登记流式中的消息：切换会话后切回时续显（对象仍被下方闭包更新）
    inflightAiMessages.set(numericId, aiMessage)

    // 调用流式接口
    const ctrl = new AbortController() // 用来中止fetch请求
    let settled = false
    activeCtrl.value = ctrl
    fetchEventSource('/api/psychological-chat/stream', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Token': localStorage.getItem('token'),
            'Accept': 'text/event-stream'
        },
        body: JSON.stringify({
            sessionId,
            userMessage,
            clientRequestId
        }),
        signal: ctrl.signal,
        // 禁用库的 visibilitychange 机制：切走标签页时中断连接、切回时用原 body 重新 POST，
        // 导致同一条消息重复入库并触发多次 AI 回复
        openWhenHidden: true,
        //`onopen` ：只在 连接刚建立、收到 HTTP 响应头的那一刻 触发一次。此时 还看不到任何数据分片 （那要等`onmessage` ）。
        onopen: validateStreamResponse,
        //`onmessage` ：之后每收到一个事件（content 分片或 done）触发一次。
        onmessage: (event) => {
            const outcome = applyReplyEvent(aiMessage, event)
            if (outcome === 'done') {
                settled = true
                unconfirmedReplies.delete(numericId)
                // 后端契约（ADR 0009）：落库与槽位释放先于 done 事件，收到即可续聊
                inflightAiMessages.delete(numericId)
                isAiTyping.value = false
                activeReplySessionId.value = null
                stopActivePolling()
                ctrl.abort()
                // 情绪分析的数据侧仍绑流所属会话（ADR 0008）；但卡片只在正在查看该会话时刷新，
                // 否则侧栏会显示与当前所看会话错位的情绪数据——切回时懒计算（ADR 0004）自动重算
                if (currentSession.value && currentSession.value.sessionId === sessionId) {
                    refreshSessionView(numericId)
                }
                getSessionPage()
                return
            }
            if (outcome === 'error') {
                settled = true
                handleError(aiMessage, { message: aiMessage.errorMessage })
                inflightAiMessages.delete(numericId)
                activeReplySessionId.value = null
                stopActivePolling()
                ctrl.abort()
                // 守卫错误说明服务器上确有活跃回复（可能来自其他标签页）：向服务器状态对齐
                restoreActiveReply()
            }
        },
        onclose: () => {
            if (!settled && !ctrl.signal.aborted) {
                throw new Error('AI回复连接提前结束，请刷新会话查看结果')
            }
        },
        onerror: (err) => {
            // 禁止自动重发 POST；由下方 catch 统一收尾，避免用户消息重复入库
            throw err
        }
    }).catch((err) => {
        if (settled || ctrl.signal.aborted || deliberateStop.value) return
        settled = true
        recoverDisconnectedReply(numericId, aiMessage, true, err)
    })

}

const handleError = (targetMessage, error) => {
    applyReplyFailure(targetMessage, error)
    isAiTyping.value = false
    ElMessage.error(targetMessage.errorMessage)
}

const getSessionPage = () => {
    getSessionList({
        pageNum: 1,
        pageSize: 10
    }).then(res => {
        console.log(res)

        sessionList.value = res.records
    }).catch(error => {
        ElMessage.error(error?.response?.data?.msg || '会话列表加载失败，请稍后重试')
    })
}

// 重连观看（ADR 0010）：向服务器接回活跃回复的观察流——先收已生成半截的回放，再续收实时片段。
// 适用于刷新后点入生成中会话、或本标签页未持有流的切回场景（事件结构与 /stream 完全一致）；
// 本标签页自己发起的流走 inflightAiMessages 本地续显，无需 attach。
// 返回 AI 气泡对象供调用方并入消息列表；写回目标必须是该闭包对象（防串台，同 startAIResponse）
const attachActiveReply = (dbSessionId, history = messages.value, recoveryBaseline, recoveryPartial, requestMetadata = {}) => {
    stopActivePolling()
    //一次性返回之前所有历史消息，再续收实时片段
    const aiMessage = reactive({
        id: `ai_attach_${dbSessionId}_${Date.now()}`,
        senderType: 2,
        clientRequestId: requestMetadata.clientRequestId,
        requestId: requestMetadata.requestId,
        content: '',
        recoveryBaseline: recoveryBaseline || createReplyRecoveryBaseline(history, { sessionId: dbSessionId, attach: true }),
        recoveryPartial,
        createdAt: ''
    })
    // 登记流式中的消息：attach 期间切走再切回可续显（对象仍被下方 onmessage 更新）
    inflightAiMessages.set(dbSessionId, aiMessage)

    const ctrl = new AbortController()
    let settled = false
    activeCtrl.value = ctrl
    deliberateStop.value = false

    fetchEventSource(`/api/psychological-chat/stream/attach/${dbSessionId}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Token': localStorage.getItem('token'),
            'Accept': 'text/event-stream'
        },
        signal: ctrl.signal,
        openWhenHidden: true,
        onopen: validateStreamResponse,
        onmessage: (event) => {
            const outcome = applyReplyEvent(aiMessage, event)
            if (outcome === 'done') {
                settled = true
                unconfirmedReplies.delete(dbSessionId)
                // 与 /stream 的 done 同契约（ADR 0009）：落库与槽位释放先于 done，收到即可续聊
                inflightAiMessages.delete(dbSessionId)
                isAiTyping.value = false
                activeReplySessionId.value = null
                stopActivePolling()
                ctrl.abort()
                if (currentSession.value && currentSession.value.sessionId === 'session_' + dbSessionId) {
                    refreshSessionView(dbSessionId)
                }
                getSessionPage()
                return
            }
            if (outcome === 'error') {
                settled = true
                // 404 扑空（回复恰在 attach 前完成）：清本地占位，回源拉库（可能已含完整回复）
                inflightAiMessages.delete(dbSessionId)
                isAiTyping.value = false
                activeReplySessionId.value = null
                ctrl.abort()
                if (aiMessage.errorCode === '404' && currentSession.value?.sessionId === 'session_' + dbSessionId) {
                    // attach 扑空只说明观察任务已结束，不证明原回复未保存。
                    aiMessage.saved = undefined
                    if (aiMessage.content) unconfirmedReplies.set(dbSessionId, aiMessage)
                    refreshSessionView(dbSessionId)
                } else {
                    handleError(aiMessage, { message: aiMessage.errorMessage })
                }
                // 若活跃回复其实在别的会话（客户端状态漂移），向服务器状态对齐
                restoreActiveReply()
            }
        },
        onclose: () => {
            if (!settled && !ctrl.signal.aborted) {
                throw new Error('AI回复连接提前结束，请刷新会话查看结果')
            }
        },
        onerror: (err) => {
            throw err
        }
    }).catch((err) => {
        if (settled || ctrl.signal.aborted || deliberateStop.value) return
        settled = true
        recoverDisconnectedReply(dbSessionId, aiMessage, false, err)
    })

    return aiMessage
}

// 获取会话数据
const handleSessionClick = (session) => {
    // 导航自由（ADR 0009）：切换会话不再被生成状态拦截
    console.log(session, 'session')
    // 点击会话时，获取会话详情
    getSessionDetail(session.id).then(res => {
        console.log('getSessionDetail', res)
        const inflight = inflightAiMessages.get(session.id)
        const list = restoreReplyHistory(res || [], inflight)
        // 流式中的回复仍属其会话：本标签页发起的流切回后续显（闭包对象仍被 onmessage 更新）；
        // 刷新/其他来源恢复的活跃回复走重连观看（ADR 0010）：attach 回放半截并续收实时片段
        
        //有一个活跃会话（AI正在回答），一个非活跃会话，现在来回切换历史会话
        console.log('inflight', inflight)
        // console.log打印结果：
        // 切为活跃会话：inflight Proxy(Object) {id: 'ai_1789545667136_yb7ewmb25', senderType: 2, content: '\n哇～听到你说“好吃”我就忍不住要流口水啦！🤤 豪赤的魅力果然无法', createAt: '2026-09-16T08:01:07.136Z'}
        // 切为非活跃会话（inflightAiMessages中存储的是活跃会话对象）：inflight undefined 
        // 切为活跃会话（content内容追加，详见startAIResponse的onmessage函数）：Proxy(Object) {id: 'ai_1789545667136_yb7ewmb25', senderType: 2, content: '\n哇～听到你说“好吃”我就忍不住要流口水啦！🤤 豪赤的魅力果然无法抵挡！你是不是已经沉浸在美食带来的幸福感中了？', createAt: '2026-09-16T08:01:07.136Z'}
        // 切为非活跃会话 同理
        // 切为活跃会话：inflight undefined 
        
        console.log('messages', messages.value)
        if (inflight) {
            //情况1：在有活跃会话的前提下，切换历史会话，又切回活跃会话
            //注意，list和inflight它们的字段并不一致，这是因为inflight还未落库
            //这是list的字段，inflight字段上面有
            //{
            //     "id": 289,
            //     "sessionId": 42,
            //     "senderType": 1,
            //     "senderTypeDesc": "用户",
            //     "messageType": 1,
            //     "messageTypeDesc": "文本",
            //     "content": "好吃",
            //     "emotionTag": null,
            //     "aiModel": null,
            //     "createdAt": "2026-09-16T16:01:07",
            //     "contentLength": 2
            // }
            //messages.value 是一个数组，如下所示：
            // ...
            // 24: {id: 288, sessionId: 42, senderType: 1, senderTypeDesc: '用户', messageType: 1, …}
            // 25: {id: 289, sessionId: 42, senderType: 1, senderTypeDesc: '用户', messageType: 1, …}
            // 26: Proxy(Object) {id: 'ai_1789545667136_yb7ewmb25', senderType: 2, content: '\n哇～听到你说“好吃”我就忍不住要流口水啦！🤤 豪赤的魅力果然无法抵挡！你是不是已经沉浸在美食带来…享受美食的同时也要照顾好自己，吃出健康才是长久之计～你最近有没有发现什么新美食？快和我分享吧！😉', createAt: '2026-09-16T08:01:07.136Z'}

            messages.value = [...list, inflight]
        } else if (isAiTyping.value && activeReplySessionId.value === session.id) {
            // 情况2：在有活跃会话的前提下，关闭标签页，再重新打开（换句话说，能触发 OnMounted）
            // 需要配合 OnMounted 中的 restoreActiveReply() 理解
            messages.value = [...list, attachActiveReply(session.id, list)]
        } else {
            //情况3：没有活跃会话
            messages.value = reconcileSessionHistory(session.id, list)
        }
    }).catch(err => {
        // 请求失败：清空消息区，避免残留上一会话的历史消息（"串台"）
        messages.value = []
        ElMessage.error('加载会话消息失败，请稍后重试')
    })
    // 更新当前会话对象数据，因为会话切换了，session.id也会切换
    const sessionData = {
        sessionId: "session_" + session.id,
        status: 'ACTIVE',
        sessionTitle: session.sessionTitle
    }
    currentSession.value = sessionData
    loadSessionEmotion(session.id)
}

const handleDeleteSession = (sessionId) => {
    // 删除优先（ADR 0009）：被删会话若有活跃回复，后端终止生成且残句不落库（会话退出业务流程）
    deleteSession(sessionId).then(res => {
        ElMessage.success('删除成功')
        unconfirmedReplies.delete(sessionId)
        if (activeReplySessionId.value === sessionId) {
            // 本标签页正在接收该会话的流：后端已终止，主动中止本地接收
            deliberateStop.value = true
            activeCtrl.value?.abort()
            inflightAiMessages.delete(sessionId)
            isAiTyping.value = false
            activeReplySessionId.value = null
            stopActivePolling()
        }
        // 删的是当前会话 → 清空聊天区，回到新对话状态
        if (currentSession.value && currentSession.value.sessionId === 'session_' + sessionId) {
            messages.value = []
            createNewFrontendSession()
        }
        getSessionPage()
    })
}

// 显式停止（ADR 0009）：停止是命令而非断开连接；后端落残句并释放槽位后返回，
// 随后 done 事件/abort 收尾本地连接，正在查看该会话时刷新出残句
const handleStop = async () => {
    const sid = activeReplySessionId.value
    const reply = inflightAiMessages.get(sid)
    try {
        await stopActiveReply()
        // 停止命令成功不代表残句保存成功。有观察流时等待 done/error，
        // 提前 abort 或拉库会丢掉保存失败事件及当前未保存片段。
        if (!reply) {
            await restoreActiveReply()
            if (sid != null && currentSession.value?.sessionId === 'session_' + sid) refreshSessionView(sid)
            getSessionPage()
        }
    } catch (error) {
        if (error?.stopping === true) {
            ElMessage.info('停止已提交，正在确认结果')
            return
        }
        if (reply) handleError(reply, error)
        else ElMessage.error(error?.message || '停止失败，请稍后查询回复状态')
        await restoreActiveReply()
    }
}

// 活跃回复状态恢复（ADR 0009）：刷新后向服务器对齐"有一条回复正在生成"的事实，
// 恢复输入锁定态与列表徽标；本地连接异常（done 丢失）时也以此向服务器对齐
const restoreActiveReply = async () => {
    try {
        const active = await getActiveReply()// ← 向服务器查询真实状态
        if (active && active.sessionId) {// ← 服务器说"有"才锁
            isAiTyping.value = true
            activeReplySessionId.value = active.sessionId
            startActivePolling()
        } else {
            isAiTyping.value = false
            activeReplySessionId.value = null
            stopActivePolling()
        }
        return active
    } catch {
        // 未登录或网络异常：忽略，界面按无活跃回复处理
    }
}

// 查询任务后只 attach 一次。attach 再断开则回退轮询，避免网络故障触发循环重连。
const recoverDisconnectedReply = async (dbSessionId, reply, allowAttach, error) => {
    if (error?.isStreamRejection) {
        inflightAiMessages.delete(dbSessionId)
        handleError(reply, error)
        restoreActiveReply()
        return
    }
    applyReplyFailure(reply, { message: '连接中断，正在确认回复状态，请勿重复发送' })
    unconfirmedReplies.set(dbSessionId, reply)
    inflightAiMessages.delete(dbSessionId)
    const active = await restoreActiveReply()
    if (active?.sessionId === dbSessionId && allowAttach && currentSession.value?.sessionId === 'session_' + dbSessionId) {
        stopActivePolling()
        const replacement = attachActiveReply(dbSessionId, messages.value, reply.recoveryBaseline, reply.content, reply)
        unconfirmedReplies.set(dbSessionId, replacement)
        // attach 从空白区接受全量回放，替换旧半句而非追加到旧内容。
        messages.value = messages.value.map(message => message === reply ? replacement : message)
    } else if (active !== undefined && !active?.sessionId && currentSession.value?.sessionId === 'session_' + dbSessionId) {
        refreshSessionView(dbSessionId)
    }
}

const reconcileSessionHistory = (dbSessionId, history) => {
    const reply = unconfirmedReplies.get(dbSessionId)
    history = restoreReplyHistory(history, reply)
    if (!reply) return history
    const recovered = reconcileReplyHistory(history, reply)
    if (recovered.confirmed) unconfirmedReplies.delete(dbSessionId)
    else if (reply.content && reply.saved === undefined) applyReplyFailure(reply, { message: '连接已结束，尚未确认本轮回复已保存，请保留当前内容' })
    return recovered.messages
}

// 刷新指定会话的视图与情绪卡片（停止/轮询完成后的数据回填）
const refreshSessionView = (dbSessionId) => {
    getSessionDetail(dbSessionId).then(res => {
        if (currentSession.value?.sessionId === 'session_' + dbSessionId && !inflightAiMessages.has(dbSessionId)) messages.value = reconcileSessionHistory(dbSessionId, res || [])
    }).catch(error => ElMessage.error(error?.message || '回复记录加载失败，请稍后刷新'))
    loadSessionEmotion(dbSessionId)
}

// 轮询活跃状态：仅本标签页未持有流的场景需要（刷新恢复/其他标签页发起）。
// 本标签页自己发起的流由 done 事件收尾，无需轮询。
const startActivePolling = () => {
    if (activePollTimer) return
    activePollTimer = setInterval(async () => {
        try {
            const active = await getActiveReply()
            // 已建立本地观察流时由终止事件决定保存状态，避免在途轮询抢先拉库抹掉失败片段。
            if (inflightAiMessages.has(activeReplySessionId.value)) return
            if (!active || !active.sessionId) {
                const sid = activeReplySessionId.value
                isAiTyping.value = false
                activeReplySessionId.value = null
                stopActivePolling()
                if (sid != null && currentSession.value?.sessionId === 'session_' + sid) {
                    refreshSessionView(sid)
                }
                getSessionPage()
            }
        } catch {
            // 查询失败：下个周期重试
        }
    }, 3000)
}

const stopActivePolling = () => {
    if (activePollTimer) {
        clearInterval(activePollTimer)
        activePollTimer = null
    }
}

onMounted(() => {
    // 初始化时获取会话列表
    getSessionPage()
    // 初始化时创建一个新会话
    createNewFrontendSession()
    // 恢复活跃回复状态（刷新后服务器上可能仍有生成中的回复）
    restoreActiveReply()
})
</script>
<style scoped lang="scss">
.consultation-container {
    margin: 0 auto;
    width: 1200px;
    display: flex;
    gap: 20px;
    padding: 20px;
    .sidebar {
        width: 320px;
        .ai-assistant-info {
            margin-bottom: 20px;
            background: linear-gradient(135deg, rgba(255, 255, 255, 0.9) 0%, rgba(255, 252, 248, 0.95) 100%);
            border-radius: 16px;
            padding: 16px;
            box-shadow: 0 8px 32px rgba(251, 146, 60, 0.06), 0 2px 8px rgba(0, 0, 0, 0.04);
            border: 1px solid rgba(251, 146, 60, 0.08);
            backdrop-filter: blur(10px);
            transition: all 0.3s ease;
            .breathing-circle {
                width: 60px;
                height: 60px;
                background: linear-gradient(135deg, #fb923c 0%, #f59e0b 100%);
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                margin: 0 auto 12px;
                animation: breathing 4s ease-in-out infinite;
                box-shadow: 0 6px 24px rgba(251, 146, 60, 0.25);
                position: relative;
            }
            .assistant-name {
                font-size: 16px;
                font-weight: 700;
                background: linear-gradient(135deg, #fb923c, #f59e0b);
                -webkit-background-clip: text;
                -webkit-text-fill-color: transparent;
                text-align: center;
                background-clip: text;
                margin: 0 0 12px;
            }
            .online-status {
                display: flex;
                align-items: center;
                justify-content: center;
                color: #059669;
                font-size: 12px;
                font-weight: 600;
                .status-dot {
                    width: 8px;
                    height: 8px;
                    background: #059669;
                    border-radius: 50%;
                    margin-right: 8px;
                    animation: pulse 2s infinite;
                    box-shadow: 0 0 8px rgba(5, 150, 105, 0.4);
                }
            }
        }
        .session-history {
            background: white;
            border-radius: 16px;
            padding: 16px;
            box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
            margin-bottom: 20px;
            min-height: 250px;
            display: flex;
            flex-direction: column;
            .section-title {
                font-size: 16px;
                font-weight: 600;
                color: #333;
                margin: 0 0 16px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                
            }
            .session-list {
                overflow-y: auto;
                max-height: 200px;
                scrollbar-width: thin;
                scrollbar-color: rgba(64, 150, 255, 0.3) transparent;
                .session-item {
                    position: relative;
                    display: flex;
                    align-items: flex-start;
                    gap: 12px;
                    padding: 12px;
                    margin-bottom: 8px;
                    border-radius: 12px;
                    cursor: pointer;
                    transition: all 0.3s ease;
                    border: 2px solid transparent;
                    &:hover {
                        background: #f8f9ff;
                        border-color: #e6f0ff;
                    }
                    &.active {
                        background: #e6f0ff;
                        border-color: #4096ff;
                    }
                    .session-info {
                        flex: 1;
                        .session-title {
                            font-weight: 500;
                            font-size: 14px;
                            color: #333;
                            margin-bottom: 4px;
                            white-space: nowrap;
                            overflow: hidden;
                            text-overflow: ellipsis;
                            .session-meta {
                                display: flex;
                                align-items: center;
                                gap: 8px;
                                margin-bottom: 6px;
                                .session-time {
                                    font-size: 12px;
                                    color: #999;
                                }
                            }
                            .session-preview {
                                width: 200px;
                                font-size: 12px;
                                color: #666;
                                margin-bottom: 6px;
                                white-space: nowrap;
                                overflow: hidden;
                                text-overflow: ellipsis;
                            }
                            .session-stats {
                                display: flex;
                                align-items: center;
                                gap: 12px;
                                span {
                                    font-size: 12px;
                                    color: #999;
                                    display: flex;
                                    align-items: center;
                                    gap: 4px;
                                }
                                .generating-chip {
                                    background: linear-gradient(135deg, #fb923c, #f59e0b);
                                    color: #fff;
                                    padding: 1px 8px;
                                    border-radius: 10px;
                                    font-size: 11px;
                                }
                            }
                        }
                        .session-actions {
                            position: absolute;
                            top: 30px;
                            right: 12px;
                        }
                    }
                }
                .no-sessions-text {
                    text-align: center;
                    font-size: 14px;
                    color: #999;
                }
            }
        }
        .emotion-garden {
            .analysis-state {
                font-size: 13px;
                line-height: 1.5;
                color: #6b5a46;
                margin: 12px 0;
            }
            background: linear-gradient(135deg, #fef9e7 0%, #fcf4e6 50%, #f6f0e8 100%);
            border-radius: 20px;
            padding: 16px;
            margin-bottom: 20px;
            box-shadow: 0 8px 32px rgba(252, 244, 230, 0.8);
            border: 1px solid rgba(255, 255, 255, 0.2);
            position: relative;
            overflow: hidden;
            min-height: 300px;
            
            .garden-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                margin-bottom: 20px;
                position: relative;
                z-index: 2;
                .garden-title {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    font-size: 16px;
                    font-weight: 600;
                    color: #8b4513;
                }
                .garden-analyzed-at {
                    font-size: 11px;
                    color: #a8927a;
                }
            }
            .emotion-info {
                margin: 0 auto;
                width: 80px;
                height: 80px;
                border-radius: 50%;
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                z-index: 10;
                box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
                border: 2px solid rgba(255, 255, 255, 0.8);
                background: linear-gradient(135deg, #ff9a9e 0%, #fecfef 50%, #fecfef 100%);
                color: #fff;
                .emotion-name {
                    font-size: 15px;
                    font-weight: 600;
                    line-height: 1;
                    margin-bottom: 2px;
                }
                .emotion-score {
                    font-size: 14px;
                    font-weight: 700;
                    opacity: 0.9;
                }
            }
            .warm-tips {
                text-align: center;
                margin-bottom: 16px;
                .emotion-status-text {
                    margin-bottom: 12px;
                    .status-label {
                        font-size: 14px;
                        color: #8b7355;
                        margin-right: 8px;
                    }
                    .status-emotion {
                        font-size: 16px;
                        font-weight: 600;
                        padding: 4px 12px;
                        border-radius: 16px;
                        display: inline-block;
                    }
                }
                .emotion-intensity {
                    margin-bottom: 16px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    gap: 8px;
                    .intensity-dots {
                        display: flex;
                        gap: 4px;
                        .dot {
                            width: 8px;
                            height: 8px;
                            border-radius: 50%;
                            background: #e0e0e0;
                            transition: all 0.3s ease;
                            &.active {
                                background: linear-gradient(135deg, #ff9a9e, #fecfef);
                                transform: scale(1.2);
                                box-shadow: 0 2px 8px rgba(255, 154, 158, 0.4);
                            }
                        }
                    }
                    .intensity-text {
                        font-size: 12px;
                        color: #8b7355;
                        font-weight: 500;
                    }
                }
                .warm-suggestion {
                    background: linear-gradient(135deg, rgba(255, 255, 255, 0.95), rgba(255, 255, 255, 0.8));
                    border-radius: 16px;
                    padding: 12px;
                    margin-bottom: 16px;
                    display: flex;
                    align-items: flex-start;
                    gap: 10px;
                    border: 1px solid rgba(255, 255, 255, 0.6);
                    box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
                    .suggestion-icon {
                        font-size: 20px;
                        flex-shrink: 0;
                        margin-top: 2px;
                    }
                    .suggestion-content {
                        text-align: left;
                        flex: 1;
                        .suggestion-title {
                            font-size: 14px;
                            font-weight: 600;
                            color: #8b7355;
                            margin-bottom: 6px;
                        }
                        .suggestion-text {
                            font-size: 13px;
                            color: #6b5b47;
                            line-height: 1.5;
                        }
                    }
                }
                .healing-actions {
                    margin-bottom: 16px;
                    .actions-title {
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        gap: 8px;
                        font-size: 14px;
                        font-weight: 600;
                        color: #8b7355;
                        margin-bottom: 16px;
                    }
                    .actions-list {
                        display: flex;
                        flex-direction: column;
                        gap: 10px;
                        .action-item {
                            background: linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(255, 255, 255, 0.7));
                            border-radius: 12px;
                            padding: 12px;
                            display: flex;
                            align-items: center;
                            gap: 10px;
                            border: 1px solid rgba(255, 255, 255, 0.5);
                            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
                            text-align: left;
                            .action-icon {
                                font-size: 14px;
                                color: #ffd700;
                                flex-shrink: 0;
                            }
                            .action-text {
                                font-size: 12px;
                                color: #6b5b47;
                                line-height: 1.4;
                                flex: 1;
                            }
                        }
                    }
                }
                .risk-notice {
                    background: linear-gradient(135deg, #fff9e6, #ffeaa7);
                    border-radius: 16px;
                    padding: 16px;
                    display: flex;
                    align-items: flex-start;
                    gap: 12px;
                    border: 1px solid rgba(255, 234, 167, 0.6);
                    box-shadow: 0 6px 20px rgba(255, 234, 167, 0.3);
                    &.crisis-notice {
                        margin-top: 20px;
                        background: linear-gradient(135deg, #fff0f0, #ffd6d6);
                        border: 1px solid rgba(255, 150, 150, 0.6);
                        box-shadow: 0 6px 20px rgba(255, 150, 150, 0.3);
                        .notice-title {
                            color: #c0392b;
                        }
                        .notice-text {
                            color: #a93226;
                        }
                    }
                    .notice-icon {
                        font-size: 20px;
                        flex-shrink: 0;
                        margin-top: 2px;
                    }
                    .notice-content {
                        flex: 1;
                        .notice-title {
                            font-size: 14px;
                            font-weight: 600;
                            color: #d4840f;
                            margin-bottom: 6px;
                        }
                        .notice-text {
                            font-size: 13px;
                            color: #b8740c;
                            line-height: 1.5;
                        }
                    }
                }
            }
        }
    }
    .chat-main {
        background: linear-gradient(135deg, rgba(255, 255, 255, 0.95) 0%, rgba(255, 252, 250, 0.98) 100%);
        border-radius: 20px;
        box-shadow: 0 12px 40px rgba(251, 146, 60, 0.08), 0 4px 16px rgba(0, 0, 0, 0.04);
        border: 1px solid rgba(251, 146, 60, 0.1);
        backdrop-filter: blur(10px);
        display: flex;
        flex-direction: column;
        overflow: hidden;
        flex: 1;
        .chat-header {
            background: linear-gradient(135deg, #fb923c 0%, #f59e0b 100%);
            color: white;
            padding: 20px 24px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            position: relative;
            flex-shrink: 0;
            .header-left {
                display: flex;
                align-items: center;
                .chat-avatar {
                    width: 48px;
                    height: 48px;
                    background: rgba(255, 255, 255, 0.25);
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin-right: 16px;
                    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
                    position: relative;
                    z-index: 1;
                }
                .chat-info {
                    h2 {
                        font-size: 20px;
                        font-weight: 700;
                        margin-bottom: 4px;
                    }
                    p {
                        font-size: 14px;
                    }
                }
            }
        }
        .chat-messages {
            flex: 1;
            overflow-y: auto;
            padding: 24px;
            display: flex;
            flex-direction: column;
            gap: 16px;
            background: linear-gradient(135deg, rgba(255, 255, 255, 0.02) 0%, rgba(255, 252, 248, 0.05) 100%);
            min-height: 0;
            max-height: calc(100vh - 200px);
            scrollbar-width: thin;
            scrollbar-color: rgba(251, 146, 60, 0.3) transparent;
            .message-item {
                display: flex;
                align-items: flex-start;
                gap: 12px;
                .message-avatar {
                    width: 32px;
                    height: 32px;
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 14px;
                    color: white;
                    flex-shrink: 0;
                }
                &.ai-message {
                    .message-avatar {
                        background: linear-gradient(135deg, #fb923c, #f59e0b);
                        box-shadow: 0 4px 12px rgba(251, 146, 60, 0.3);
                    }
                }
                &.user-message {
                    .message-avatar {
                        background: linear-gradient(135deg, #6b7280, #4b5563);
                        box-shadow: 0 4px 12px rgba(107, 114, 128, 0.3);
                    }
                }
                .message-content {
                    max-width: 70%;
                    .message-bubble {
                        background: linear-gradient(135deg, rgba(255, 255, 255, 0.9) 0%, rgba(255, 252, 248, 0.95) 100%);
                        border-radius: 16px;
                        padding: 12px 16px;
                        position: relative;
                        animation: fadeInUp 0.4s ease-out;
                        border: 1px solid rgba(251, 146, 60, 0.1);
                        box-shadow: 0 4px 16px rgba(251, 146, 60, 0.05);
                        .typing-indicator {
                            display: flex;
                            gap: 4px;
                            padding: 8px 0;
                            .typing-dot {
                                width: 8px;
                                height: 8px;
                                background: #ccc;
                                border-radius: 50%;
                                animation: typing 1.5s ease-in-out infinite;
                                &:nth-child(2) {
                                    animation-delay: 0.2s;
                                }
                                &:nth-child(3) {
                                    animation-delay: 0.4s;
                                }   
                            }
                        }
                        /* 错误消息样式 */
                        .user-text {
                            white-space: pre-wrap;
                            overflow-wrap: anywhere;
                        }
                        .error-message {
                            background: linear-gradient(135deg, #FEF2F2 0%, #FECACA 100%);
                            border: 1px solid #F87171;
                            border-radius: 12px;
                            padding: 12px 16px;
                            color: #991B1B;
                            font-weight: 500;
                            display: flex;
                            align-items: center;
                            gap: 8px;
                        }
                    }
                    .message-time {
                        font-size: 12px;
                        color: #999;
                        margin-top: 4px;
                    }
                }
            }
        }
        .chat-input {

            border-top: 1px solid rgba(251, 146, 60, 0.1);
            padding: 20px 24px;
            display: flex;
            gap: 12px;
            align-items: flex-end;
            background: linear-gradient(135deg, rgba(255, 255, 255, 0.5) 0%, rgba(255, 252, 248, 0.7) 100%);
            backdrop-filter: blur(10px);
            flex-shrink: 0;
            .input-container {
                flex: 1;
            }
            .input-footer {
                display: flex;
                justify-content: space-between;
                align-items: center;
                font-size: 12px;
                color: #78716c;
                font-weight: 500;
            }
            .send-btn {
                height: 60px;
                width: 60px;
                border-radius: 16px;
                background: linear-gradient(135deg, #fb923c 0%, #f59e0b 100%) !important;
                border: none !important;
                box-shadow: 0 6px 20px rgba(251, 146, 60, 0.25);
                transition: all 0.3s ease;
                &.stop-btn {
                    background: linear-gradient(135deg, #f87171 0%, #ef4444 100%) !important;
                    box-shadow: 0 6px 20px rgba(239, 68, 68, 0.25);
                }
            }

        }

    }
}
</style>
