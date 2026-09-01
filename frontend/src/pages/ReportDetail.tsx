import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import MarketMarkdown from '../components/MarketMarkdown'
import api from '../utils/api'
import { getReportEditionInfo, reportSlotStamp } from '../utils/reportEdition'
import './ReportDetail.css'

interface Report {
  id: number
  edition: string
  title: string
  content: string
  summary: string
  runId: string
  createdAt: string
  displayTime?: string
  reportDate?: string
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

  const editionInfo = getReportEditionInfo(report.edition, report.displayTime, report.title)
  const editionLabel = `${editionInfo.icon} ${editionInfo.label}`
  const editionClass = editionInfo.className

  return (
    <div className="detail-page">
      <Link to="/" className="back-btn">← 返回列表</Link>

      <article className="report-article">
        <header className="article-header">
          <div className="meta">
            <span className={editionClass}>{editionLabel}</span>
            <span className="time">{reportSlotStamp(report, 'YYYY-MM-DD HH:mm')}</span>
          </div>
          <h1>{report.title}</h1>
        </header>

        <div className="article-content">
          <MarketMarkdown>{report.content}</MarketMarkdown>
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
