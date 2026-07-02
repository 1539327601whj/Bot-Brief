import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import './Auth.css'

export default function Login() {
  const { login } = useAuth()
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setSubmitting(true)
    try {
      await login(email.trim(), password)
      nav('/', { replace: true })
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || '登录失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-logo">📝</div>
          <h1>登录 BriefMind</h1>
          <p className="auth-desc">用你的邮箱和密码登录</p>
        </div>

        <form onSubmit={handleSubmit} className="auth-form">
          <label>
            <span>邮箱</span>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              autoComplete="email"
              required
            />
          </label>
          <label>
            <span>密码</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="至少 6 位"
              autoComplete="current-password"
              minLength={6}
              required
            />
          </label>

          {error && <div className="auth-error">❌ {error}</div>}

          <button type="submit" className="auth-btn" disabled={submitting}>
            {submitting ? '登录中...' : '登录'}
          </button>
        </form>

        <div className="auth-footer">
          还没有账号？<Link to="/register">用邀请码注册</Link>
        </div>
      </div>
    </div>
  )
}
