import type React from 'react'
import { BrowserRouter, Routes, Route, Link, useLocation } from 'react-router-dom'
import Dashboard from './pages/Dashboard'
import History from './pages/History'
import ReportDetail from './pages/ReportDetail'
import Subscription from './pages/Subscription'
import Chat from './pages/Chat'
import './Layout.css'

// 侧边栏导航组件
function Sidebar() {
  const location = useLocation()
  
  const menuItems = [
    { path: '/', icon: '🏠', label: '首页概览' },
    { path: '/reports', icon: '📋', label: '历史简报' },
    { path: '/subscription', icon: '📬', label: '订阅管理' },
    { path: '/chat', icon: '💬', label: 'AI 对话' },
    { path: '/domains', icon: '🏷️', label: '领域管理' },
    { path: '/notifications', icon: '🔔', label: '通知记录' },
    { path: '/settings', icon: '⚙️', label: '系统设置' },
  ]
  
  const isActive = (path: string) => {
    if (path === '/') return location.pathname === '/'
    return location.pathname.startsWith(path)
  }
  
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
          </Link>
        ))}
      </nav>
      
      <div className="sidebar-user">
        <div className="user-avatar">👤</div>
        <div className="user-info">
          <span className="user-name">Admin</span>
          <span className="user-role">管理员</span>
        </div>
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

export default function App() {
  return (
    <BrowserRouter>
      <div className="app-layout">
        <Sidebar />
        <div className="main-wrapper">
          <Header />
          <main className="main-content">
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/reports" element={<History />} />
              <Route path="/report/:id" element={<ReportDetail />} />
              <Route path="/subscription" element={<Subscription />} />
              <Route path="/chat" element={<Chat />} />
              <Route path="/domains" element={<div className="page-placeholder"><h2>🏷️ 领域管理</h2><p>页面开发中...</p></div>} />
              <Route path="/notifications" element={<div className="page-placeholder"><h2>🔔 通知记录</h2><p>页面开发中...</p></div>} />
              <Route path="/settings" element={<div className="page-placeholder"><h2>⚙️ 系统设置</h2><p>页面开发中...</p></div>} />
            </Routes>
          </main>
        </div>
      </div>
    </BrowserRouter>
  )
}
