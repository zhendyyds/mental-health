import service from '@/utils/request'

export const register = (data) => {
    return service.post('/user/add', data)
}

export const startSession = (data) => {
    return service.post('/psychological-chat/session/start', data)
}

export const getSessionList = (params) => {
    return service.get('/psychological-chat/sessions', { params })
}

export const deleteSession = (sessionId) => {
    return service.delete(`/psychological-chat/sessions/${sessionId}`)
}

export const getSessionDetail = (sessionId) => {
    return service.get(`/psychological-chat/sessions/${sessionId}/messages`)
}

export const getSessionEmotion = (sessionId) => {
    // 给服务端 60 秒总截止时间预留传输及错误响应余量。
    return service.get(`/psychological-chat/session/${sessionId}/emotion`, { timeout: 70000 })
}

export const stopActiveReply = () => {
    // 显式停止（ADR 0009）：停止是命令而非断开连接；残句由后端落库后返回
    return service.post('/psychological-chat/stream/stop')
}

export const getActiveReply = () => {
    // 活跃回复状态查询（ADR 0009）：刷新后恢复输入锁定态、会话列表"生成中"徽标
    return service.get('/psychological-chat/stream/active')
}

export const addEmotionDiary = (data) => {
    // 提交时同步分析；客户端比服务端总截止时间多留 10 秒。
    return service.post('/emotion-diary', data, { timeout: 70000 })
}

export const getEmotionDiaryPage = (params) => {
    return service.get('/emotion-diary/page', { params })
}

export const retryEmotionDiaryAnalysis = (id) => {
    // 同步重试仅用于分析缺失恢复，保留服务端超时错误的传输余量。
    return service.post(`/emotion-diary/${id}/analysis/retry`, {}, { timeout: 70000 })
}

export const getKnowledgeList = (params) => {
    return service.get('/knowledge/article/page', { params })
}

export const askKnowledgeQuestion = (question, clientRequestId) => {
    return service.post('/knowledge/qa', { question, clientRequestId }, { timeout: 70000 })
}

export const getKnowledgeDetail = (articleId) => {
    return service.get(`/knowledge/article/${articleId}`)
}
