import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import api from '../utils/api'
import './Subscription.css'

interface SubscriptionData {
  receiveTime?: string
  preferenceFields: string[]
  enabled: boolean
  morningEnabled: boolean
  morningTime: string   // "HH:mm"
  eveningEnabled: boolean
  eveningTime: string
}

const FIELD_OPTIONS = [
  'AI大模型', 'Web开发', '移动端', '云原生', '数据库',
  '安全', 'DevOps', '数据分析', '机器学习', '区块链'
]

const toHHmm = (s?: string) => {
  if (!s) return ''
  // 后端可能返回 "HH:mm:ss"，剪成 "HH:mm"
  return s.length >= 5 ? s.slice(0, 5) : s
}

export default function Subscription() {
  const [data, setData] = useState<SubscriptionData>({
    preferenceFields: [],
    enabled: true,
    morningEnabled: true,
    morningTime: '08:00',
    eveningEnabled: true,
    eveningTime: '20:00',
  })
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [channelCount, setChannelCount] = useState<number | null>(null)

  useEffect(() => {
    api.get('/subscription')
      .then(res => {
        const d = res.data?.data
        if (d) {
          setData({
            receiveTime: d.receiveTime,
            preferenceFields: d.preferenceFields || [],
            enabled: d.enabled ?? true,
            morningEnabled: d.morningEnabled ?? true,
            morningTime: toHHmm(d.morningTime) || '08:00',
            eveningEnabled: d.eveningEnabled ?? true,
            eveningTime: toHHmm(d.eveningTime) || '20:00',
          })
        }
      })
      .catch(err => {
        if (err?.response?.status === 401) {
          setMessage('❌ 登录状态已失效，请重新登录')
          return
        }
        console.error(err)
      })
      .finally(() => setLoading(false))

    // 拉推送渠道数量
    api.get('/channels')
      .then(res => setChannelCount((res.data?.data || []).length))
      .catch(() => setChannelCount(null))
  }, [])

  const handleFieldToggle = (field: string) => {
    setData(prev => ({
      ...prev,
      preferenceFields: prev.preferenceFields.includes(field)
        ? prev.preferenceFields.filter(f => f !== field)
        : [...prev.preferenceFields, field]
    }))
  }

  const handleSave = async () => {
    setSaving(true)
    setMessage('')
    try {
      const res = await api.put('/subscription', data)
      if (res.data?.code === 200) setMessage('✅ 保存成功！')
      else setMessage('❌ 保存失败：' + (res.data?.message || ''))
    } catch (e: any) {
      setMessage('❌ 请求失败：' + (e?.response?.data?.message || e?.message || ''))
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="subscription-page">
      <div className="page-header">
        <h2>📬 订阅管理</h2>
        <p className="page-desc">设置接收时间、偏好领域，以及推送到哪里</p>
      </div>

      {/* 早间版 */}
      <div className="section">
        <h3>🌅 早间版</h3>
        <div className="edition-row">
          <label className="toggle">
            <input
              type="checkbox"
              checked={data.morningEnabled}
              onChange={(e) => setData({ ...data, morningEnabled: e.target.checked })}
            />
            <span className="slider"></span>
            <span className="toggle-label">{data.morningEnabled ? '已开启' : '已关闭'}</span>
          </label>
          <div className="time-picker">
            <span className="time-label">推送时间</span>
            <input
              type="time"
              value={data.morningTime}
              disabled={!data.morningEnabled}
              onChange={(e) => setData({ ...data, morningTime: e.target.value })}
            />
          </div>
        </div>
      </div>

      {/* 晚间版 */}
      <div className="section">
        <h3>🌙 晚间版</h3>
        <div className="edition-row">
          <label className="toggle">
            <input
              type="checkbox"
              checked={data.eveningEnabled}
              onChange={(e) => setData({ ...data, eveningEnabled: e.target.checked })}
            />
            <span className="slider"></span>
            <span className="toggle-label">{data.eveningEnabled ? '已开启' : '已关闭'}</span>
          </label>
          <div className="time-picker">
            <span className="time-label">推送时间</span>
            <input
              type="time"
              value={data.eveningTime}
              disabled={!data.eveningEnabled}
              onChange={(e) => setData({ ...data, eveningTime: e.target.value })}
            />
          </div>
        </div>
      </div>

      {/* 偏好领域 */}
      <div className="section">
        <h3>🏷️ 偏好领域（可多选）</h3>
        <div className="field-grid">
          {FIELD_OPTIONS.map(field => (
            <button
              key={field}
              className={data.preferenceFields.includes(field) ? 'field-tag selected' : 'field-tag'}
              onClick={() => handleFieldToggle(field)}
            >
              {field}
            </button>
          ))}
        </div>
      </div>

      {/* 总开关 */}
      <div className="section">
        <h3>⚡ 订阅总开关</h3>
        <label className="toggle">
          <input
            type="checkbox"
            checked={data.enabled}
            onChange={(e) => setData({ ...data, enabled: e.target.checked })}
          />
          <span className="slider"></span>
          <span className="toggle-label">{data.enabled ? '✅ 订阅已启用' : '⏸️ 订阅已暂停'}</span>
        </label>
      </div>

      {/* 推送渠道预览 */}
      <div className="section">
        <h3>📡 推送渠道</h3>
        <div className="channel-preview">
          <div>
            {channelCount === null && <span style={{ color: '#8b949e' }}>请先登录查看</span>}
            {channelCount === 0 && <span style={{ color: '#8b949e' }}>你还没配置任何渠道，简报无法推送</span>}
            {channelCount !== null && channelCount > 0 && (
              <span>已配置 <b style={{ color: '#00d4aa' }}>{channelCount}</b> 个推送渠道</span>
            )}
          </div>
          <Link to="/notifications" className="back-btn" style={{ marginBottom: 0 }}>
            管理推送渠道 →
          </Link>
        </div>
      </div>

      <button className="save-btn" onClick={handleSave} disabled={saving}>
        {saving ? '保存中...' : '保存设置'}
      </button>

      {message && <div className="message">{message}</div>}
    </div>
  )
}
