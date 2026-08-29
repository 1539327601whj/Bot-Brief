import { useEffect, useState } from 'react'
import { ConfigProvider, TimePicker, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { Link } from 'react-router-dom'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoChannels, demoSubscription } from '../demo/fixtures'
import './Subscription.css'

interface TopicScheduleItem {
  topic: string
  enabled: boolean
  channelIds?: number[] | null
}

interface TopicSchedules {
  morning: TopicScheduleItem[]
  evening: TopicScheduleItem[]
}

interface SubscriptionData {
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
const MAX_INTERESTS = 20
const MAX_INTEREST_LENGTH = 40
const DEFAULT_TIMES = { morning: '08:15', evening: '20:15' } as const
const TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/

const subscriptionTheme = {
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
    borderRadius: 12,
    boxShadowSecondary: '0 24px 80px rgba(0, 0, 0, 0.48)',
  },
}

const toHHmm = (value?: string) => value ? value.slice(0, 5) : ''
const timeToMinutes = (value: string) => {
  if (!TIME_PATTERN.test(value)) return null
  const [hours, minutes] = value.split(':').map(Number)
  return hours * 60 + minutes
}
const isTimeInEdition = (value: string, edition: keyof TopicSchedules) => {
  const minutes = timeToMinutes(value)
  if (minutes === null) return false
  return edition === 'morning' ? minutes < 15 * 60 : minutes >= 15 * 60
}
const normalizeEditionTime = (value: string | undefined, edition: keyof TopicSchedules) => {
  const normalized = toHHmm(value)
  return isTimeInEdition(normalized, edition) ? normalized : DEFAULT_TIMES[edition]
}
const timeRangeLabel = (edition: keyof TopicSchedules) => edition === 'morning' ? '00:00–14:59' : '15:00–23:59'
const normalizeInterest = (value: string) => value.trim().replace(/\s+/g, ' ')
const interestKey = (topic: string) => topic.toLocaleLowerCase()
const isPreset = (topic: string) => FIELD_OPTIONS.some(option => interestKey(option) === interestKey(topic))
const interestLength = (value: string) => Array.from(value).length

const mergeTopicItems = (items: TopicScheduleItem[] | undefined, selected: string[]) => {
  const existing = new Map<string, TopicScheduleItem>()
  ;(items || []).forEach(item => {
    const topic = normalizeInterest(item.topic || '')
    if (topic) existing.set(interestKey(topic), {
      topic,
      enabled: Boolean(item.enabled),
      channelIds: item.channelIds == null ? item.channelIds : [...new Set(item.channelIds.filter(id => Number.isInteger(id) && id > 0))],
    })
  })
  if (items === undefined) {
    selected.forEach(topicValue => {
      const topic = normalizeInterest(topicValue)
      const key = interestKey(topic)
      if (topic && !existing.has(key)) existing.set(key, { topic, enabled: true, channelIds: null })
    })
  }

  const topics = [...FIELD_OPTIONS]
  existing.forEach(item => {
    if (!topics.some(topic => interestKey(topic) === interestKey(item.topic))) topics.push(item.topic)
  })
  return topics.map(topic => existing.get(interestKey(topic)) || { topic, enabled: false, channelIds: null })
}

const collectPreferenceFields = (topicSchedules: TopicSchedules) => {
  const fields = new Map<string, string>()
  ;[...topicSchedules.morning, ...topicSchedules.evening].forEach(item => {
    if (item.enabled) fields.set(interestKey(item.topic), item.topic)
  })
  return Array.from(fields.values())
}

const normalizeSubscription = (source: any): SubscriptionData => {
  const preferenceFields = source?.preferenceFields || []
  return {
    enabled: source?.enabled ?? true,
    morningEnabled: source?.morningEnabled ?? true,
    morningTime: normalizeEditionTime(source?.morningTime, 'morning'),
    eveningEnabled: source?.eveningEnabled ?? true,
    eveningTime: normalizeEditionTime(source?.eveningTime, 'evening'),
    topicSchedules: {
      morning: mergeTopicItems(source?.topicSchedules?.morning, preferenceFields),
      evening: mergeTopicItems(source?.topicSchedules?.evening, preferenceFields),
    },
  }
}

