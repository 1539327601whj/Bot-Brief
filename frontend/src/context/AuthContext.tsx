import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import api, { TOKEN_KEY, USER_KEY } from '../utils/api'

export interface UserInfo {
  id: number
  email: string
  displayName: string
  role: 'ADMIN' | 'USER'
  accountType: 'NORMAL' | 'DEMO'
}

interface AuthContextValue {
  user: UserInfo | null
  token: string | null
  loading: boolean
  authReady: boolean
  login: (email: string, password: string) => Promise<UserInfo>
  demoLogin: () => Promise<UserInfo>
  register: (email: string, password: string, displayName: string, inviteCode: string) => Promise<UserInfo>
  logout: () => void
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(() => {
    const s = localStorage.getItem(USER_KEY)
    return s ? (JSON.parse(s) as UserInfo) : null
  })
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY))
  const [loading, setLoading] = useState(false)
  const [authReady, setAuthReady] = useState(() => !localStorage.getItem(TOKEN_KEY))
  const authGeneration = useRef(0)

  useEffect(() => {
    const storedToken = localStorage.getItem(TOKEN_KEY)
    if (storedToken) refresh().finally(() => setAuthReady(true))
  }, [])

  const persist = (t: string | null, u: UserInfo | null) => {
    authGeneration.current += 1
    if (t) localStorage.setItem(TOKEN_KEY, t)
    else localStorage.removeItem(TOKEN_KEY)
    if (u) localStorage.setItem(USER_KEY, JSON.stringify(u))
    else localStorage.removeItem(USER_KEY)
    setToken(t)
    setUser(u)
    setAuthReady(true)
  }

  const login: AuthContextValue['login'] = async (email, password) => {
    setLoading(true)
    try {
      const res = await api.post('/auth/login', { email, password })
      if (res.data?.code !== 200) throw new Error(res.data?.message || '登录失败')
      const { token, user } = res.data.data
      persist(token, user)
      return user
    } finally {
      setLoading(false)
    }
  }

  const demoLogin: AuthContextValue['demoLogin'] = async () => {
    setLoading(true)
    try {
      const res = await api.post('/auth/demo')
      if (res.data?.code !== 200) throw new Error(res.data?.message || 'Demo 登录失败')
      const { token, user } = res.data.data
      persist(token, user)
      return user
    } finally {
      setLoading(false)
    }
  }

  const register: AuthContextValue['register'] = async (email, password, displayName, inviteCode) => {
    setLoading(true)
    try {
      const res = await api.post('/auth/register', { email, password, displayName, inviteCode })
      if (res.data?.code !== 200) throw new Error(res.data?.message || '注册失败')
      const { token, user } = res.data.data
      persist(token, user)
      return user
    } finally {
      setLoading(false)
    }
  }

  const logout = () => persist(null, null)

  const refresh = async () => {
    const requestToken = localStorage.getItem(TOKEN_KEY)
    if (!requestToken) return
    const requestGeneration = authGeneration.current
    const isCurrentSession = () => (
      authGeneration.current === requestGeneration
      && localStorage.getItem(TOKEN_KEY) === requestToken
    )

    try {
      const res = await api.get('/auth/me')
      if (!isCurrentSession()) return
      if (res.data?.code === 200) {
        const info = res.data.data as UserInfo
        localStorage.setItem(USER_KEY, JSON.stringify(info))
        setUser(info)
      } else if (res.data?.code === 401) {
        persist(null, null)
      }
    } catch (error) {
      if (authGeneration.current !== requestGeneration) return
      const currentToken = localStorage.getItem(TOKEN_KEY)
      if (currentToken && currentToken !== requestToken) return
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 401) persist(null, null)
    }
  }

  return (
    <AuthContext.Provider value={{ user, token, loading, authReady, login, demoLogin, register, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
