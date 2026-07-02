import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import './Dashboard.css'

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  content?: string
  createdAt: string
}

function TodayCard({ edition }: { edition: 'morning' | 'evening' }) {
  const [report, setReport] = useState<Report | null>(null)
  const [loading, setLoading] = useState(true)
  const [expanded, setExpanded] = useState(false)

  useEffect(() => {
    api.get('/reports/latest', { params: { edition } })
      .then(res => {
        if (res.data?.code === 200 && res.data.data) setReport(res.data.data)
      })
      .catch(() => {/* ignore */})
      .finally(() => setLoading(false))
  }, [edition])

  const meta = edition === 'morning'
    ? { icon: '🌅', label: '今日早间版', accent: 'today-card-morning' }
    : { icon: '🌙', label: '今日晚间版', accent: 'today-card-evening' }

  const isToday = report && dayjs(report.createdAt).format('YYYY-MM-DD') === dayjs().format('YYYY-MM-DD')

  return (
    <div className={`today-card ${meta.accent}`}>
      <div className="today-card-header">
        <div className="today-card-title">
          <span className="today-card-icon">{meta.icon}</span>
          <span>{meta.label}</span>
        </div>
        {report && (
          <Link to={`/report/${report.id}`} className="today-card-link">详情 →</Link>
        )}
      </div>

      {loading ? (
        <div className="today-card-empty">加载中…</div>
      ) : !report || !isToday ? (
        <div className="today-card-empty">
          <span className="today-card-empty-title">今日尚未生成</span>
          <span className="today-card-empty-sub">
            {edition === 'morning' ? '每日 08:00 自动推送' : '每日 20:00 自动推送'}
          </span>
        </div>
      ) : (
        <>
          <h3 className="today-card-report-title">{report.title}</h3>
          <div className={`today-card-body ${expanded ? 'expanded' : ''}`}>
            <ReactMarkdown>{expanded ? (report.content || report.summary) : report.summary}</ReactMarkdown>
          </div>
          <button className="today-card-toggle" onClick={() => setExpanded(v => !v)}>
            {expanded ? '收起' : '展开阅读'}
          </button>
        </>
      )}
    </div>
  )
}

interface PageData {
  records: Report[]
  total: number
  current: number
  size: number
}

interface DashboardStats {
  todayCount: number
  totalCount: number
  hotTags: string[]
  nextPushAt: string
}

export default function Dashboard() {
  const [pageData, setPageData] = useState<PageData | null>(null)
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [edition, setEdition] = useState<string>('')
  const [stats, setStats] = useState<DashboardStats | null>(null)

  const fetchData = async () => {
    setLoading(true)
    try {
      const params: Record<string, string | number> = { page, size: 10 }
      if (edition) params.edition = edition
      const res = await api.get('/reports', { params })
      setPageData(res.data?.data)
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchData()
  }, [page, edition])

  useEffect(() => {
    api.get('/stats/dashboard')
      .then(res => {
        if (res.data?.code === 200 && res.data.data) setStats(res.data.data)
      })
      .catch(() => {/* ignore */})
  }, [])

  const nextPushLabel = stats?.nextPushAt
    ? dayjs(stats.nextPushAt).format('MM-DD HH:mm')
    : '--:--'

  const editionLabel = (e: string) => e === 'morning' ? '🌅 早间版' : '🌙 晚间版'
  const editionTagClass = (e: string) => e === 'morning' ? 'tag tag-morning' : 'tag tag-evening'

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="dashboard-new">
      {/* 欢迎横幅 */}
      <div className="welcome-banner">
        <div className="welcome-content">
          <h1 className="welcome-title">👋 欢迎使用 BriefMind</h1>
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

      {/* 今日简报 */}
      <div className="today-grid">
        <TodayCard edition="morning" />
        <TodayCard edition="evening" />
      </div>

      {/* 功能卡片区 */}
      <div className="cards-grid">
        <div className="stat-card">
          <div className="stat-icon">📄</div>
          <div className="stat-info">
            <span className="stat-value">{stats?.todayCount ?? '—'}</span>
            <span className="stat-label">今日新增</span>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">📚</div>
          <div className="stat-info">
            <span className="stat-value">{stats?.totalCount ?? pageData?.total ?? 0}</span>
            <span className="stat-label">累计简报</span>
          </div>
        </div>
        <div className="stat-card stat-card-tags">
          <div className="stat-icon">🔥</div>
          <div className="stat-info">
            <span className="stat-value stat-value-tags">
              {stats?.hotTags && stats.hotTags.length > 0
                ? stats.hotTags.slice(0, 3).join(' · ')
                : '暂无'}
            </span>
            <span className="stat-label">本周热词</span>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">⏰</div>
          <div className="stat-info">
            <span className="stat-value stat-value-time">{nextPushLabel}</span>
            <span className="stat-label">下次推送</span>
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
                    <span className="item-time">{
                      dayjs(report.createdAt).format('MM-DD HH:mm')
                    }</span>
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
