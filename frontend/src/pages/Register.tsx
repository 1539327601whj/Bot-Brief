import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import './Auth.css'

export default function Register() {
  const { register } = useAuth()
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setSubmitting(true)
    try {
      await register(email.trim(), password, displayName.trim(), inviteCode.trim())
      nav('/', { replace: true })
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || '注册失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-logo">📝</div>
          <h1>注册 BriefMind</h1>
          <p className="auth-desc">向管理员索要邀请码后即可注册</p>
        </div>

        <form onSubmit={handleSubmit} className="auth-form">
          <label>
            <span>邮箱</span>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                   placeholder="you@example.com" autoComplete="email" required />
          </label>
          <label>
            <span>昵称</span>
            <input type="text" value={displayName} onChange={(e) => setDisplayName(e.target.value)}
                   placeholder="随便起一个" maxLength={20} required />
          </label>
          <label>
            <span>密码</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                   placeholder="至少 6 位" autoComplete="new-password" minLength={6} required />
          </label>
          <label>
            <span>邀请码</span>
            <input type="text" value={inviteCode} onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
                   placeholder="向管理员索要" required />
          </label>

          {error && <div className="auth-error">❌ {error}</div>}

          <button type="submit" className="auth-btn" disabled={submitting}>
            {submitting ? '注册中...' : '注册并登录'}
          </button>
        </form>

        <div className="auth-footer">
          已有账号？<Link to="/login">直接登录</Link>
        </div>
      </div>
    </div>
  )
}
