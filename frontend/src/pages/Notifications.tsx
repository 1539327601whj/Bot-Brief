import { cloneElement, isValidElement, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ConfigProvider, DatePicker, Pagination, Select, theme } from 'antd'
import type { PaginationProps } from 'antd'
import type { Dayjs } from 'dayjs'
import zhCN from 'antd/locale/zh_CN'
import api from '../utils/api'
import { parseBeijing } from '../utils/dayjs'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs, demoTodayStatus } from '../demo/fixtures'
import { CHANNEL_LABEL, channelLabel, dispatchKeyOf, missKey, missStamp, pushKindFromDispatchKey, visibleGenerationMisses, type TodayProgress, type TopicProgressItem } from '../utils/pushDisplay'
import './Notifications.css'

const { RangePicker } = DatePicker
const PAGE_SIZE = 10
const CHANNEL_OPTIONS = (Object.keys(CHANNEL_LABEL) as Array<keyof typeof CHANNEL_LABEL>).map(value => ({
  value,
  label: CHANNEL_LABEL[value],
}))

interface PushLog {
  id: number
  reportId: number
  channelId: number
  channelType: string
  status: 'success' | 'failed' | 'sending'
  errorMessage: string | null
  dispatchKey?: string | null
  dispatch_key?: string | null
  pushedAt: string
}

const TYPE_ICON: Record<string, string> = {
  email: '✉️', wechat: '💬', dingtalk: '🔔', feishu: '🚀',
}

type Filter = 'all' | 'scheduled' | 'test' | 'success' | 'failed' | 'unwritten'

const FILTERS: Filter[] = ['all', 'scheduled', 'test', 'success', 'failed', 'unwritten']

function parseFilter(value: string | null): Filter {
  return FILTERS.includes(value as Filter) ? value as Filter : 'all'
}

const notificationTheme = {
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
  },
}

