import { useEffect, useState } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { ConfigProvider, Pagination } from 'antd'
import type { PaginationProps } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import MarketMarkdown from '../components/MarketMarkdown'
import api from '../utils/api'
import { goBackOr } from '../utils/navigation'
import { getReportEditionInfo, reportSlotStamp } from '../utils/reportEdition'
import { useTheme } from '../context/ThemeContext'
import { getAntdTheme } from '../theme/antdTheme'
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

interface ReportNavigation {
  ids: number[]
  index: number
  total: number
}

function editionTone(className: string) {
  if (className.includes('tag-etf-evening')) return 'etf-evening'
  if (className.includes('tag-etf')) return 'etf'
  if (className.includes('tag-evening')) return 'evening'
  if (className.includes('tag-morning')) return 'morning'
  return 'other'
}

function stripLeadTitleQuote(content: string) {
  return content.replace(/^\s*>\s*\*\*[^\n*]+\*\*\s*(?:\n[ \t]*)+/, '')
}

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { theme } = useTheme()
  const [report, setReport] = useState<Report | null>(null)
  const [navigation, setNavigation] = useState<ReportNavigation | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setReport(null)
    setNavigation(null)
    setLoading(true)
    api.get(`/reports/${id}`)
      .then(r => setReport(r.data?.data))
      .catch(() => {/* 401 已由 interceptor 处理 */})
      .finally(() => setLoading(false))
  }, [id])

  // 同版次的简报导览：总数、当前位置、有序 id，用于上一篇 / 下一篇与页码跳转。
  useEffect(() => {
    api.get(`/reports/${id}/navigation`)
      .then(r => {
        const data = r.data?.data
        if (!data || !Array.isArray(data.ids)) {
          setNavigation(null)
          return
        }
        const ids = data.ids.map(Number).filter((value: number) => Number.isFinite(value))
        setNavigation({
          ids,
          index: Number(data.index) || 0,
          total: Number(data.total) || ids.length,
        })
      })
      .catch(() => setNavigation(null))
  }, [id])

  const goBack = () => {
    // 回到真正打开这份简报的页面（带上原来的筛选与页码），没有上一页时才回历史简报。
    goBackOr(navigate, '/reports')
  }

  const openReport = (reportId: number | undefined | null) => {
    if (!reportId || reportId === report?.id) return
    // 用 replace 翻页：连翻多篇后按返回仍然回到打开它的那个列表页。
    navigate(`/report/${reportId}`, { replace: true })
  }

  const pagerIndex = navigation && navigation.index > 0 ? navigation.index : 0
  const pagerAvailable = !!navigation && pagerIndex > 0 && navigation.ids.length > 1
  const prevId = pagerAvailable ? navigation!.ids[pagerIndex - 2] : undefined
  const nextId = pagerAvailable ? navigation!.ids[pagerIndex] : undefined

  const renderPagerItem: PaginationProps['itemRender'] = (_, type, originalElement) => {
    if ((type === 'prev' || type === 'next') && originalElement) {
      return type === 'prev' ? '‹ 上一篇' : '下一篇 ›'
    }
    return originalElement
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
    <ConfigProvider locale={zhCN} theme={getAntdTheme(theme)}>
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
            <p className="detail-kicker">简报正文</p>
            <h1>{report.title}</h1>
          </header>

          <div className="article-content">
            <MarketMarkdown>{stripLeadTitleQuote(report.content || '')}</MarketMarkdown>
          </div>

          {pagerAvailable && (
            <div className="report-pager">
              <Pagination
                className="report-pager-pages"
                current={pagerIndex}
                pageSize={1}
                total={navigation.total}
                showSizeChanger={false}
                showQuickJumper
                showTotal={total => `共 ${total} 篇`}
                itemRender={renderPagerItem}
                onChange={page => openReport(navigation.ids[page - 1])}
              />
            </div>
          )}
        </article>

        {pagerAvailable && (
          <>
            <button
              type="button"
              className="report-side-arrow report-side-prev"
              onClick={() => openReport(prevId)}
              disabled={!prevId}
              title="上一篇（较新的简报）"
              aria-label="上一篇（较新的简报）"
            >
              ‹
            </button>
            <button
              type="button"
              className="report-side-arrow report-side-next"
              onClick={() => openReport(nextId)}
              disabled={!nextId}
              title="下一篇（更早的简报）"
              aria-label="下一篇（更早的简报）"
            >
              ›
            </button>
          </>
        )}
      </div>
    </ConfigProvider>
  )
}
