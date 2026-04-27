import React, { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import dayjs from 'dayjs'
import './History.css'

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

export default function History() {
  const [pageData, setPageData] = useState<PageData | null>(null)
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [edition, setEdition] = useState<string>('')

  const fetchReports = async () => {
    setLoading(true)
    try {
      const url = `/api/reports?page=${page}&size=20${edition ? `&edition=${edition}` : ''}`
      const res = await fetch(url).then(r => r.json())
      if (res.code === 200) {
        setPageData(res.data)
      }
    } catch (e) {
      console.error('获取简报列表失败', e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchReports()
  }, [page, edition])

  const editionLabel = (e: string) => e === 'morning' ? '早间版' : '晚间版'
  const editionTagClass = (e: string) => e === 'morning' ? 'tag-morning' : 'tag-evening'

  if (loading) {
    return (
      <div className="history-page">
        <div className="loading-spinner">
          <div className="spinner"></div>
          <p>加载中...</p>
        </div>
      </div>
    )
  }

  const records = pageData?.records || []
  const total = pageData?.total || 0
  const totalPages = Math.ceil(total / 20)

  return (
    <div className="history-page">
      <div className="history-header">
        <div className="header-info">
          <h2>📋 历史简报</h2>
          <p className="header-desc">查看所有已生成的 AI 简报</p>
        </div>
        <div className="header-stats">
          <div className="stat-badge">
            <span className="stat-number">{total}</span>
            <span className="stat-label">总计</span>
          </div>
        </div>
      </div>

      {/* 筛选栏 */}
      <div className="filter-bar">
        <button
          className={edition === '' ? 'filter-btn active' : 'filter-btn'}
          onClick={() => { setEdition(''); setPage(1) }}
        >全部</button>
        <button
          className={edition === 'morning' ? 'filter-btn active' : 'filter-btn'}
          onClick={() => { setEdition('morning'); setPage(1) }}
        >🌅 早间版</button>
        <button
          className={edition === 'evening' ? 'filter-btn active' : 'filter-btn'}
          onClick={() => { setEdition('evening'); setPage(1) }}
        >🌙 晚间版</button>
      </div>

      {records.length === 0 ? (
        <div className="empty-state">
          <p>暂无简报数据</p>
        </div>
      ) : (
        <div className="reports-list">
          {records.map(report => (
            <Link key={report.id} to={`/report/${report.id}`} className="report-card">
              <div className="report-icon">
                {report.edition === 'morning' ? '🌅' : '🌙'}
              </div>
              <div className="report-content">
                <div className="report-meta">
                  <span className={`edition-tag ${editionTagClass(report.edition)}`}>
                    {editionLabel(report.edition)}
                  </span>
                  <span className="report-date">
                    {dayjs(report.createdAt).format('MM-DD HH:mm')}
                  </span>
                </div>
                <h3 className="report-title">{report.title}</h3>
                <p className="report-summary">{report.summary}</p>
              </div>
              <div className="report-arrow">→</div>
            </Link>
          ))}
        </div>
      )}

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="pagination">
          <button disabled={page === 1} onClick={() => setPage(p => p - 1)}>上一页</button>
          <span>第 {page} / {totalPages} 页</span>
          <button disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}>下一页</button>
        </div>
      )}
    </div>
  )
}
