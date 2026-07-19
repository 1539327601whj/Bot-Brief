import type React from 'react'
import { useState } from 'react'
import { BrowserRouter, Routes, Route, Link, useLocation, useNavigate } from 'react-router-dom'
import Dashboard from './pages/Dashboard'
import History from './pages/History'
import ReportDetail from './pages/ReportDetail'
import Subscription from './pages/Subscription'
import Chat from './pages/Chat'
import ContentGrowth from './pages/ContentGrowth'
import Login from './pages/Login'
import Register from './pages/Register'
import PushChannels from './pages/PushChannels'
import Notifications from './pages/Notifications'
import MarketWatch from './pages/MarketWatch'
import Pricing from './pages/Pricing'
import CreatorTools from './pages/CreatorTools'
import ShopAnalytics from './pages/ShopAnalytics'
import Admin from './pages/Admin'
import { AuthProvider, useAuth } from './context/AuthContext'
import { ProtectedRoute, AdminRoute } from './components/ProtectedRoute'
import UserMenu from './components/UserMenu'
import './Layout.css'

// 侧边栏导航组件
function Sidebar() {
  const location = useLocation()
  const { user } = useAuth()
  const nav = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)

  const menuItems = [
    { path: '/', icon: '🏠', label: '首页概览' },
    { path: '/reports', icon: '📋', label: '历史简报' },
    { path: '/market-watch', icon: '市', label: '市场观察' },
    { path: '/pricing', icon: '💎', label: '套餐权益' },
    { path: '/creator-tools', icon: '🎬', label: '短视频分析', wip: true },
    { path: '/content-growth', icon: '📈', label: '内容增长' },
    { path: '/shop-analytics', icon: '🛍️', label: '店铺分析' },
    { path: '/subscription', icon: '📬', label: '订阅管理' },
    { path: '/chat', icon: '💬', label: 'AI 对话' },
    { path: '/channels', icon: '📡', label: '推送渠道' },
    { path: '/notifications', icon: '🔔', label: '通知记录' },
    ...(user?.role === 'ADMIN' ? [{ path: '/admin', icon: '🛠', label: '邀请码管理' }] : []),
  ]

  const isActive = (path: string) => {
    if (path === '/') return location.pathname === '/'
    return location.pathname.startsWith(path)
  }

  const handleUserClick = () => {
    if (user) {
      setMenuOpen(v => !v)
    } else {
      nav('/login')
    }
  }

  const initial = (user?.displayName || user?.email || '?').charAt(0).toUpperCase()

  return (
    <aside className="sidebar">
      <div className="sidebar-logo">
        <span className="logo-icon">📝</span>
        <span className="logo-text">BriefMind</span>
      </div>

      <nav className="sidebar-nav">
        {menuItems.map(item => (
          <Link
            key={item.path}
            to={item.path}
            className={`nav-item ${isActive(item.path) ? 'active' : ''}`}
          >
            <span className="nav-icon">{item.icon}</span>
            <span className="nav-label">{item.label}</span>
            {'wip' in item && item.wip && <span className="nav-badge-wip">即将</span>}
          </Link>
        ))}
      </nav>

      <UserMenu open={menuOpen} onClose={() => setMenuOpen(false)} />

      <div
        className={`sidebar-user ${menuOpen ? 'active' : ''}`}
        onClick={handleUserClick}
        style={{ cursor: 'pointer' }}
        title={user ? '点击查看账户菜单' : '点击去登录'}
      >
        <div className="user-avatar user-avatar-initial">
          {user ? initial : '🔒'}
        </div>
        <div className="user-info">
          <span className="user-name">{user?.displayName || '未登录'}</span>
          <span className="user-role">
            {user ? (user.role === 'ADMIN' ? '管理员' : '用户') : '点击登录'}
          </span>
        </div>
        {user && <span className="user-menu-caret">{menuOpen ? '▾' : '▸'}</span>}
      </div>
    </aside>
  )
}

// 顶部栏组件
function Header() {
  return (
    <header className="header-bar">
      <div className="header-left">
        <h1 className="header-title">BriefMind</h1>
      </div>
      <div className="header-right">
        <span className="header-time">{new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })}</span>
      </div>
    </header>
  )
}

// 主布局（带侧边栏、顶栏），并要求登录
function MainLayout({ children }: { children: React.ReactNode }) {
  return (
    <ProtectedRoute>
      <div className="app-layout">
        <Sidebar />
        <div className="main-wrapper">
          <Header />
          <main className="main-content">{children}</main>
        </div>
      </div>
    </ProtectedRoute>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          {/* 登录/注册页 —— 无侧栏 */}
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />

          {/* 需登录后可访问 */}
          <Route path="/*" element={
            <MainLayout>
              <Routes>
                <Route path="/" element={<Dashboard />} />
                <Route path="/reports" element={<History />} />
                <Route path="/market-watch" element={<MarketWatch />} />
                <Route path="/pricing" element={<Pricing />} />
                <Route path="/creator-tools" element={<CreatorTools />} />
                <Route path="/content-growth" element={<ContentGrowth />} />
                <Route path="/shop-analytics" element={<ShopAnalytics />} />
                <Route path="/report/:id" element={<ReportDetail />} />
                <Route path="/subscription" element={<Subscription />} />
                <Route path="/chat" element={<Chat />} />
                <Route path="/channels" element={<PushChannels />} />
                <Route path="/notifications" element={<Notifications />} />
                <Route path="/admin" element={<AdminRoute><Admin /></AdminRoute>} />
              </Routes>
            </MainLayout>
          } />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
