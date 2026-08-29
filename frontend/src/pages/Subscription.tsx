import { useEffect, useMemo, useState } from 'react'
import { ConfigProvider, TimePicker, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { Link } from 'react-router-dom'
import dayjs from '../utils/dayjs'
import api, { TOKEN_KEY } from '../utils/api'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoChannels, demoSubscription } from '../demo/fixtures'
import './Subscription.css'

type ChannelType = 'email' | 'wechat' | 'dingtalk' | 'feishu'

interface Channel {
  id: number
  channelType: ChannelType
  displayName?: string
  enabled: boolean
}

interface TopicScheduleItem {
  topic: string
  enabled: boolean
  time: string
  channelIds: number[]
}

interface SubscriptionData {
  enabled: boolean
  topicSchedules: { items: TopicScheduleItem[] }
}

const FIELD_OPTIONS = [
  'AI大模型', 'Web开发', '移动端', '云原生', '数据库',
  '安全', 'DevOps', '数据分析', '机器学习', '区块链'
]
const MAX_INTERESTS = 20
const MAX_INTEREST_LENGTH = 40
const DEFAULT_TIME = '08:15'
const TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/
const WINDOWS = ['w00_06', 'w06_12', 'w12_18', 'w18_24'] as const
const WINDOW_DEFAULTS: Record<string, string> = {
  w00_06: '03:00',
  w06_12: '08:15',
  w12_18: '14:00',
  w18_24: '20:15',
}
const CHANNEL_META: Record<ChannelType, string> = {
  email: '邮箱',
  wechat: '企业微信',
  dingtalk: '钉钉',
  feishu: '飞书',
}

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

const toHHmm = (value?: string) => {
  const normalized = value ? value.slice(0, 5) : ''
  return TIME_PATTERN.test(normalized) ? normalized : DEFAULT_TIME
}
const windowOf = (value: string) => {
  const hour = Number(toHHmm(value).slice(0, 2))
  if (hour < 6) return 'w00_06'
  if (hour < 12) return 'w06_12'
  if (hour < 18) return 'w12_18'
  return 'w18_24'
}
const normalizeInterest = (value: string) => value.trim().replace(/\s+/g, ' ')
const interestKey = (topic: string) => topic.toLocaleLowerCase()
const isPreset = (topic: string) => FIELD_OPTIONS.some(option => interestKey(option) === interestKey(topic))
const interestLength = (value: string) => Array.from(value).length

const flattenItems = (source: any): TopicScheduleItem[] => {
  const raw = source?.topicSchedules
  const rows: any[] = Array.isArray(raw?.items)
    ? raw.items
    : [...(raw?.morning || []), ...(raw?.evening || [])]
  const used = new Set<string>()
  const items: TopicScheduleItem[] = []
  rows.forEach((row: any) => {
    const topic = normalizeInterest(row?.topic || '')
    if (!topic) return
    const time = toHHmm(row?.time)
    const key = `${interestKey(topic)}|${windowOf(time)}`
    if (used.has(key)) return
    used.add(key)
    items.push({
      topic,
      enabled: Boolean(row?.enabled),
      time,
      channelIds: (Array.isArray(row?.channelIds) ? row.channelIds : [])
        .filter((id: unknown): id is number => Number.isInteger(id) && Number(id) > 0),
    })
  })
  return items
}

const normalizeSubscription = (source: any): SubscriptionData => ({
  enabled: source?.enabled ?? true,
  topicSchedules: { items: flattenItems(source) },
})

