import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import api from '../utils/api'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoChannels, demoSubscription } from '../demo/fixtures'
import './Subscription.css'

interface TopicScheduleItem {
  topic: string
  enabled: boolean
  time: string
}

interface TopicSchedules {
  morning: TopicScheduleItem[]
  evening: TopicScheduleItem[]
}

interface SubscriptionData {
  receiveTime?: string
  preferenceFields: string[]
  enabled: boolean
  morningEnabled: boolean
  morningTime: string
  eveningEnabled: boolean
  eveningTime: string
  topicSchedules: TopicSchedules
}

const FIELD_OPTIONS = [
  'AI大模型', 'Web开发', '移动端', '云原生', '数据库',
  '安全', 'DevOps', '数据分析', '机器学习', '区块链'
]

const toHHmm = (s?: string) => {
  if (!s) return ''
  return s.length >= 5 ? s.slice(0, 5) : s
}

const makeTopicItems = (items: TopicScheduleItem[] | undefined, selected: string[], defaultTime: string) => {
  const map = new Map((items || []).map(item => [item.topic, item]))
  return FIELD_OPTIONS.map(topic => {
    const item = map.get(topic)
    return {
      topic,
      enabled: item?.enabled ?? selected.includes(topic),
      time: toHHmm(item?.time) || defaultTime,
    }
  })
}

const collectPreferenceFields = (topicSchedules: TopicSchedules) => {
  const fields = new Set<string>()
  ;[...topicSchedules.morning, ...topicSchedules.evening].forEach(item => {
    if (item.enabled) fields.add(item.topic)
  })
  return Array.from(fields)
}

