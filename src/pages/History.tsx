import { useState, useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { DatePicker, Input, ConfigProvider, theme } from 'antd'
import type { Dayjs } from 'dayjs'
import zhCN from 'antd/locale/zh_CN'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import './History.css'

const { RangePicker } = DatePicker

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
}

export default function History() {
  const navigate = useNavigate()
  const [pageData, setPageData] = useState<PageData | null>(null)
  const [loading, setLoading] = useState(true)
  const [edition, setEdition] = useState<'' | 'morning' | 'evening'>('')
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [keyword, setKeyword] = useState('')
  const [debouncedKeyword, setDebouncedKeyword] = useState('')

  useEffect(() => {
    const t = setTimeout(() => setDebouncedKeyword(keyword.trim()), 300)
    return () => clearTimeout(t)
  }, [keyword])

  const queryString = useMemo(() => {
    const p = new URLSearchParams({ page: '1', size: '50' })
    if (edition) p.set('edition', edition)
    if (range?.[0]) p.set('startDate', range[0]!.format('YYYY-MM-DD'))
    if (range?.[1]) p.set('endDate', range[1]!.format('YYYY-MM-DD'))
    if (debouncedKeyword) p.set('keyword', debouncedKeyword)
    return p.toString()
  }, [edition, range, debouncedKeyword])

  useEffect(() => {
    setLoading(true)
    api.get(`/reports?${queryString}`)
      .then(res => {
        if (res.data?.code === 200 && res.data.data) {
          setPageData({ records: res.data.data.records || [], total: res.data.data.total || 0 })
        }
      })
      .catch(() => {/* ignore */})
      .finally(() => setLoading(false))
  }, [queryString])

  const isMorning = (e: string) => e === 'morning'
  const reports = pageData?.records ?? []
  const total = pageData?.total ?? 0

  const hasFilter = edition !== '' || !!range?.[0] || !!range?.[1] || !!debouncedKeyword

  const resetFilters = () => {
    setEdition('')
    setRange(null)
    setKeyword('')
  }

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: { colorPrimary: '#00d4aa', colorBgContainer: '#161b22', colorBorder: '#30363d' }
      }}
    >
      <div className="history-page">
        <div className="history-header">
          <div className="header-info">
            <h2>📋 历史简报</h2>
            <p className="header-desc">查看所有已生成的 AI 简报</p>
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
              onClick={() => setEdition('')}
            >全部</button>
            <button
              className={`history-chip ${edition === 'morning' ? 'active' : ''}`}
              onClick={() => setEdition('morning')}
            >🌅 早间版</button>
            <button
              className={`history-chip ${edition === 'evening' ? 'active' : ''}`}
              onClick={() => setEdition('evening')}
            >🌙 晚间版</button>
          </div>

          <RangePicker
            value={range as any}
            onChange={(v) => setRange(v as any)}
            placeholder={['开始日期', '结束日期']}
            allowClear
            className="history-range"
          />

          <Input.Search
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
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
        ) : reports.length === 0 ? (
          <div className="empty-state">
            <p>{hasFilter ? '当前条件下暂无匹配的简报' : '暂无历史简报'}</p>
          </div>
        ) : (
          <div className="reports-list">
            {reports.map(report => (
              <div key={report.id} className="report-card" onClick={() => navigate(`/report/${report.id}`)}>
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
                    <span className="report-date">{dayjs(report.createdAt).format('YYYY-MM-DD')}</span>
                    <span className="report-time">{dayjs(report.createdAt).format('HH:mm')}</span>
                  </div>
                </div>
                <div className="report-arrow">→</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </ConfigProvider>
  )
}
