import React, { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import './Subscription.css'

interface SubscriptionData {
  receiveTime: string
  preferenceFields: string[]
  enabled: boolean
}

const FIELD_OPTIONS = [
  'AI大模型', 'Web开发', '移动端', '云原生', '数据库',
  '安全', 'DevOps', '数据分析', '机器学习', '区块链'
]

export default function Subscription() {
  const navigate = useNavigate()
  const [data, setData] = useState<SubscriptionData>({
    receiveTime: 'both',
    preferenceFields: [],
    enabled: true
  })
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')

  useEffect(() => {
    fetch('/api/subscription')
      .then(r => r.json())
      .then(res => {
        if (res.data) {
          setData(res.data)
        }
      })
      .catch(console.error)
      .finally(() => setLoading(false))
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
      const res = await fetch('/api/subscription', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
      })
      const result = await res.json()
      if (result.code === 200) {
        setMessage('✅ 保存成功！')
      } else {
        setMessage('❌ 保存失败：' + result.message)
      }
    } catch (e) {
      setMessage('❌ 请求失败')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="subscription-page">
      <div className="page-header">
        <h2>📬 订阅管理</h2>
        <p className="page-desc">设置您的简报接收时间和偏好领域</p>
      </div>

      {/* 接收时间 */}
      <div className="section">
        <h3>🕐 接收时间</h3>
        <div className="radio-group">
          <label className={data.receiveTime === 'morning' ? 'radio-card active' : 'radio-card'}>
            <input
              type="radio"
              name="receiveTime"
              value="morning"
              checked={data.receiveTime === 'morning'}
              onChange={() => setData({ ...data, receiveTime: 'morning' })}
            />
            🌅 仅早间版（08:00）
          </label>
          <label className={data.receiveTime === 'evening' ? 'radio-card active' : 'radio-card'}>
            <input
              type="radio"
              name="receiveTime"
              value="evening"
              checked={data.receiveTime === 'evening'}
              onChange={() => setData({ ...data, receiveTime: 'evening' })}
            />
            🌙 仅晚间版（20:00）
          </label>
          <label className={data.receiveTime === 'both' ? 'radio-card active' : 'radio-card'}>
            <input
              type="radio"
              name="receiveTime"
              value="both"
              checked={data.receiveTime === 'both'}
              onChange={() => setData({ ...data, receiveTime: 'both' })}
            />
            ⏰ 早晚都要
          </label>
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

      {/* 启用开关 */}
      <div className="section">
        <h3>⚡ 订阅状态</h3>
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

      {/* 保存按钮 */}
      <button className="save-btn" onClick={handleSave} disabled={saving}>
        {saving ? '保存中...' : '保存设置'}
      </button>

      {message && <div className="message">{message}</div>}
    </div>
  )
}