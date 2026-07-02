import axios from 'axios'

export const TOKEN_KEY = 'briefmind_token'
export const USER_KEY = 'briefmind_user'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (resp) => resp,
  (err) => {
    if (err?.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      // 若不在 /login 或 /register，跳登录
      const p = window.location.pathname
      if (p !== '/login' && p !== '/register') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(err)
  }
)

export default api
