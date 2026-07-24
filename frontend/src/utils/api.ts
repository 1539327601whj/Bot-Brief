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

let validatingSession: { token: string; promise: Promise<void> } | null = null

function clearSession(token: string) {
  if (localStorage.getItem(TOKEN_KEY) !== token) return
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  if (window.location.pathname !== '/login') window.location.href = '/login'
}

async function validateSession(token: string) {
  try {
    await axios.get('/api/auth/me', {
      timeout: 10000,
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 401) clearSession(token)
  }
}

api.interceptors.response.use(
  (resp) => resp,
  (err) => {
    if (err?.response?.status === 401) {
      const authorization = err?.config?.headers?.Authorization
      const failedToken = typeof authorization === 'string' && authorization.startsWith('Bearer ')
        ? authorization.slice(7)
        : null
      const currentToken = localStorage.getItem(TOKEN_KEY)

      if (failedToken && failedToken === currentToken) {
        if (err?.config?.url === '/auth/me') {
          clearSession(failedToken)
        } else if (validatingSession?.token !== failedToken) {
          const promise = validateSession(failedToken).finally(() => {
            if (validatingSession?.promise === promise) validatingSession = null
          })
          validatingSession = { token: failedToken, promise }
        }
      }
    }
    return Promise.reject(err)
  }
)

export default api
