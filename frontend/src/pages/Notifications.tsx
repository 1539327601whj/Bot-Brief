import { useEffect, useState } from 'react'
import api from '../utils/api'
import dayjs from '../utils/dayjs'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs } from '../demo/fixtures'
import './Notifications.css'

interface PushLog {
  id: number
  reportId: number
  channelId: number
  channelType: string
  status: 'success' | 'failed'
  errorMessage: string | null
  pushedAt: string
}

const TYPE_ICON: Record<string, string> = {
  email: '✉️', wechat: '💬', dingtalk: '🔔', feishu: '🚀'
}

export default function Notifications() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [logs, setLogs] = useState<PushLog[]>([])
  const [loading, setLoading] = useState(true)

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

  return (
    <div className="notifications-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>🔔 通知记录</h2>
        <p className="page-desc">你的推送历史，包含成功与失败原因</p>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : logs.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🕊️</div>
          <p>暂无推送记录</p>
          <p className="hint">配置好推送渠道并等定时任务触发，或到"推送渠道"页做一次测试推送</p>
        </div>
      ) : (
        <div className="log-list">
          {logs.map(l => (
            <div key={l.id} className={`log-row ${l.status}`}>
              <div className="log-icon">{TYPE_ICON[l.channelType] || '📨'}</div>
              <div className="log-info">
                <div className="log-title">
                  <span className="log-type">{l.channelType}</span>
                  <span className={`log-status ${l.status}`}>
                    {l.status === 'success' ? '✅ 成功' : '❌ 失败'}
                  </span>
                  <span className="log-time">{dayjs(l.pushedAt).format('MM-DD HH:mm')}</span>
                </div>
                <div className="log-meta">
                  报告 #{l.reportId} → 渠道 #{l.channelId}
                </div>
                {l.errorMessage && (
                  <div className="log-error">{l.errorMessage}</div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
