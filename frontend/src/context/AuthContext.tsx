import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import api, { TOKEN_KEY, USER_KEY } from '../utils/api'

export interface UserInfo {
  id: number
  email: string
  displayName: string
  role: 'ADMIN' | 'USER'
}

interface AuthContextValue {
  user: UserInfo | null
  token: string | null
  loading: boolean
  login: (email: string, password: string) => Promise<UserInfo>
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

  // 打开 App 时若有 token，用 /me 同步真实用户信息
  useEffect(() => {
    if (token) {
      refresh().catch(() => {})
    }
  }, [])

  const persist = (t: string | null, u: UserInfo | null) => {
    if (t) localStorage.setItem(TOKEN_KEY, t)
    else localStorage.removeItem(TOKEN_KEY)
    if (u) localStorage.setItem(USER_KEY, JSON.stringify(u))
    else localStorage.removeItem(USER_KEY)
    setToken(t)
    setUser(u)
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
    try {
      const res = await api.get('/auth/me')
      if (res.data?.code === 200) {
        const info = res.data.data as UserInfo
        localStorage.setItem(USER_KEY, JSON.stringify(info))
        setUser(info)
      } else {
        persist(null, null)
      }
    } catch {
      persist(null, null)
    }
  }

  return (
    <AuthContext.Provider value={{ user, token, loading, login, register, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
