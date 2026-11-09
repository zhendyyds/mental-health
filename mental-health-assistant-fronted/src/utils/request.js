import axios from 'axios'
import { ElMessage } from 'element-plus'
import { isAuthenticationFailure, toApiError, unwrapApiResponse } from './apiError'

// 创建axios实例
const service = axios.create({
  baseURL: '/api', // 请求的前缀
  timeout: 5000, // 请求的超时时间
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 在发送请求之前做些什么
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['token'] = token
    }
    return config
  },
  (error) => {
    // 对请求错误做些什么
    return Promise.reject(error)
  }
)

const rejectRequest = (error, config) => {
  if (isAuthenticationFailure(error) && !config?.url?.includes('/login')) {
    ElMessage.error(error.message || '登录过期，请重新登录')
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    window.location.href = '/auth/login'
  }
  return Promise.reject(error)
}

// HTTP 错误和 HTTP 200 内的业务错误都 reject；保留 response 供调用方读取具体提示。
service.interceptors.response.use(
  (response) => {
    try {
      return unwrapApiResponse(response)
    } catch (error) {
      return rejectRequest(error, response.config)
    }
  },
  (error) => rejectRequest(toApiError(error.response, error), error.config)
)

export default service
