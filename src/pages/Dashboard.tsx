import React, { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import './Dashboard.css'

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  createdAt: string
}

interface PageData {
  records: Report[]
  total: number
  current: number
  size: number
}

export default function Dashboard() {
  const navigate = useNavigate()
  const [pageData, setPageData] = useState<PageData | null>(null)
  const [latest, setLatest] = useState<Report | null>(null)
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [edition, setEdition] = useState<string>('')

  const fetchData = async () => {
    setLoading(true)
    try {
      const [pageRes, latestRes] = await Promise.all([
        fetch(`/api/reports?page=${page}&size=10${edition ? `&edition=${edition}` : ''}`).then(r => r.json()),
        fetch(`/api/reports/latest${edition ? `?edition=${edition}` : ''}`).then(r => r.json()),
      ])
      setPageData(pageRes.data)
      setLatest(latestRes.data)
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchData()
  }, [page, edition])

  const editionLabel = (e: string) => e === 'morning' ? '🌅 早间版' : '🌙 晚间版'
  const editionTagClass = (e: string) => e === 'morning' ? 'tag tag-morning' : 'tag tag-evening'

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="dashboard-new">
      {/* 欢迎横幅 */}
      <div className="welcome-banner">
        <div className="welcome-content">
          <h1 className="welcome-title">👋 欢迎使用 AI 简报助手</h1>
          <p className="welcome-subtitle">智能聚合多源资讯，生成个性化每日简报</p>
          <div className="feature-tags">
            <span className="feature-tag">🤖 AI 智能搜集</span>
            <span className="feature-tag">⏰ 定时推送</span>
            <span className="feature-tag">🔍 RAG 智能问答</span>
          </div>
        </div>
        <div className="welcome-image">
          <img src="/ai-robot.svg" alt="AI Assistant" onError={(e) => e.currentTarget.style.display = 'none'} />
        </div>
      </div>

      {/* 功能卡片区 */}
      <div className="cards-grid">
        <div className="stat-card">
          <div className="stat-icon">📄</div>
          <div className="stat-info">
            <span className="stat-value">{pageData?.total || 0}</span>
            <span className="stat-label">已生成简报</span>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">📬</div>
          <div className="stat-info">
            <span className="stat-value">2</span>
            <span className="stat-label">订阅源</span>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">💬</div>
          <div className="stat-info">
            <span className="stat-value">0</span>
            <span className="stat-label">对话次数</span>
          </div>
        </div>
        <div className="stat-card clickable" onClick={() => navigate('/chat')}>
          <div className="stat-icon">🚀</div>
          <div className="stat-info">
            <span className="stat-value">开始</span>
            <span className="stat-label">RAG 对话</span>
          </div>
        </div>
      </div>

      {/* 历史简报 */}
      <div className="section">
        <div className="section-header">
          <h2 className="section-title">📋 历史简报</h2>
          <div className="filter-bar">
            <button
              className={edition === '' ? 'filter-btn active' : 'filter-btn'}
              onClick={() => setEdition('')}
            >全部</button>
            <button
              className={edition === 'morning' ? 'filter-btn active' : 'filter-btn'}
              onClick={() => setEdition('morning')}
            >🌅 早间版</button>
            <button
              className={edition === 'evening' ? 'filter-btn active' : 'filter-btn'}
              onClick={() => setEdition('evening')}
            >🌙 晚间版</button>
          </div>
        </div>
        
        <div className="report-list">
          {pageData?.records?.length === 0 ? (
            <div className="empty">暂无简报</div>
          ) : (
            <div className="list">
              {pageData?.records?.map(report => (
                <div key={report.id} className="report-item">
                  <div className="item-left">
                    <span className={editionTagClass(report.edition)}>{editionLabel(report.edition)}</span>
                    <span className="item-time">{dayjs(report.createdAt).format('MM-DD HH:mm')}</span>
                  </div>
                  <div className="item-right">
                    <Link to={`/report/${report.id}`} className="item-title">{report.title}</Link>
                    <p className="item-summary">{report.summary}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 分页 */}
        {pageData && pageData.total > 10 && (
          <div className="pagination">
            <button disabled={page === 1} onClick={() => setPage(p => p - 1)}>上一页</button>
            <span>第 {page} / {Math.ceil(pageData.total / 10)} 页</span>
            <button
              disabled={page >= Math.ceil(pageData.total / 10)}
              onClick={() => setPage(p => p + 1)}
            >下一页</button>
          </div>
        )}
      </div>

      {/* 订阅概览 */}
      <div className="section">
        <div className="section-header">
          <h2 className="section-title">📬 订阅概览</h2>
          <Link to="/subscription" className="section-link">管理订阅 →</Link>
        </div>
        <div className="subscription-cards">
          <div className="subscription-card">
            <div className="sub-icon">🌅</div>
            <div className="sub-info">
              <span className="sub-label">早间版</span>
              <span className="sub-time">每日 08:00</span>
            </div>
            <span className="sub-status active">已启用</span>
          </div>
          <div className="subscription-card">
            <div className="sub-icon">🌙</div>
            <div className="sub-info">
              <span className="sub-label">晚间版</span>
              <span className="sub-time">每日 20:00</span>
            </div>
            <span className="sub-status active">已启用</span>
          </div>
        </div>
      </div>
    </div>
  )
}
