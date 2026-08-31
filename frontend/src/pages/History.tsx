import { cloneElement, isValidElement, useState, useEffect, useMemo } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { DatePicker, Input, ConfigProvider, Pagination, theme } from 'antd'
import type { PaginationProps } from 'antd'
import type { Dayjs } from 'dayjs'
import zhCN from 'antd/locale/zh_CN'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import { getReportEditionInfo, reportSlotStamp } from '../utils/reportEdition'
import { useAuth } from '../context/AuthContext'
import './History.css'

const { RangePicker } = DatePicker
const PAGE_SIZE = 10

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  createdAt: string
  displayTime?: string
  reportDate?: string
}

interface PageData {
  records: Report[]
  total: number
  pages: number
  current: number
  size: number
}

export default function History() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const isAdmin = user?.role === 'ADMIN'
  const canSeePublicDigest = isDemo || isAdmin
  const navigate = useNavigate()
  const [pageData, setPageData] = useState<PageData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState(1)
  const [retryKey, setRetryKey] = useState(0)
  const [edition, setEdition] = useState('')
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [keyword, setKeyword] = useState('')
  const [debouncedKeyword, setDebouncedKeyword] = useState('')

  useEffect(() => {
    const t = setTimeout(() => {
      setDebouncedKeyword(keyword.trim())
      setPage(1)
    }, 300)
    return () => clearTimeout(t)
  }, [keyword])

  useEffect(() => {
    if (!canSeePublicDigest && edition.startsWith('market_watch')) {
      setEdition('')
      setPage(1)
    }
  }, [canSeePublicDigest, edition])

  const queryString = useMemo(() => {
    const p = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) })
    if (edition) p.set('edition', edition)
    if (range?.[0]) p.set('startDate', range[0].format('YYYY-MM-DD'))
    if (range?.[1]) p.set('endDate', range[1].format('YYYY-MM-DD'))
    if (debouncedKeyword) p.set('keyword', debouncedKeyword)
    return p.toString()
  }, [page, edition, range, debouncedKeyword])

  useEffect(() => {
    const controller = new AbortController()

    setLoading(true)
    setError('')
    api.get(`/reports?${queryString}`, { signal: controller.signal })
      .then(res => {
        if (controller.signal.aborted) return

        if (res.data?.code !== 200 || !res.data.data) {
          setPageData(null)
          setError(res.data?.message || '历史简报加载失败，请稍后重试')
          return
        }

        const data = res.data.data
        const nextPageData: PageData = {
          records: data.records ?? [],
          total: data.total ?? 0,
          pages: data.pages ?? 0,
          current: data.current ?? page,
          size: data.size ?? PAGE_SIZE,
        }

        if (nextPageData.total > 0 && nextPageData.records.length === 0 && page > nextPageData.pages) {
          setPage(Math.max(nextPageData.pages, 1))
          return
        }

        setPageData(nextPageData)
      })
      .catch(err => {
        if (controller.signal.aborted) return
        setPageData(null)
        setError(err?.response?.data?.message || '历史简报加载失败，请稍后重试')
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })

    return () => controller.abort()
  }, [queryString, retryKey])

  const reports = pageData?.records ?? []
  const total = pageData?.total ?? 0

  const hasFilter = edition !== '' || !!range?.[0] || !!range?.[1] || !!debouncedKeyword

  const changeEdition = (nextEdition: string) => {
    setEdition(nextEdition)
    setPage(1)
  }

  const changeRange = (nextRange: [Dayjs | null, Dayjs | null] | null) => {
    setRange(nextRange)
    setPage(1)
  }

  const changeKeyword = (nextKeyword: string) => {
    setKeyword(nextKeyword)
  }

  const resetFilters = () => {
    setEdition('')
    setRange(null)
    setKeyword('')
    setDebouncedKeyword('')
    setPage(1)
  }

  const renderReportCard = (report: Report) => {
    const editionInfo = getReportEditionInfo(report.edition, report.displayTime)
    return (
      <div key={report.id} className="report-card" onClick={() => navigate(`/report/${report.id}`)}>
        <div className="report-icon">{editionInfo.icon}</div>
        <div className="report-content">
          <div className="report-meta">
            <span className="report-category">{editionInfo.label}</span>
            <span className="report-version">{editionInfo.version}</span>
          </div>
          <h3 className="report-title">{report.title}</h3>
          <p className="report-summary">{report.summary}</p>
          <div className="report-footer">
            <span className="report-date">{reportSlotStamp(report, 'YYYY-MM-DD')}</span>
            <span className="report-time">{reportSlotStamp(report, 'HH:mm')}</span>
          </div>
        </div>
        <div className="report-arrow">→</div>
      </div>
    )
  }

  const renderPaginationItem: PaginationProps['itemRender'] = (_, type, originalElement) => {
    if ((type === 'prev' || type === 'next') && isValidElement(originalElement)) {
      return cloneElement(originalElement, {}, type === 'prev' ? '上一页' : '下一页')
    }
    return originalElement
  }

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#8b9cff',
          colorPrimaryHover: '#a8b2ff',
          colorBgBase: '#05070d',
          colorBgContainer: '#0d111b',
          colorBgElevated: '#111620',
          colorBorder: 'rgba(255, 255, 255, 0.14)',
          colorText: '#f4f7fb',
          colorTextSecondary: '#9aa4b5',
          colorTextPlaceholder: '#667085',
          borderRadius: 12,
        }
      }}
    >
      <div className="history-page">
        <div className="history-header">
          <div className="header-info">
            <h2>📋 历史简报</h2>
            <p className="header-desc">{isDemo ? '查看和筛选已生成的 AI 简报与市场观察' : isAdmin ? '未筛选时上面是公共日报，下面是你的个人简报。也可以用标签单独查看。' : '我的简报只显示你勾选过的主题'}</p>
          </div>
          <div className="header-stats">
            <div className="stat-badge">
              <span className="stat-number">{total}</span>
              <span className="stat-label">{hasFilter ? '筛选结果' : '总计'}</span>
            </div>
          </div>
        </div>

        <div className="history-toolbar">
          <div className="history-filter-group">
            <button
              className={`history-chip ${edition === '' ? 'active' : ''}`}
              onClick={() => changeEdition('')}
            >全部</button>
            {canSeePublicDigest && (
              <>
                <button
                  className={`history-chip ${edition === 'morning' ? 'active' : ''}`}
                  onClick={() => changeEdition('morning')}
                >🌅 AI 早报</button>
                <button
                  className={`history-chip ${edition === 'evening' ? 'active' : ''}`}
                  onClick={() => changeEdition('evening')}
                >🌙 AI 晚报</button>
              </>
            )}
            {!isDemo && (
              <button
                className={`history-chip ${edition === 'personal' ? 'active' : ''}`}
                onClick={() => changeEdition('personal')}
              >✨ 我的简报</button>
            )}
            {canSeePublicDigest && (
              <>
                <button
                  className={`history-chip ${edition === 'market_watch_morning' ? 'active' : ''}`}
                  onClick={() => changeEdition('market_watch_morning')}
                >📈 市场观察·早间</button>
                <button
                  className={`history-chip ${edition === 'market_watch_evening' ? 'active' : ''}`}
                  onClick={() => changeEdition('market_watch_evening')}
                >📊 市场观察·晚间</button>
              </>
            )}
          </div>

          <RangePicker
            value={range as any}
            onChange={(v) => changeRange(v as [Dayjs | null, Dayjs | null] | null)}
            placeholder={['开始日期', '结束日期']}
            allowClear
            className="history-range"
          />

          <Input.Search
            value={keyword}
            onChange={(e) => changeKeyword(e.target.value)}
            placeholder="搜索标题或摘要"
            allowClear
            className="history-search"
          />

          {hasFilter && (
            <button className="history-reset" onClick={resetFilters}>清空</button>
          )}
        </div>

        {loading ? (
          <div className="loading-spinner">
            <div className="spinner"></div>
            <p>加载中...</p>
          </div>
        ) : error ? (
          <div className="history-error">
            <p>{error}</p>
            <button type="button" onClick={() => setRetryKey(value => value + 1)}>重新加载</button>
          </div>
        ) : reports.length === 0 ? (
          <div className="empty-state">
            <p>{hasFilter ? '当前条件下暂无匹配的简报' : (isDemo ? '暂无历史简报' : isAdmin ? '暂无公共简报或你的简报' : '暂无属于你的简报，请先勾选兴趣并等到生成时刻')}</p>
            {!hasFilter && !isDemo && (
              <Link to="/subscription" className="history-empty-link">去订阅管理勾选兴趣</Link>
            )}
          </div>
        ) : (
          <>
            {isAdmin && !edition ? (
              <>
                <div className="history-group">
                  <h3>公共日报</h3>
                  <div className="reports-list">
                    {reports.filter(report => report.edition !== 'personal').map(renderReportCard)}
                  </div>
                  {reports.every(report => report.edition === 'personal') && (
                    <div className="empty-state"><p>当前页没有公共日报</p></div>
                  )}
                </div>
                <div className="history-group personal">
                  <h3>我的个人简报</h3>
                  <div className="reports-list">
                    {reports.filter(report => report.edition === 'personal').map(renderReportCard)}
                  </div>
                  {reports.every(report => report.edition !== 'personal') && (
                    <div className="empty-state"><p>当前页没有个人简报</p></div>
                  )}
                </div>
              </>
            ) : (
              <div className="reports-list">
                {reports.map(renderReportCard)}
              </div>
            )}
            <Pagination
              className="history-pagination"
              current={pageData?.current ?? page}
              pageSize={PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              itemRender={renderPaginationItem}
              onChange={setPage}
            />
          </>
        )}
      </div>
    </ConfigProvider>
  )
}
