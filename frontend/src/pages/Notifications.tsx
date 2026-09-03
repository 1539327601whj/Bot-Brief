import { useEffect, useMemo, useState } from 'react'
import api from '../utils/api'
import { parseBeijing } from '../utils/dayjs'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs } from '../demo/fixtures'
import { channelLabel, dispatchKeyOf, pushKindFromDispatchKey } from '../utils/pushDisplay'
import './Notifications.css'

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
  email: '✉️', wechat: '💬', dingtalk: '🔔', feishu: '🚀'
}

type Filter = 'all' | 'scheduled' | 'test' | 'success' | 'failed'

export default function Notifications() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [logs, setLogs] = useState<PushLog[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [filter, setFilter] = useState<Filter>('all')

  useEffect(() => {
    if (isDemo) {
      setLogs(demoPushLogs)
      setLoading(false)
      return
    }
    api.get('/push-logs', { params: { limit: 200 } })
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

  const visible = useMemo(
    () => logs.filter(log => {
      const kind = pushKindFromDispatchKey(dispatchKeyOf(log)).kind
      if (filter === 'scheduled') return kind === 'scheduled'
      if (filter === 'test') return kind === 'test'
      if (filter === 'success' || filter === 'failed') return log.status === filter
      return true
    }),
    [logs, filter]
  )

  return (
    <div className="notifications-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>🔔 通知记录</h2>
        <p className="page-desc">测试推送和按订阅时刻投递会分开标注，方便对照哪一次是试推</p>
      </div>

      <div className="log-filters">
        <button className={filter === 'all' ? 'active' : ''} onClick={() => setFilter('all')}>全部 {logs.length}</button>
        <button className={filter === 'scheduled' ? 'active' : ''} onClick={() => setFilter('scheduled')}>订阅投递 {scheduledCount}</button>
        <button className={filter === 'test' ? 'active' : ''} onClick={() => setFilter('test')}>测试推送 {testCount}</button>
        <button className={filter === 'success' ? 'active' : ''} onClick={() => setFilter('success')}>成功 {logs.length - failedCount}</button>
        <button className={filter === 'failed' ? 'active' : ''} onClick={() => setFilter('failed')}>失败 {failedCount}</button>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : visible.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🕊️</div>
          <p>{loadError || emptyHint(filter)}</p>
          <p className="hint">在「推送渠道」点测试，或等到订阅时刻自动投递后，都会出现在这里。</p>
        </div>
      ) : (
        <div className="log-list">
          {visible.map(l => {
            const kind = kindOf(l)
            return (
              <div key={l.id} className={`log-row ${l.status}`}>
                <div className="log-icon">{TYPE_ICON[l.channelType] || '📨'}</div>
                <div className="log-info">
                  <div className="log-title">
                    <span className="log-type">{channelLabel(l.channelType)}</span>
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
      )}
    </div>
  )
}

function emptyHint(filter: Filter) {
  if (filter === 'failed') return '没有失败记录'
  if (filter === 'scheduled') return '还没有按订阅时刻投递的记录'
  if (filter === 'test') return '还没有测试推送记录'
  return '暂无推送记录'
}
