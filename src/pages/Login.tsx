import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  listSavedAccounts,
  saveAccount,
  removeSavedAccount,
  type SavedAccount,
} from '../utils/savedAccounts'
import './Auth.css'

type Mode = 'list' | 'form'

export default function Login() {
  const { login } = useAuth()
  const nav = useNavigate()

  const [saved, setSaved] = useState<SavedAccount[]>(() => listSavedAccounts())
  const [mode, setMode] = useState<Mode>(() =>
    listSavedAccounts().length > 0 ? 'list' : 'form'
  )

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [rememberAccount, setRememberAccount] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const hasSaved = saved.length > 0
  const showBackButton = mode === 'form' && hasSaved

  const persistAfterLogin = (userEmail: string, userInfo: any) => {
    if (!rememberAccount) {
      removeSavedAccount(userEmail)
      return
    }
    saveAccount({
      email: userEmail,
      displayName: userInfo?.displayName || userEmail,
      role: userInfo?.role || 'USER',
      lastLoginAt: new Date().toISOString(),
    })
  }

  const handleFormSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (submitting) return
    setError('')
    setSubmitting(true)
    try {
      const info = await login(email.trim(), password)
      persistAfterLogin(email.trim(), info)
      nav('/', { replace: true })
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || '登录失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleAccountClick = (a: SavedAccount) => {
    if (submitting) return
    // 只预填邮箱 → 切表单 → 交给浏览器密码管理器自动填充密码
    setEmail(a.email)
    setPassword('')
    setRememberAccount(true)
    setError('')
    setMode('form')
    setTimeout(() => {
      const el = document.querySelector<HTMLInputElement>('input[name="password"]')
      el?.focus()
    }, 50)
  }

  const handleDelete = (e: React.MouseEvent, emailToDelete: string) => {
    e.stopPropagation()
    if (!confirm(`确定要删除已保存的账户 ${emailToDelete} 吗？\n（这只会删除本地记录，不影响账户本身）`)) return
    removeSavedAccount(emailToDelete)
    const next = listSavedAccounts()
    setSaved(next)
    if (next.length === 0) setMode('form')
  }

  const goToNewAccount = () => {
    setEmail('')
    setPassword('')
    setRememberAccount(true)
    setError('')
    setMode('form')
  }

  const backToList = () => {
    setError('')
    setMode('list')
  }

  const initialOf = (a: SavedAccount) =>
    (a.displayName || a.email || '?').charAt(0).toUpperCase()

  // —— 账户列表模式 ——
  if (mode === 'list') {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-header">
            <div className="auth-logo">📝</div>
            <h1>选择账户登录</h1>
            <p className="auth-desc">点击已保存的账户，用它的邮箱快速登录</p>
          </div>

          <div className="account-list">
            {saved.map(a => (
              <div
                key={a.email}
                className="account-card"
                onClick={() => handleAccountClick(a)}
                role="button"
              >
                <div className="account-avatar">{initialOf(a)}</div>
                <div className="account-info">
                  <div className="account-name">
                    {a.displayName || a.email}
                    {a.role === 'ADMIN' && <span className="account-badge admin">👑</span>}
                  </div>
                  <div className="account-email" title={a.email}>{a.email}</div>
                </div>
                <button
                  className="account-delete"
                  title="删除此账户记录（不影响账户本身）"
                  onClick={e => handleDelete(e, a.email)}
                >×</button>
              </div>
            ))}
          </div>

          {error && <div className="auth-error">❌ {error}</div>}

          <button className="auth-btn-secondary" onClick={goToNewAccount}>
            + 使用其他账户登录
          </button>

          <div className="auth-footer">
            还没有账号？<Link to="/register">用邀请码注册</Link>
          </div>
        </div>
      </div>
    )
  }

  // —— 表单模式 ——
  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-logo">📝</div>
          <h1>登录 BriefMind</h1>
          <p className="auth-desc">用你的邮箱和密码登录</p>
        </div>

        <form onSubmit={handleFormSubmit} className="auth-form" autoComplete="on">
          <label>
            <span>邮箱</span>
            <input
              name="email"
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              autoComplete="username email"
              required
            />
          </label>
          <label>
            <span>密码</span>
            <input
              name="password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="至少 6 位"
              autoComplete="current-password"
              minLength={6}
              required
            />
          </label>

          <label className="remember-check standalone">
            <input
              type="checkbox"
              checked={rememberAccount}
              onChange={e => setRememberAccount(e.target.checked)}
            />
            <span>
              记住此账户
              <small>只存邮箱，方便下次点击直接选</small>
            </span>
          </label>

          <div className="password-tip">
            🔒 <b>关于密码：</b>登录后浏览器会提示"保存密码"，同意即可。
            它会用系统级加密（Windows Hello / Keychain 等）安全保存，比任何网站自己存都安全。
          </div>

          {error && <div className="auth-error">❌ {error}</div>}

          <button type="submit" className="auth-btn" disabled={submitting}>
            {submitting ? '登录中...' : '登录'}
          </button>

          {showBackButton && (
            <button type="button" className="auth-btn-secondary" onClick={backToList}>
              ← 返回账户列表
            </button>
          )}
        </form>

        <div className="auth-footer">
          还没有账号？<Link to="/register">用邀请码注册</Link>
        </div>
      </div>
    </div>
  )
}
