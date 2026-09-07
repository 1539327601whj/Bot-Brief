import { cloneElement, isValidElement, useEffect, useMemo, useState } from 'react'
import { ConfigProvider, DatePicker, Pagination, Select, theme } from 'antd'
import type { PaginationProps } from 'antd'
import type { Dayjs } from 'dayjs'
import zhCN from 'antd/locale/zh_CN'
import api from '../utils/api'
import { parseBeijing } from '../utils/dayjs'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs } from '../demo/fixtures'
import { CHANNEL_LABEL, channelLabel, dispatchKeyOf, pushKindFromDispatchKey } from '../utils/pushDisplay'
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

type Filter = 'all' | 'scheduled' | 'test' | 'success' | 'failed'

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
  const [logs, setLogs] = useState<PushLog[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [channelType, setChannelType] = useState<string>('')
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [page, setPage] = useState(1)

  useEffect(() => {
    if (isDemo) {
      setLogs(demoPushLogs)
      setLoading(false)
      return
    }
    api.get('/push-logs', { params: { limit: 500 } })
      .then(res => {
        if (res.data?.code === 200) {
          setLogs(res.data?.data || [])
          setLoadError('')
          return
        }
        setLogs([])
        setLoadError(res.data?.message || '推送记录加载失败')
      })
      .catch(() => {
        setLogs([])
        setLoadError('推送记录加载失败')
      })
      .finally(() => setLoading(false))
  }, [isDemo])

  const kindOf = (log: PushLog) => pushKindFromDispatchKey(dispatchKeyOf(log))
  const scheduledCount = logs.filter(log => kindOf(log).kind === 'scheduled').length
  const testCount = logs.filter(log => kindOf(log).kind === 'test').length
  const failedCount = logs.filter(log => log.status === 'failed').length

  const visible = useMemo(() => logs.filter(log => {
    const kind = pushKindFromDispatchKey(dispatchKeyOf(log)).kind
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

  const totalPages = Math.max(1, Math.ceil(visible.length / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages)
  const paged = visible.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)
  const hasExtraFilter = Boolean(channelType || range?.[0] || range?.[1])

  const changeFilter = (next: Filter) => {
    setFilter(next)
    setPage(1)
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
        <p className="page-desc">测试推送和按订阅时刻投递会分开标注。可按时间段、推送软件筛选，每页 10 条。</p>
      </div>

      <div className="log-toolbar">
        <div className="log-filters">
          <button className={filter === 'all' ? 'active' : ''} onClick={() => changeFilter('all')}>全部 {logs.length}</button>
          <button className={filter === 'scheduled' ? 'active' : ''} onClick={() => changeFilter('scheduled')}>订阅投递 {scheduledCount}</button>
          <button className={filter === 'test' ? 'active' : ''} onClick={() => changeFilter('test')}>测试推送 {testCount}</button>
          <button className={filter === 'success' ? 'active' : ''} onClick={() => changeFilter('success')}>成功 {logs.length - failedCount}</button>
          <button className={filter === 'failed' ? 'active' : ''} onClick={() => changeFilter('failed')}>失败 {failedCount}</button>
        </div>
        <div className="log-query">
          <RangePicker
            value={range}
            onChange={value => changeRange(value as [Dayjs | null, Dayjs | null] | null)}
            placeholder={['开始日期', '结束日期']}
            allowClear
            className="log-range"
          />
          <Select
            className="log-channel"
            value={channelType || undefined}
            onChange={changeChannel}
            allowClear
            placeholder="全部推送软件"
            options={CHANNEL_OPTIONS}
          />
          {hasExtraFilter && (
            <button type="button" className="log-reset" onClick={resetExtraFilters}>清除条件</button>
          )}
        </div>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : visible.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🕊️</div>
          <p>{loadError || emptyHint(filter, hasExtraFilter)}</p>
          <p className="hint">在「推送渠道」点测试，或等到订阅时刻自动投递后，都会出现在这里。</p>
        </div>
      ) : (
        <>
          <div className="log-list">
            {paged.map(l => {
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
              第 {currentPage}/{totalPages} 页，共 {visible.length} 条
            </span>
            <Pagination
              className="log-pagination"
              current={currentPage}
              pageSize={PAGE_SIZE}
              total={visible.length}
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
  if (hasExtraFilter) return '当前时间段或推送软件下没有记录'
  if (filter === 'failed') return '没有失败记录'
  if (filter === 'scheduled') return '还没有按订阅时刻投递的记录'
  if (filter === 'test') return '还没有测试推送记录'
  return '暂无推送记录'
}
