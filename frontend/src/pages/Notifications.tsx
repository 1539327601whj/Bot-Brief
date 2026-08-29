import { useEffect, useMemo, useState } from 'react'
import api from '../utils/api'
import dayjs from '../utils/dayjs'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs } from '../demo/fixtures'
import { channelLabel, slotFromDispatchKey } from '../utils/pushDisplay'
import './Notifications.css'

interface PushLog {
  id: number
  reportId: number
  channelId: number
  channelType: string
  status: 'success' | 'failed'
  errorMessage: string | null
  dispatchKey?: string | null
  pushedAt: string
}

const TYPE_ICON: Record<string, string> = {
  email: '✉️', wechat: '💬', dingtalk: '🔔', feishu: '🚀'
}

type Filter = 'all' | 'success' | 'failed'

export default function Notifications() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [logs, setLogs] = useState<PushLog[]>([])
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState<Filter>('all')

  useEffect(() => {
    if (isDemo) {
      setLogs(demoPushLogs)
      setLoading(false)
      return
    }
    api.get('/push-logs', { params: { limit: 200 } })
      .then(res => setLogs(res.data?.data || []))
      .catch(() => setLogs([]))
      .finally(() => setLoading(false))
  }, [isDemo])

  const visible = useMemo(
    () => filter === 'all' ? logs : logs.filter(log => log.status === filter),
    [logs, filter]
  )
  const failedCount = logs.filter(log => log.status === 'failed').length

  return (
    <div className="notifications-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>🔔 通知记录</h2>
        <p className="page-desc">按投递时刻排查：哪一分钟、哪个渠道、成功还是失败</p>
      </div>

      <div className="log-filters">
        <button className={filter === 'all' ? 'active' : ''} onClick={() => setFilter('all')}>全部 {logs.length}</button>
        <button className={filter === 'success' ? 'active' : ''} onClick={() => setFilter('success')}>成功 {logs.length - failedCount}</button>
        <button className={filter === 'failed' ? 'active' : ''} onClick={() => setFilter('failed')}>失败 {failedCount}</button>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : visible.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🕊️</div>
          <p>{filter === 'failed' ? '没有失败记录' : '暂无推送记录'}</p>
          <p className="hint">订阅里绑好渠道后，到点会写在这里。也可以先到「推送渠道」做一次测试推送。</p>
        </div>
      ) : (
        <div className="log-list">
          {visible.map(l => {
            const slot = slotFromDispatchKey(l.dispatchKey)
            return (
              <div key={l.id} className={`log-row ${l.status}`}>
                <div className="log-icon">{TYPE_ICON[l.channelType] || '📨'}</div>
                <div className="log-info">
                  <div className="log-title">
                    <span className="log-type">{channelLabel(l.channelType)}</span>
                    <span className={`log-status ${l.status}`}>
                      {l.status === 'success' ? '成功' : '失败'}
                    </span>
                    <span className="log-time">{dayjs(l.pushedAt).tz('Asia/Shanghai').format('MM-DD HH:mm')}</span>
                  </div>
                  <div className="log-meta">
                    {slot ? `订阅时刻 ${slot} · ` : ''}实际投递 {dayjs(l.pushedAt).tz('Asia/Shanghai').format('HH:mm')}
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
