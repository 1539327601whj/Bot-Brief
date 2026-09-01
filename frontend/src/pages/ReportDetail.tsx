import { useEffect, useState } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
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

function editionTone(className: string) {
  if (className.includes('tag-etf-evening')) return 'etf-evening'
  if (className.includes('tag-etf')) return 'etf'
  if (className.includes('tag-evening')) return 'evening'
  if (className.includes('tag-morning')) return 'morning'
  return 'other'
}

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [report, setReport] = useState<Report | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.get(`/reports/${id}`)
      .then(r => setReport(r.data?.data))
      .catch(() => {/* 401 已由 interceptor 处理 */})
      .finally(() => setLoading(false))
  }, [id])

  const goBack = () => {
    if (window.history.length > 1) navigate(-1)
    else navigate('/')
  }

  if (loading) {
    return (
      <div className="detail-page">
        <div className="detail-state">正在打开简报…</div>
      </div>
    )
  }

  if (!report) {
    return (
      <div className="detail-page">
        <div className="detail-state">
          <strong>简报不存在或已失效</strong>
          <span>可能已被删除，或当前账号看不到这份内容。</span>
          <Link to="/">回到首页</Link>
        </div>
      </div>
    )
  }

  const editionInfo = getReportEditionInfo(report.edition, report.displayTime, report.title)
  const tone = editionTone(editionInfo.className)

  return (
    <div className="detail-page">
      <div className="detail-toolbar">
        <button type="button" className="detail-back" onClick={goBack}>← 返回</button>
        <nav className="detail-crumbs">
          <Link to="/">首页概览</Link>
          <span>/</span>
          <Link to="/reports">历史简报</Link>
        </nav>
      </div>

      <article className={`report-article tone-${tone}`}>
        <header className="article-header">
          <div className="meta">
            <span className={editionInfo.className}>{editionInfo.icon} {editionInfo.label}</span>
            <span className="detail-version">{editionInfo.version}</span>
            <span className="time">{reportSlotStamp(report, 'YYYY-MM-DD HH:mm')}</span>
          </div>
          <h1>{report.title}</h1>
        </header>

        <div className="article-content">
          <MarketMarkdown>{report.content}</MarketMarkdown>
        </div>
      </article>
    </div>
  )
}