export default function Subscription() {
  const { user, authReady } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [data, setData] = useState<SubscriptionData>(normalizeSubscription({}))
  const [channels, setChannels] = useState<Channel[]>([])
  const [customInput, setCustomInput] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState<'ok' | 'error'>('ok')

  const showMessage = (text: string, type: 'ok' | 'error' = 'ok') => {
    setMessageType(type)
    setMessage(text)
  }

  useEffect(() => {
    if (!authReady) return
    if (isDemo) {
      setData(normalizeSubscription(demoSubscription))
      setChannels(demoChannels)
      setLoading(false)
      return
    }
    setLoading(true)
    Promise.all([
      api.get('/subscription'),
      api.get('/channels'),
    ]).then(([subscriptionRes, channelRes]) => {
      if (subscriptionRes.data?.code === 401) {
        showMessage('登录已过期，请退出后重新登录再保存', 'error')
        return
      }
      if (subscriptionRes.data?.data) setData(normalizeSubscription(subscriptionRes.data.data))
      setChannels(channelRes.data?.data || [])
    }).catch(err => {
      if (err?.response?.status === 401) showMessage('登录已过期，请退出后重新登录再保存', 'error')
      else console.error(err)
    }).finally(() => setLoading(false))
  }, [isDemo, authReady])

  const items = data.topicSchedules.items
  const topics = useMemo(() => {
    const names = [...FIELD_OPTIONS]
    items.forEach(item => {
      if (!names.some(name => interestKey(name) === interestKey(item.topic))) names.push(item.topic)
    })
    return names
  }, [items])
  const enabledTopics = useMemo(() => {
    const unique = new Map<string, string>()
    items.filter(item => item.enabled).forEach(item => unique.set(interestKey(item.topic), item.topic))
    return Array.from(unique.values())
  }, [items])
  const enabledSlots = items.filter(item => item.enabled)
  const channelsByType = useMemo(() => {
    const grouped: Partial<Record<ChannelType, Channel[]>> = {}
    channels.forEach(channel => {
      grouped[channel.channelType] = [...(grouped[channel.channelType] || []), channel]
    })
    return grouped
  }, [channels])

  const topicItems = (topic: string) => items.filter(item => interestKey(item.topic) === interestKey(topic))
  const topicEnabled = (topic: string) => topicItems(topic).some(item => item.enabled)

  const updateItems = (next: TopicScheduleItem[]) => {
    if (isDemo) return
    setData(prev => ({ ...prev, topicSchedules: { items: next } }))
  }

  const toggleTopic = (topic: string, enabled: boolean) => {
    const existing = topicItems(topic)
    if (enabled && existing.length === 0) {
      updateItems([...items, { topic, enabled: true, time: DEFAULT_TIME, channelIds: [] }])
      return
    }
    updateItems(items.map(item => interestKey(item.topic) === interestKey(topic) ? { ...item, enabled } : item))
  }

  const updateSlot = (index: number, patch: Partial<TopicScheduleItem>) => {
    updateItems(items.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item))
  }

  const changeSlotTime = (index: number, time: string) => {
    const current = items[index]
    const clash = items.some((item, itemIndex) => (
      itemIndex !== index
      && interestKey(item.topic) === interestKey(current.topic)
      && windowOf(item.time) === windowOf(time)
    ))
    if (clash) {
      showMessage('同一主题在同一时间段只能订阅一次', 'error')
      return
    }
    setMessage('')
    updateSlot(index, { time })
  }

  const addSlot = (topic: string) => {
    const used = new Set(topicItems(topic).map(item => windowOf(item.time)))
    const nextWindow = WINDOWS.find(window => !used.has(window))
    if (!nextWindow) {
      showMessage('同一主题每天最多四个时间段', 'error')
      return
    }
    setMessage('')
    updateItems([...items, { topic, enabled: true, time: WINDOW_DEFAULTS[nextWindow], channelIds: [] }])
  }

  const removeSlot = (index: number) => {
    updateItems(items.filter((_, itemIndex) => itemIndex !== index))
  }

  const bindChannel = (index: number, type: ChannelType, channelId: number | null) => {
    const current = items[index]
    const others = current.channelIds.filter(id => {
      const channel = channels.find(item => item.id === id)
      return channel && channel.channelType !== type
    })
    updateSlot(index, { channelIds: channelId ? [...others, channelId] : others })
  }

  const addCustomInterest = () => {
    if (isDemo) return
    const topic = normalizeInterest(customInput)
    if (!topic) return showMessage('请输入感兴趣的内容', 'error')
    if (interestLength(topic) > MAX_INTEREST_LENGTH) return showMessage(`每个兴趣不能超过 ${MAX_INTEREST_LENGTH} 个字符`, 'error')
    const exists = topics.some(name => interestKey(name) === interestKey(topic))
    if (!exists && enabledTopics.length >= MAX_INTERESTS && !topics.includes(topic)) {
      const uniqueCount = new Set(topics.map(interestKey)).size
      if (uniqueCount >= MAX_INTERESTS) return showMessage(`兴趣总数不能超过 ${MAX_INTERESTS} 个`, 'error')
    }
    if (!exists && new Set([...topics, topic].map(interestKey)).size > MAX_INTERESTS) {
      return showMessage(`兴趣总数不能超过 ${MAX_INTERESTS} 个`, 'error')
    }
    if (!topicItems(topic).length) {
      updateItems([...items, { topic, enabled: true, time: DEFAULT_TIME, channelIds: [] }])
    } else {
      toggleTopic(topic, true)
    }
    setCustomInput('')
    setMessage('')
  }

  const removeCustomInterest = (topic: string) => {
    if (isDemo || isPreset(topic)) return
    updateItems(items.filter(item => interestKey(item.topic) !== interestKey(topic)))
  }

  const handleSave = async () => {
    if (isDemo) return
    if (!localStorage.getItem(TOKEN_KEY)) {
      showMessage('登录已过期，请退出后重新登录再保存', 'error')
      return
    }
    setSaving(true)
    setMessage('')
    try {
      const payload = {
        enabled: data.enabled,
        preferenceFields: enabledTopics,
        topicSchedules: { items },
      }
      const res = await api.put('/subscription', payload)
      if (res.data?.code === 200) {
        setData(normalizeSubscription(res.data.data || payload))
        showMessage('设置已保存。到点后按你选的时刻展示和推送')
      } else if (res.data?.code === 401) {
        showMessage('登录已过期，请退出后重新登录再保存', 'error')
      } else {
        showMessage('保存失败：' + (res.data?.message || ''), 'error')
      }
    } catch (error: any) {
      const status = error?.response?.status
      const detail = error?.response?.data?.message || error?.message || ''
      if (status === 401 || detail.includes('token')) {
        showMessage('登录已过期，请退出后重新登录再保存', 'error')
      } else {
        showMessage('请求失败：' + detail, 'error')
      }
    } finally {
      setSaving(false)
    }
  }

  if (!authReady || loading) return <div className="loading">加载中...</div>

  return (
    <ConfigProvider locale={zhCN} theme={subscriptionTheme}>
    <div className="subscription-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>订阅管理</h2>
        <p className="page-desc">勾选兴趣并设定每天的时刻。同一主题在同一 6 小时时段只生成一次，你的简报按自己选的时间展示。</p>
      </div>

      <div className="subscription-summary">
        <div><span className="summary-label">已订阅兴趣</span><strong>{enabledTopics.length}</strong></div>
        <div><span className="summary-label">推送时刻</span><strong>{new Set(enabledSlots.map(item => item.time)).size}</strong></div>
        <div><span className="summary-label">渠道绑定</span><strong>{enabledSlots.filter(item => item.channelIds.length > 0).length}</strong></div>
      </div>

      <div className="section">
        <div className="section-title-row">
          <h3>兴趣主题</h3>
          <span className="section-count">已选 {enabledTopics.length} 个</span>
        </div>
        <div className="topic-actions">
          <button type="button" disabled={isDemo} onClick={() => FIELD_OPTIONS.forEach(topic => { if (!topicEnabled(topic)) toggleTopic(topic, true) })}>全选常用</button>
          <button type="button" disabled={isDemo} onClick={() => updateItems(items.map(item => ({ ...item, enabled: false })))}>清空勾选</button>
        </div>
        <div className="topic-schedule-list">
          {topics.map(topic => (
            <div key={topic} className={topicEnabled(topic) ? 'topic-schedule-row active' : 'topic-schedule-row'}>
              <label className="topic-check">
                <input
                  type="checkbox"
                  checked={topicEnabled(topic)}
                  disabled={isDemo}
                  onChange={event => toggleTopic(topic, event.target.checked)}
                />
                <span>{topic}</span>
                {!isPreset(topic) && <small>自定义</small>}
              </label>
              {!isPreset(topic) && (
                <button type="button" className="remove-interest" disabled={isDemo} onClick={() => removeCustomInterest(topic)}>删除</button>
              )}
            </div>
          ))}
        </div>
        <div className="custom-interest-row">
          <input
            type="text"
            value={customInput}
            maxLength={MAX_INTEREST_LENGTH * 2}
            disabled={isDemo}
            placeholder="添加自定义兴趣，如：足球"
            onChange={event => setCustomInput(event.target.value)}
            onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); addCustomInterest() } }}
          />
          <button type="button" disabled={isDemo} onClick={addCustomInterest}>添加兴趣</button>
        </div>
      </div>

      <div className="section schedule-section">
        <div className="section-title-row">
          <div>
            <h3>推送时间</h3>
            <p className="section-sub">每个主题每天一个或多个时刻。到点后网页和渠道同时更新；没绑渠道就只在网页看。</p>
          </div>
          <span className="section-count">{enabledSlots.length} 个时刻</span>
        </div>
        {enabledTopics.length === 0 ? (
          <div className="schedule-empty">先在上面勾选兴趣，再设置每天几点推送</div>
        ) : (
          <div className="schedule-list">
            {enabledTopics.map(topic => {
              const slots = items
                .map((item, index) => ({ item, index }))
                .filter(({ item }) => item.enabled && interestKey(item.topic) === interestKey(topic))
              return (
                <div key={topic} className="schedule-topic">
                  {slots.map(({ item, index }, slotIndex) => (
                    <div key={`${topic}-${index}`} className="schedule-row">
                      <div className="schedule-name">
                        <strong>{topic}</strong>
                        <span>{slotIndex === 0 ? '每天推送' : `第 ${slotIndex + 1} 个时段`}</span>
                        {!isPreset(topic) && <small>自定义</small>}
                      </div>
                      <div className="schedule-time">
                        <TimePicker
                          className="subscription-time-picker"
                          popupClassName="subscription-time-popup"
                          value={dayjs(`2000-01-01T${item.time}:00`)}
                          format="HH:mm"
                          minuteStep={1}
                          allowClear={false}
                          showNow={false}
                          inputReadOnly
                          disabled={isDemo}
                          onChange={value => { if (value) changeSlotTime(index, value.format('HH:mm')) }}
                        />
                      </div>
                      <span className={item.channelIds.length ? 'schedule-badge bound' : 'schedule-badge'}>
                        {item.channelIds.length ? `已绑 ${item.channelIds.length} 个渠道` : '仅网页'}
                      </span>
                      <div className="schedule-actions">
                        {slotIndex === 0 && (
                          <button type="button" className="ghost-link" disabled={isDemo || slots.length >= 4} onClick={() => addSlot(topic)}>再加时段</button>
                        )}
                        {slots.length > 1 && (
                          <button type="button" className="remove-interest" disabled={isDemo} onClick={() => removeSlot(index)}>删除</button>
                        )}
                      </div>
                      {channels.length > 0 && (
                        <div className="channel-binds">
                          {(Object.keys(CHANNEL_META) as ChannelType[]).map(type => {
                            const options = channelsByType[type] || []
                            if (options.length === 0) return null
                            const selected = item.channelIds.find(id => options.some(channel => channel.id === id)) || ''
                            return (
                              <label key={type} className="channel-bind">
                                <span>{CHANNEL_META[type]}</span>
                                <select
                                  value={selected}
                                  disabled={isDemo}
                                  onChange={event => bindChannel(index, type, event.target.value ? Number(event.target.value) : null)}
                                >
                                  <option value="">不推送</option>
                                  {options.map(channel => (
                                    <option key={channel.id} value={channel.id}>
                                      {channel.displayName || CHANNEL_META[type]}{channel.enabled ? '' : '（已暂停）'}
                                    </option>
                                  ))}
                                </select>
                              </label>
                            )
                          })}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )
            })}
          </div>
        )}
      </div>

      <div className="section">
        <h3>订阅总开关</h3>
        <label className="toggle">
          <input type="checkbox" checked={data.enabled} disabled={isDemo} onChange={event => { if (!isDemo) setData(prev => ({ ...prev, enabled: event.target.checked })) }} />
          <span className="slider"></span>
          <span className="toggle-label">{data.enabled ? '订阅已启用' : '订阅已暂停'}</span>
        </label>
      </div>

      <div className="section">
        <h3>推送渠道通讯录</h3>
        <div className="channel-preview">
          <div>
            {channels.length === 0 && <span style={{ color: '#8b949e' }}>还没有账号。不绑定也可以，简报只出现在网页。</span>}
            {channels.length > 0 && <span>已保存 <b style={{ color: '#00d4aa' }}>{channels.length}</b> 个账号，每个主题每种方式最多绑一个</span>}
          </div>
          <Link to="/channels" className="back-btn" style={{ marginBottom: 0 }}>管理通讯录 →</Link>
        </div>
      </div>

      <p className="interest-help">公共主题从当日资讯池筛选；自定义兴趣会按词单独检索。同一时间段里多人勾选同一词只生成一次。当天没有内容时跳过该段。</p>
      <button className="save-btn" onClick={handleSave} disabled={isDemo || saving}>{saving ? '保存中...' : '保存设置'}</button>
      {message && <div className={`message ${messageType}`}>{message}</div>}
    </div>
    </ConfigProvider>
  )
}
