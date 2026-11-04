import type React from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
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
import StockPick from './pages/StockPick'
import CodeLookupLegacy from './pages/CodeLookup'
import CodeLookupV2Page from './pages/codeLookup/CodeLookupPage'
import Pricing from './pages/Pricing'
import CreatorTools from './pages/CreatorTools'
import ShopAnalytics from './pages/ShopAnalytics'
import Admin from './pages/Admin'
import { AuthProvider, useAuth } from './context/AuthContext'
import { ThemeProvider, useTheme } from './context/ThemeContext'
import { ProtectedRoute, AdminRoute } from './components/ProtectedRoute'
import BrandLogo from './components/BrandLogo'
import UserMenu from './components/UserMenu'
import ViewScrollRestoration from './components/ViewScrollRestoration'
import './Layout.css'

// 侧边栏导航组件
function Sidebar({ open, onClose }: { open: boolean; onClose: () => void }) {
  const location = useLocation()
  const { user } = useAuth()
  const nav = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  // 抽屉打开时锁住页面滚动，并支持 Esc 关闭。
  useEffect(() => {
    if (!open) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    closeButtonRef.current?.focus()
    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, onClose])

  // 关闭后把焦点还给汉堡按钮，别留在已隐藏的抽屉里。
  const wasOpen = useRef(false)
  useEffect(() => {
    if (wasOpen.current && !open) document.getElementById('nav-toggle')?.focus()
    wasOpen.current = open
  }, [open])

  const canSeePublicDigest = user?.accountType === 'DEMO' || user?.role === 'ADMIN'
  const menuItems = [
    { path: '/', icon: '🏠', label: '首页概览' },
    { path: '/reports', icon: '📋', label: '历史简报' },
    ...(canSeePublicDigest ? [
      { path: '/market-watch', icon: '市', label: '市场观察' },
      { path: '/stock-pick', icon: '选', label: '低估精选' },
      { path: '/code-lookup', icon: '查', label: '代码查询' },
    ] : []),
    { path: '/pricing', icon: '💎', label: '套餐权益' },
    { path: '/creator-tools', icon: '🎬', label: '短视频分析' },
    { path: '/content-growth', icon: '📈', label: '内容增长' },
    { path: '/shop-analytics', icon: '🛍️', label: '店铺分析' },
    { path: '/subscription', icon: '📬', label: '订阅管理' },
    { path: '/chat', icon: '💬', label: 'AI 对话' },
    { path: '/channels', icon: '📡', label: '推送渠道' },
    { path: '/notifications', icon: '🔔', label: '通知记录' },
    ...(user?.role === 'ADMIN' || user?.accountType === 'DEMO' ? [{ path: '/admin', icon: '🛠', label: '邀请码管理' }] : []),
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
    <>
      <div
        className={`sidebar-scrim ${open ? 'is-open' : ''}`}
        onClick={onClose}
        aria-hidden="true"
      />
      <aside id="app-sidebar" className={`sidebar ${open ? 'is-open' : ''}`}>
        <div className="sidebar-logo">
          <BrandLogo className="logo-icon" />
          <span className="logo-text">BriefMind</span>
          <button
            ref={closeButtonRef}
            type="button"
            className="sidebar-close"
            onClick={onClose}
            aria-label="关闭导航菜单"
          >
            ✕
          </button>
        </div>

        <nav className="sidebar-nav">
          {menuItems.map(item => (
            <Link
              key={item.path}
              to={item.path}
              className={`nav-item ${isActive(item.path) ? 'active' : ''}`}
              onClick={onClose}
            >
              <span className="nav-icon">{item.icon}</span>
              <span className="nav-label">{item.label}</span>
              {'wip' in item && (item as { wip?: boolean }).wip && <span className="nav-badge-wip">即将</span>}
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
              {user ? (user.accountType === 'DEMO' ? '公开 Demo' : user.role === 'ADMIN' ? '管理员' : '用户') : '点击登录'}
            </span>
          </div>
          {user && <span className="user-menu-caret">{menuOpen ? '▾' : '▸'}</span>}
        </div>
      </aside>
    </>
  )
}

// 顶部栏组件
function Header({ navOpen, onToggleNav }: { navOpen: boolean; onToggleNav: () => void }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { theme, toggleTheme } = useTheme()
  const showBack = location.pathname !== '/'

  const goBack = () => {
    if (location.key !== 'default') navigate(-1)
    else navigate('/')
  }

  return (
    <header className="header-bar">
      <div className="header-left">
        <button
          type="button"
          id="nav-toggle"
          className="nav-toggle"
          onClick={onToggleNav}
          aria-label={navOpen ? '关闭导航菜单' : '打开导航菜单'}
          aria-expanded={navOpen}
          aria-controls="app-sidebar"
        >
          {navOpen ? '✕' : '☰'}
        </button>
        {showBack && (
          <button type="button" className="header-back" onClick={goBack} aria-label="返回上一页">
            ← 返回
          </button>
        )}
        <h1 className="header-title">BriefMind</h1>
      </div>
      <div className="header-right">
        <button
          type="button"
          className="theme-toggle"
          onClick={toggleTheme}
          title={theme === 'dark' ? '浅色模式' : '深色模式'}
          aria-label={theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
        >
          {theme === 'dark' ? '☀️' : '🌙'}
        </button>
        <span className="header-time">{new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })}</span>
      </div>
    </header>
  )
}

// 主布局（带侧边栏、顶栏），并要求登录
function MainLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  const [navOpen, setNavOpen] = useState(false)
  const closeNav = useCallback(() => setNavOpen(false), [])

  // 换页后自动收起抽屉，避免遮住新页面。
  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  // 拉宽到桌面时关掉抽屉，防止状态残留。
  useEffect(() => {
    const query = window.matchMedia('(min-width: 1025px)')
    const handleChange = () => {
      if (query.matches) setNavOpen(false)
    }
    query.addEventListener?.('change', handleChange)
    return () => query.removeEventListener?.('change', handleChange)
  }, [])

  return (
    <ProtectedRoute>
      <ViewScrollRestoration />
      <div className={`app-layout${navOpen ? ' nav-open' : ''}`}>
        <Sidebar open={navOpen} onClose={closeNav} />
        <div className="main-wrapper">
          <Header navOpen={navOpen} onToggleNav={() => setNavOpen(v => !v)} />
          <main className="main-content">{children}</main>
        </div>
      </div>
    </ProtectedRoute>
  )
}

export default function App() {
  return (
    <ThemeProvider>
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
                  <Route path="/stock-pick" element={<StockPick />} />
                  <Route
                    path="/code-lookup"
                    element={import.meta.env.VITE_CODE_LOOKUP_V2 === 'false'
                      ? <CodeLookupLegacy />
                      : <CodeLookupV2Page />}
                  />
                  <Route path="/code-lookup/legacy" element={<CodeLookupLegacy />} />
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
    </ThemeProvider>
  )
}