export default function Subscription() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [data, setData] = useState<SubscriptionData>({
    preferenceFields: [],
    enabled: true,
    morningEnabled: true,
    morningTime: '08:00',
    eveningEnabled: true,
    eveningTime: '20:00',
    topicSchedules: {
      morning: makeTopicItems(undefined, [], '08:00'),
      evening: makeTopicItems(undefined, [], '20:00'),
    },
  })
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [channelCount, setChannelCount] = useState<number | null>(null)

  useEffect(() => {
    if (isDemo) {
      setData(demoSubscription)
      setChannelCount(demoChannels.length)
      setLoading(false)
      return
    }
    api.get('/subscription')
      .then(res => {
        const d = res.data?.data
        if (d) {
          const preferenceFields = d.preferenceFields || []
          const morningTime = toHHmm(d.morningTime) || '08:00'
          const eveningTime = toHHmm(d.eveningTime) || '20:00'
          setData({
            receiveTime: d.receiveTime,
            preferenceFields,
            enabled: d.enabled ?? true,
            morningEnabled: d.morningEnabled ?? true,
            morningTime,
            eveningEnabled: d.eveningEnabled ?? true,
            eveningTime,
            topicSchedules: {
              morning: makeTopicItems(d.topicSchedules?.morning, preferenceFields, morningTime),
              evening: makeTopicItems(d.topicSchedules?.evening, preferenceFields, eveningTime),
            },
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
  }, [isDemo])

  const updateTopicSchedule = (edition: keyof TopicSchedules, topic: string, patch: Partial<TopicScheduleItem>) => {
    if (isDemo) return
    setData(prev => ({
      ...prev,
      topicSchedules: {
        ...prev.topicSchedules,
        [edition]: prev.topicSchedules[edition].map(item =>
          item.topic === topic ? { ...item, ...patch } : item
        ),
      },
    }))
  }

  const setEditionTopics = (edition: keyof TopicSchedules, enabled: boolean) => {
    if (isDemo) return
    setData(prev => ({
      ...prev,
      topicSchedules: {
        ...prev.topicSchedules,
        [edition]: prev.topicSchedules[edition].map(item => ({ ...item, enabled })),
      },
    }))
  }

  const applyDefaultTime = (edition: keyof TopicSchedules) => {
    if (isDemo) return
    setData(prev => {
      const defaultTime = edition === 'morning' ? prev.morningTime : prev.eveningTime
      return {
        ...prev,
        topicSchedules: {
          ...prev.topicSchedules,
          [edition]: prev.topicSchedules[edition].map(item =>
            item.enabled ? { ...item, time: defaultTime } : item
          ),
        },
      }
    })
  }

  const updateEditionDefaultTime = (edition: keyof TopicSchedules, time: string) => {
    if (isDemo) return
    setData(prev => edition === 'morning' ? { ...prev, morningTime: time } : { ...prev, eveningTime: time })
  }

  const handleSave = async () => {
    if (isDemo) return
    setSaving(true)
    setMessage('')
    try {
      const payload = {
        ...data,
        preferenceFields: collectPreferenceFields(data.topicSchedules),
      }
      const res = await api.put('/subscription', payload)
      if (res.data?.code === 200) {
        setData(payload)
        setMessage('✅ 设置已保存，新的推送时间将在下一次分钟扫描后生效')
      } else setMessage('❌ 保存失败：' + (res.data?.message || ''))
    } catch (e: any) {
      setMessage('❌ 请求失败：' + (e?.response?.data?.message || e?.message || ''))
    } finally {
      setSaving(false)
    }
  }

  const morningSelected = data.topicSchedules.morning.filter(item => item.enabled).length
  const eveningSelected = data.topicSchedules.evening.filter(item => item.enabled).length
  const totalTopics = collectPreferenceFields(data.topicSchedules).length

  const renderEditionSection = (
    edition: keyof TopicSchedules,
    title: string,
    enabled: boolean,
    defaultTime: string,
    onEnabledChange: (enabled: boolean) => void,
  ) => (
    <div className="section edition-section">
      <div className="section-title-row">
        <h3>{title}</h3>
        <span className="section-count">已选 {data.topicSchedules[edition].filter(item => item.enabled).length} 个内容</span>
      </div>
      <div className="edition-row">
        <label className="toggle">
          <input
            type="checkbox"
            checked={enabled}
            disabled={isDemo}
            onChange={(e) => onEnabledChange(e.target.checked)}
          />
          <span className="slider"></span>
          <span className="toggle-label">{enabled ? '已开启' : '已关闭'}</span>
        </label>
        <div className="time-picker">
          <span className="time-label">默认时间</span>
          <input
            type="time"
            value={defaultTime}
            disabled={isDemo || !enabled}
            onChange={(e) => updateEditionDefaultTime(edition, e.target.value)}
          />
        </div>
      </div>
      <div className="topic-actions">
        <button type="button" onClick={() => setEditionTopics(edition, true)} disabled={isDemo || !enabled}>全选内容</button>
        <button type="button" onClick={() => setEditionTopics(edition, false)} disabled={isDemo || !enabled}>清空</button>
        <button type="button" onClick={() => applyDefaultTime(edition)} disabled={isDemo || !enabled}>应用默认时间到已选</button>
      </div>
      <div className="topic-schedule-list">
        {data.topicSchedules[edition].map(item => (
          <div key={`${edition}-${item.topic}`} className={item.enabled ? 'topic-schedule-row active' : 'topic-schedule-row'}>
            <label className="topic-check">
              <input
                type="checkbox"
                checked={item.enabled}
                disabled={isDemo || !enabled}
                onChange={(e) => updateTopicSchedule(edition, item.topic, { enabled: e.target.checked })}
              />
              <span>{item.topic}</span>
            </label>
            <div className="topic-time">
              <span>推送时间</span>
              <input
                type="time"
                value={item.time}
                disabled={isDemo || !enabled || !item.enabled}
                onChange={(e) => updateTopicSchedule(edition, item.topic, { time: e.target.value })}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  )

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="subscription-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>📬 订阅管理</h2>
        <p className="page-desc">在早间日报和晚间日报里选择订阅内容，并为不同内容设置不同推送时间</p>
      </div>

      <div className="subscription-summary">
        <div>
          <span className="summary-label">已订阅内容</span>
          <strong>{totalTopics}</strong>
        </div>
        <div>
          <span className="summary-label">早间日报</span>
          <strong>{morningSelected}</strong>
        </div>
        <div>
          <span className="summary-label">晚间日报</span>
          <strong>{eveningSelected}</strong>
        </div>
      </div>

      {renderEditionSection('morning', '🌅 早间日报', data.morningEnabled, data.morningTime, (enabled) => { if (!isDemo) setData({ ...data, morningEnabled: enabled }) })}
      {renderEditionSection('evening', '🌙 晚间日报', data.eveningEnabled, data.eveningTime, (enabled) => { if (!isDemo) setData({ ...data, eveningEnabled: enabled }) })}

      {/* 总开关 */}
      <div className="section">
        <h3>⚡ 订阅总开关</h3>
        <label className="toggle">
          <input
            type="checkbox"
            checked={data.enabled}
            disabled={isDemo}
            onChange={(e) => { if (!isDemo) setData({ ...data, enabled: e.target.checked }) }}
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
          <Link to="/channels" className="back-btn" style={{ marginBottom: 0 }}>
            管理推送渠道 →
          </Link>
        </div>
      </div>

      <button className="save-btn" onClick={handleSave} disabled={isDemo || saving}>
        {saving ? '保存中...' : '保存设置'}
      </button>

      {message && <div className="message">{message}</div>}
    </div>
  )
}