export default function Notifications() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [searchParams, setSearchParams] = useSearchParams()
  const [logs, setLogs] = useState<PushLog[]>([])
  const [misses, setMisses] = useState<TopicProgressItem[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [filter, setFilter] = useState<Filter>(() => parseFilter(searchParams.get('filter')))
  const [channelType, setChannelType] = useState<string>('')
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [page, setPage] = useState(1)

  useEffect(() => {
    setFilter(parseFilter(searchParams.get('filter')))
  }, [searchParams])

  useEffect(() => {
    if (isDemo) {
      setLogs(demoPushLogs)
      setMisses(visibleGenerationMisses(demoTodayStatus))
      setLoading(false)
      return
    }
    Promise.allSettled([
      api.get('/push-logs', { params: { limit: 500 } }),
      api.get('/subscription/today-status'),
    ])
      .then(([logRes, statusRes]) => {
        if (logRes.status === 'fulfilled' && logRes.value.data?.code === 200) {
          setLogs(logRes.value.data?.data || [])
          setLoadError('')
        } else {
          setLogs([])
          setLoadError(logRes.status === 'fulfilled'
            ? (logRes.value.data?.message || '推送记录加载失败')
            : '推送记录加载失败')
        }
        if (statusRes.status === 'fulfilled' && statusRes.value.data?.code === 200) {
          setMisses(visibleGenerationMisses((statusRes.value.data.data || { items: [] }) as TodayProgress))
        } else {
          setMisses([])
        }
      })
      .finally(() => setLoading(false))
  }, [isDemo])

  const kindOf = (log: PushLog) => pushKindFromDispatchKey(dispatchKeyOf(log))
  const scheduledCount = logs.filter(log => kindOf(log).kind === 'scheduled').length
  const testCount = logs.filter(log => kindOf(log).kind === 'test').length
  const failedCount = logs.filter(log => log.status === 'failed').length

  const visibleLogs = useMemo(() => logs.filter(log => {
    const kind = pushKindFromDispatchKey(dispatchKeyOf(log)).kind
    if (filter === 'unwritten') return false
    if (filter === 'scheduled' && kind !== 'scheduled') return false
    if (filter === 'test' && kind !== 'test') return false
    if ((filter === 'success' || filter === 'failed') && log.status !== filter) return false
    if (channelType && log.channelType !== channelType) return false
    if (range?.[0] || range?.[1]) {
      const pushed = parseBeijing(log.pushedAt).tz('Asia/Shanghai')
      if (range[0] && pushed.isBefore(range[0].startOf('day'))) return false
      if (range[1] && pushed.isAfter(range[1].endOf('day'))) return false
    }
    return true
  }), [logs, filter, channelType, range])

  const visibleMisses = useMemo(() => misses.filter(item => {
    if (range?.[0] || range?.[1]) {
      if (!item.date) return false
      if (range[0] && item.date < range[0].format('YYYY-MM-DD')) return false
      if (range[1] && item.date > range[1].format('YYYY-MM-DD')) return false
    }
    return true
  }), [misses, range])

  const showingMisses = filter === 'unwritten'
  const visibleCount = showingMisses ? visibleMisses.length : visibleLogs.length
  const totalPages = Math.max(1, Math.ceil(visibleCount / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages)
  const pagedLogs = visibleLogs.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)
  const pagedMisses = visibleMisses.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)
  const hasExtraFilter = Boolean((!showingMisses && channelType) || range?.[0] || range?.[1])

  const changeFilter = (next: Filter) => {
    setFilter(next)
    setPage(1)
    const nextParams = new URLSearchParams(searchParams)
    if (next === 'all') nextParams.delete('filter')
    else nextParams.set('filter', next)
    setSearchParams(nextParams, { replace: true })
  }
  const changeChannel = (value: string | undefined) => {
    setChannelType(value || '')
    setPage(1)
  }
  const changeRange = (value: [Dayjs | null, Dayjs | null] | null) => {
    setRange(value)
    setPage(1)
  }
  const resetExtraFilters = () => {
    setChannelType('')
    setRange(null)
    setPage(1)
  }

  const renderPaginationItem: PaginationProps['itemRender'] = (_, type, originalElement) => {
    if ((type === 'prev' || type === 'next') && isValidElement(originalElement)) {
      return cloneElement(originalElement, {}, type === 'prev' ? '上一页' : '下一页')
    }
    return originalElement
  }

  return (
    <ConfigProvider locale={zhCN} theme={notificationTheme}>
    <div className="notifications-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>🔔 通知记录</h2>
        <p className="page-desc">测试推送、订阅投递和未写成的日报会分开标注。未生成里能看到主题和原因。可按时间段筛选，每页 10 条。</p>
      </div>

      <div className="log-toolbar">
        <div className="log-filters">
          <button className={filter === 'all' ? 'active' : ''} onClick={() => changeFilter('all')}>全部 {logs.length}</button>
          <button className={filter === 'scheduled' ? 'active' : ''} onClick={() => changeFilter('scheduled')}>订阅投递 {scheduledCount}</button>
          <button className={filter === 'test' ? 'active' : ''} onClick={() => changeFilter('test')}>测试推送 {testCount}</button>
          <button className={filter === 'success' ? 'active' : ''} onClick={() => changeFilter('success')}>成功 {logs.length - failedCount}</button>
          <button className={filter === 'failed' ? 'active' : ''} onClick={() => changeFilter('failed')}>失败 {failedCount}</button>
          <button className={`${filter === 'unwritten' ? 'active' : ''} ${misses.length > 0 ? 'has-miss' : ''}`} onClick={() => changeFilter('unwritten')}>未生成 {misses.length}</button>
        </div>
        <div className="log-query">
          <RangePicker
            value={range}
            onChange={value => changeRange(value as [Dayjs | null, Dayjs | null] | null)}
            placeholder={['开始日期', '结束日期']}
            allowClear
            className="log-range"
          />
          {!showingMisses && (
            <Select
              className="log-channel"
              value={channelType || undefined}
              onChange={changeChannel}
              allowClear
              placeholder="全部推送软件"
              options={CHANNEL_OPTIONS}
            />
          )}
          {hasExtraFilter && (
            <button type="button" className="log-reset" onClick={resetExtraFilters}>清除条件</button>
          )}
        </div>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : visibleCount === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🕊️</div>
          <p>{loadError || emptyHint(filter, hasExtraFilter)}</p>
          <p className="hint">{showingMisses ? '到点没写成的订阅会出现在这里，首页「哪条没写成」只留最近 4 条。' : '在「推送渠道」点测试，或等到订阅时刻自动投递后，都会出现在这里。'}</p>
        </div>
      ) : (
        <>
          <div className="log-list">
            {showingMisses ? pagedMisses.map(item => (
              <div key={missKey(item)} className="log-row unwritten">
                <div className="log-icon">📭</div>
                <div className="log-info">
                  <div className="log-title">
                    <span className="log-type channel-unknown">{item.topic}</span>
                    <span className="log-kind unwritten">订阅未生成</span>
                    <span className="log-status failed">{item.label || '未生成'}</span>
                    <span className="log-time">{missStamp(item)}</span>
                  </div>
                  <div className="log-meta">
                    预约时刻 {item.time}
                    {item.date ? ` · ${item.date}` : ''}
                  </div>
                  {item.message && <div className="log-error">{item.message}</div>}
                </div>
              </div>
            )) : pagedLogs.map(l => {
              const kind = kindOf(l)
              const channel = l.channelType || 'unknown'
              return (
                <div key={l.id} className={`log-row ${l.status}`}>
                  <div className="log-icon">{TYPE_ICON[channel] || '📨'}</div>
                  <div className="log-info">
                    <div className="log-title">
                      <span className={`log-type channel-${channel}`}>{channelLabel(channel)}</span>
                      <span className={`log-kind ${kind.kind}`}>{kind.label}</span>
                      <span className={`log-status ${l.status === 'sending' ? 'success' : l.status}`}>
                        {l.status === 'success' ? '成功' : l.status === 'sending' ? '投递中' : '失败'}
                      </span>
                      <span className="log-time">{parseBeijing(l.pushedAt).tz('Asia/Shanghai').format('MM-DD HH:mm')}</span>
                    </div>
                    <div className="log-meta">
                      {kind.slot ? `订阅时刻 ${kind.slot} · ` : ''}
                      实际投递 {parseBeijing(l.pushedAt).tz('Asia/Shanghai').format('HH:mm')}
                      {l.reportId ? ` · 简报 #${l.reportId}` : ''}
                    </div>
                    {l.errorMessage && (
                      <div className="log-error">{l.errorMessage}</div>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
          <div className="log-page-bar">
            <span className="log-page-summary">
              第 {currentPage}/{totalPages} 页，共 {visibleCount} 条
            </span>
            <Pagination
              className="log-pagination"
              current={currentPage}
              pageSize={PAGE_SIZE}
              total={visibleCount}
              showSizeChanger={false}
              showQuickJumper
              itemRender={renderPaginationItem}
              onChange={setPage}
            />
          </div>
        </>
      )}
    </div>
    </ConfigProvider>
  )
}

function emptyHint(filter: Filter, hasExtraFilter: boolean) {
  if (filter === 'unwritten') return hasExtraFilter ? '当前时间段没有未生成记录' : '近几日没有未写成的订阅'
  if (hasExtraFilter) return '当前时间段或推送软件下没有记录'
  if (filter === 'failed') return '没有失败记录'
  if (filter === 'scheduled') return '还没有按订阅时刻投递的记录'
  if (filter === 'test') return '还没有测试推送记录'
  return '暂无推送记录'
}
