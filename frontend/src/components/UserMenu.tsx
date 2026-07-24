import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import './UserMenu.css'

interface UserMenuProps {
  open: boolean
  onClose: () => void
}

export default function UserMenu({ open, onClose }: UserMenuProps) {
  const { user, logout } = useAuth()
  const nav = useNavigate()
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose()
      }
    }
    // 稍微延迟一下，避免打开菜单的那次 click 立即触发关闭
    const t = setTimeout(() => document.addEventListener('mousedown', handleClickOutside), 0)
    return () => {
      clearTimeout(t)
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [open, onClose])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open || !user) return null

  const handleSwitch = () => {
    logout()
    onClose()
    nav('/login')
  }

  const handleLogout = () => {
    logout()
    onClose()
    nav('/login')
  }

  const initial = (user.displayName || user.email || '?').charAt(0).toUpperCase()

  return (
    <div className="user-menu" ref={menuRef} role="menu">
      <div className="user-menu-header">
        <div className="user-menu-avatar-lg">{initial}</div>
        <div className="user-menu-info">
          <div className="user-menu-name">{user.displayName}</div>
          <div className="user-menu-email" title={user.email}>{user.email}</div>
          <div className={`user-menu-role-badge ${user.accountType === 'DEMO' ? 'demo' : user.role === 'ADMIN' ? 'admin' : ''}`}>
            {user.accountType === 'DEMO' ? '公开 Demo · 只读' : user.role === 'ADMIN' ? '👑 管理员' : '普通用户'}
          </div>
        </div>
      </div>

      <div className="user-menu-divider" />

      <button className="user-menu-item" onClick={handleSwitch}>
        <span className="user-menu-item-icon">🔄</span>
        <div className="user-menu-item-text">
          <span>切换账户</span>
          <small>回到登录页，用另一个账号登录</small>
        </div>
      </button>

      <button className="user-menu-item danger" onClick={handleLogout}>
        <span className="user-menu-item-icon">🚪</span>
        <div className="user-menu-item-text">
          <span>退出登录</span>
          <small>清除本地登录凭证</small>
        </div>
      </button>
    </div>
  )
}
