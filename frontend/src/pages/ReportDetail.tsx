import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import { getReportEditionInfo } from '../utils/reportEdition'
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
    api.get(`/reports/${id}`)
      .then(r => setReport(r.data?.data))
      .catch(() => {/* 401 已由 interceptor 处理 */})
      .finally(() => setLoading(false))
  }, [id])

  if (loading) return <div className="loading">加载中...</div>
  if (!report) return <div className="loading">简报不存在</div>

  const editionInfo = getReportEditionInfo(report.edition)
  const editionLabel = `${editionInfo.icon} ${editionInfo.label}`
  const editionClass = editionInfo.className

  // 格式化时间（使用北京时间）
  const formatTime = (dateStr: string) => dayjs(dateStr).format('YYYY-MM-DD HH:mm')

  return (
    <div className="detail-page">
      <Link to="/" className="back-btn">← 返回列表</Link>

      <article className="report-article">
        <header className="article-header">
          <div className="meta">
            <span className={editionClass}>{editionLabel}</span>
            <span className="time">{formatTime(report.createdAt)}</span>
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