const repairedTimeEditions = (source: any) => (['morning', 'evening'] as const).filter(edition => {
  const value = edition === 'morning' ? source?.morningTime : source?.eveningTime
  return Boolean(value) && !isTimeInEdition(toHHmm(value), edition)
})

export default function Subscription() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [data, setData] = useState<SubscriptionData>(normalizeSubscription({}))
  const [customInputs, setCustomInputs] = useState({ morning: '', evening: '' })
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [timeWarning, setTimeWarning] = useState('')
  const [channelCount, setChannelCount] = useState<number | null>(null)

  useEffect(() => {
    if (isDemo) {
      setData(normalizeSubscription(demoSubscription))
      setChannelCount(demoChannels.length)
      setLoading(false)
      return
    }
    api.get('/subscription')
      .then(res => {
        const subscription = res.data?.data
        if (subscription) {
          const repaired = repairedTimeEditions(subscription)
          setData(normalizeSubscription(subscription))
          if (repaired.length > 0) {
            const labels = repaired.map(edition => edition === 'morning' ? '早报' : '晚报').join('和')
            setTimeWarning(`检测到${labels}的 Web 展示时间不符合时段规则，已恢复为默认时间，请确认后保存。`)
          }
        }
      })
      .catch(err => {
        if (err?.response?.status === 401) setMessage('登录状态已失效，请重新登录')
        else console.error(err)
      })
      .finally(() => setLoading(false))

    api.get('/channels')
      .then(res => setChannelCount((res.data?.data || []).length))
      .catch(() => setChannelCount(null))
  }, [isDemo])

  const updateTopic = (edition: keyof TopicSchedules, topic: string, enabled: boolean) => {
    if (isDemo) return
    setData(prev => ({
      ...prev,
      topicSchedules: {
        ...prev.topicSchedules,
        [edition]: prev.topicSchedules[edition].map(item => item.topic === topic ? { ...item, enabled } : item),
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

  const addCustomInterest = (edition: keyof TopicSchedules) => {
    if (isDemo) return
    const topic = normalizeInterest(customInputs[edition])
    if (!topic) return setMessage('请输入感兴趣的内容')
    if (interestLength(topic) > MAX_INTEREST_LENGTH) return setMessage(`每个兴趣不能超过 ${MAX_INTEREST_LENGTH} 个字符`)

    const allTopics = new Map<string, string>()
    ;[...data.topicSchedules.morning, ...data.topicSchedules.evening].forEach(item => allTopics.set(interestKey(item.topic), item.topic))
    const key = interestKey(topic)
    if (!allTopics.has(key) && allTopics.size >= MAX_INTERESTS) return setMessage(`兴趣总数不能超过 ${MAX_INTERESTS} 个`)

    setData(prev => ({
      ...prev,
      topicSchedules: {
        morning: upsertInterest(prev.topicSchedules.morning, topic, edition === 'morning'),
        evening: upsertInterest(prev.topicSchedules.evening, topic, edition === 'evening'),
      },
    }))
    setCustomInputs(prev => ({ ...prev, [edition]: '' }))
    setMessage('')
  }

  const removeCustomInterest = (topic: string) => {
    if (isDemo || isPreset(topic)) return
    setData(prev => ({
      ...prev,
      topicSchedules: {
        morning: prev.topicSchedules.morning.filter(item => item.topic !== topic),
        evening: prev.topicSchedules.evening.filter(item => item.topic !== topic),
      },
    }))
  }

  const handleSave = async () => {
    if (isDemo) return
    setMessage('')
    if (!isTimeInEdition(data.morningTime, 'morning')) {
      setMessage(`早报 Web 展示时间只能选择 ${timeRangeLabel('morning')}`)
      return
    }
    if (!isTimeInEdition(data.eveningTime, 'evening')) {
      setMessage(`晚报 Web 展示时间只能选择 ${timeRangeLabel('evening')}`)
      return
    }
    setSaving(true)
    try {
      const payload = { ...data, preferenceFields: collectPreferenceFields(data.topicSchedules) }
      const res = await api.put('/subscription', payload)
      if (res.data?.code === 200) {
        setData(normalizeSubscription(res.data.data || payload))
        setTimeWarning('')
        setMessage('设置已保存。早报和晚报只会显示你勾选的主题')
      } else setMessage('保存失败：' + (res.data?.message || ''))
    } catch (error: any) {
      setMessage('请求失败：' + (error?.response?.data?.message || error?.message || ''))
    } finally {
      setSaving(false)
    }
  }

  const renderEditionSection = (
    edition: keyof TopicSchedules,
    title: string,
    enabled: boolean,
    displayTime: string,
    onEnabledChange: (enabled: boolean) => void,
  ) => {
    const selected = data.topicSchedules[edition].filter(item => item.enabled).length
    const isMorning = edition === 'morning'
    return (
      <div className={`section edition-section ${edition}`}>
        <div className="section-title-row">
          <div className="edition-heading">
            <span className="edition-icon" aria-hidden="true">{isMorning ? '☀' : '☾'}</span>
            <div>
              <h3>{title}</h3>
              <span>{isMorning ? '零点至下午三点前' : '下午三点至午夜前'}</span>
            </div>
          </div>
          <span className="section-count">已选 {selected} 个兴趣</span>
        </div>
        <div className="edition-row">
          <label className="toggle edition-toggle">
            <input type="checkbox" checked={enabled} disabled={isDemo} onChange={event => onEnabledChange(event.target.checked)} />
            <span className="slider"></span>
            <span className="toggle-copy">
              <strong>{enabled ? '订阅已开启' : '订阅已关闭'}</strong>
              <small>{enabled ? '到点后在首页展示对应内容' : '该版次不会生成或展示内容'}</small>
            </span>
          </label>
          <div className={`time-picker ${enabled ? '' : 'disabled'}`}>
            <div className="time-picker-copy">
              <span className="time-label">Web 展示时间</span>
              <small>可选 {timeRangeLabel(edition)}</small>
            </div>
            <TimePicker
              className="subscription-time-picker"
              popupClassName="subscription-time-popup"
              value={dayjs(`2000-01-01T${displayTime}:00`)}
              format="HH:mm"
              minuteStep={1}
              allowClear={false}
              showNow={false}
              hideDisabledOptions
              inputReadOnly
              disabled={isDemo || !enabled}
              disabledTime={() => ({
                disabledHours: () => isMorning
                  ? Array.from({ length: 9 }, (_, index) => index + 15)
                  : Array.from({ length: 15 }, (_, index) => index),
              })}
              onChange={value => {
                if (!value) return
                const nextTime = value.format('HH:mm')
                setData(prev => isMorning
                  ? { ...prev, morningTime: nextTime }
                  : { ...prev, eveningTime: nextTime })
              }}
            />
          </div>
        </div>
        <div className="topic-actions">
          <button type="button" onClick={() => setEditionTopics(edition, true)} disabled={isDemo || !enabled}>全选</button>
          <button type="button" onClick={() => setEditionTopics(edition, false)} disabled={isDemo || !enabled}>清空</button>
        </div>
        <div className="topic-schedule-list">
          {data.topicSchedules[edition].map(item => (
            <div key={`${edition}-${item.topic}`} className={item.enabled ? 'topic-schedule-row active' : 'topic-schedule-row'}>
              <label className="topic-check">
                <input
                  type="checkbox"
                  checked={item.enabled}
                  disabled={isDemo || !enabled}
                  onChange={event => updateTopic(edition, item.topic, event.target.checked)}
                />
                <span>{item.topic}</span>
                {!isPreset(item.topic) && <small>自定义</small>}
              </label>
              {!isPreset(item.topic) && (
                <button type="button" className="remove-interest" disabled={isDemo} onClick={() => removeCustomInterest(item.topic)}>删除</button>
              )}
            </div>
          ))}
        </div>
        <div className="custom-interest-row">
          <input
            type="text"
            value={customInputs[edition]}
            maxLength={MAX_INTEREST_LENGTH * 2}
            disabled={isDemo || !enabled}
            placeholder="添加自定义兴趣，如：具身智能"
            onChange={event => setCustomInputs(prev => ({ ...prev, [edition]: event.target.value }))}
            onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); addCustomInterest(edition) } }}
          />
          <button type="button" disabled={isDemo || !enabled} onClick={() => addCustomInterest(edition)}>添加兴趣</button>
        </div>
        <p className="interest-help">只有勾选的主题会出现在网页和推送里。当天某个主题没有内容时，这一段会跳过，不会改发完整公共简报。</p>
      </div>
    )
  }

  if (loading) return <div className="loading">加载中...</div>

  const morningSelected = data.topicSchedules.morning.filter(item => item.enabled).length
  const eveningSelected = data.topicSchedules.evening.filter(item => item.enabled).length
  const totalTopics = collectPreferenceFields(data.topicSchedules).length

  return (
    <ConfigProvider locale={zhCN} theme={subscriptionTheme}>
    <div className="subscription-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>订阅管理</h2>
        <p className="page-desc">勾选兴趣后，早报和晚报只包含这些主题；未勾选的内容不会出现</p>
      </div>

      <div className="subscription-summary">
        <div><span className="summary-label">已订阅兴趣</span><strong>{totalTopics}</strong></div>
        <div><span className="summary-label">早间日报</span><strong>{morningSelected}</strong></div>
        <div><span className="summary-label">晚间日报</span><strong>{eveningSelected}</strong></div>
      </div>

      {renderEditionSection('morning', '早间日报', data.morningEnabled, data.morningTime, enabled => { if (!isDemo) setData(prev => ({ ...prev, morningEnabled: enabled })) })}
      {renderEditionSection('evening', '晚间日报', data.eveningEnabled, data.eveningTime, enabled => { if (!isDemo) setData(prev => ({ ...prev, eveningEnabled: enabled })) })}

      <div className="section">
        <h3>订阅总开关</h3>
        <label className="toggle">
          <input type="checkbox" checked={data.enabled} disabled={isDemo} onChange={event => { if (!isDemo) setData(prev => ({ ...prev, enabled: event.target.checked })) }} />
          <span className="slider"></span>
          <span className="toggle-label">{data.enabled ? '订阅已启用' : '订阅已暂停'}</span>
        </label>
      </div>

      <div className="section">
        <h3>推送渠道</h3>
        <div className="channel-preview">
          <div>
            {channelCount === null && <span style={{ color: '#8b949e' }}>请先登录查看</span>}
            {channelCount === 0 && <span style={{ color: '#8b949e' }}>你还没配置任何渠道，简报无法推送</span>}
            {channelCount !== null && channelCount > 0 && <span>已配置 <b style={{ color: '#00d4aa' }}>{channelCount}</b> 个推送渠道</span>}
          </div>
          <Link to="/channels" className="back-btn" style={{ marginBottom: 0 }}>管理推送渠道 →</Link>
        </div>
      </div>

      {timeWarning && <div className="time-warning">{timeWarning}</div>}
      <button className="save-btn" onClick={handleSave} disabled={isDemo || saving}>{saving ? '保存中...' : '保存设置'}</button>
      {message && <div className="message">{message}</div>}
    </div>
    </ConfigProvider>
  )
}

function upsertInterest(items: TopicScheduleItem[], topic: string, enabled: boolean) {
  const existing = items.find(item => interestKey(item.topic) === interestKey(topic))
  if (existing) return items.map(item => item === existing ? { ...item, enabled: item.enabled || enabled } : item)
  return [...items, { topic, enabled }]
}
