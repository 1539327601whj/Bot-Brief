import React, { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import dayjs from 'dayjs'
import './ReportDetail.css'

interface Report {
  id: number
  edition: string
  title: string
  content: string
  summary: string
  runId: string
  createdAt: string
}

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>()
  const [report, setReport] = useState<Report | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`/api/reports/${id}`)
      .then(r => r.json())
      .then(r => setReport(r.data))
      .finally(() => setLoading(false))
  }, [id])

  if (loading) return <div className="loading">加载中...</div>
  if (!report) return <div className="loading">简报不存在</div>

  const editionLabel = report.edition === 'morning' ? '🌅 早间版' : '🌙 晚间版'
  const editionClass = report.edition === 'morning' ? 'tag tag-morning' : 'tag tag-evening'

  // 转为北京时间 (UTC+8)
  const beijingTime = (dateStr: string) => {
    const d = new Date(dateStr)
    d.setHours(d.getHours() + 8)
    return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
  }

  return (
    <div className="detail-page">
      <Link to="/" className="back-btn">← 返回列表</Link>

      <article className="report-article">
        <header className="article-header">
          <div className="meta">
            <span className={editionClass}>{editionLabel}</span>
            <span className="time">{beijingTime(report.createdAt)}</span>
          </div>
          <h1>{report.title}</h1>
        </header>

        <div className="article-content">
          <ReactMarkdown>{report.content}</ReactMarkdown>
        </div>

        {report.runId && (
          <footer className="article-footer">
            <span>来源：GitHub Actions Run #{report.runId}</span>
          </footer>
        )}
      </article>
    </div>
  )
}
