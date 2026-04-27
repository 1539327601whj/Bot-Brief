import React, { useState, useEffect } from 'react'
import './History.css'

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  createdAt: string
}

export default function History() {
  const [reports, setReports] = useState<Report[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch('/api/reports?page=1&size=50')
      .then(res => res.json())
      .then(data => {
        if (data.code === 200 && data.data?.records) {
          setReports(data.data.records)
        }
      })
      .catch(() => {/* ignore */})
      .finally(() => setLoading(false))
  }, [])

  const formatDate = (dateStr: string) => {
    const d = new Date(dateStr)
    // 转为北京时间 (UTC+8)
    d.setHours(d.getHours() + 8)
    return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`
  }

  const formatTime = (dateStr: string) => {
    const d = new Date(dateStr)
    // 转为北京时间 (UTC+8)
    d.setHours(d.getHours() + 8)
    return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
  }

  const isMorning = (edition: string) => edition === 'morning'

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

  return (
    <div className="history-page">
      <div className="history-header">
        <div className="header-info">
          <h2>📋 历史简报</h2>
          <p className="header-desc">查看所有已生成的 AI 简报</p>
        </div>
        <div className="header-stats">
          <div className="stat-badge">
            <span className="stat-number">{reports.length}</span>
            <span className="stat-label">总计</span>
          </div>
        </div>
      </div>

      {reports.length === 0 ? (
        <div className="empty-state">
          <p>暂无历史简报</p>
        </div>
      ) : (
        <div className="reports-list">
          {reports.map(report => (
            <div key={report.id} className="report-card" onClick={() => window.location.href = `/report/${report.id}`}>
              <div className="report-icon">
                {isMorning(report.edition) ? '🌅' : '🌙'}
              </div>
              <div className="report-content">
                <div className="report-meta">
                  <span className="report-category" style={{ borderColor: '#00d4aa' }}>
                    {isMorning(report.edition) ? '早间版' : '晚间版'}
                  </span>
                  <span className="report-version">AI 简报</span>
                </div>
                <h3 className="report-title">{report.title}</h3>
                <p className="report-summary">{report.summary}</p>
                <div className="report-footer">
                  <span className="report-date">{formatDate(report.createdAt)}</span>
                  <span className="report-time">{formatTime(report.createdAt)}</span>
                </div>
              </div>
              <div className="report-arrow">→</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
