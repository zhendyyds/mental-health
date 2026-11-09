import service from '@/utils/request'

export function login(data) {
    return service.post('/user/login', data)
}


// 落回 /api/admin/** 保护规则（ADR-0002：管理端接口路径规范化）
export function categoryTree() {
    return service.get('/admin/knowledge/category/tree')
}

// 管理端列表：含草稿/已下线/软删行，支持标题/分类/状态筛选——与用户端公开列表是两个不同端点
export function articlePage(params) {
    return service.get('/admin/knowledge/article/page', { params })
}

export function getArticleIndexJobs(articleId) {
    return service.get('/admin/knowledge/index-jobs', { params: { articleId, limit: 10 } })
}

export function retryArticleIndexJob(jobId) {
    return service.post(`/admin/knowledge/index-jobs/${encodeURIComponent(jobId)}/retry`)
}

export function uploadFile(file, businessInfo) {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('businessType', 'ARTICLE')
    formData.append('businessId', businessInfo.businessId)
    formData.append('businessField', 'cover')

    return service.post('/file/upload', formData, {
        headers: {
            'Content-Type': 'multipart/form-data'
        }
    })
}


export function createArticle(data) {
    return service.post('/admin/knowledge/article', data)
}

export function getArticleDetail(id) {
    return service.get(`/admin/knowledge/article/${id}`)
}


export function updateArticle(id, data) {
    return service.put(`/admin/knowledge/article/${id}`, data)
}

export function changeArticleStatus(id, data) {
    return service.put(`/admin/knowledge/article/${id}/status`, data)
}

export function deleteArticle(id) {
    return service.delete(`/admin/knowledge/article/${id}`)
}

export function getConsultationPage(params) {
    return service.get('/admin/consultation/sessions', { params })
}

export function getSessionDetail(sessionId) {
    return service.get(`/admin/consultation/sessions/${sessionId}/messages`)
}

export function getEmotionalPage(params) {
    // 落回 /api/admin/** 保护规则（ADR-0002：管理端接口路径规范化）
    return service.get('/admin/emotion-diary/page', { params })
}

export function deleteEmotional(id) {
    return service.delete(`/admin/emotion-diary/${id}`)
}

export function getAnalyticsOverview() {
    return service.get(`/data-analytics/overview`)
}

export function logout() {
    return service.post('/user/logout')
}
