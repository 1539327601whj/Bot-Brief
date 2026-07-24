import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from '../context/AuthContext'

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, token, authReady } = useAuth()
  const location = useLocation()
  if (!authReady) return <div className="loading">加载中...</div>
  if (!user || !token) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <>{children}</>
}

export function AdminRoute({ children }: { children: ReactNode }) {
  const { user, token, authReady } = useAuth()
  if (!authReady) return <div className="loading">加载中...</div>
  if (!user || !token) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN' && user.accountType !== 'DEMO') {
    return <div className="page-placeholder"><h2>🚫 权限不足</h2><p>仅管理员可访问</p></div>
  }
  return <>{children}</>
}
