import { useEffect, useMemo, useRef, useState } from 'react'
import { ConfigProvider, TimePicker, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { Link } from 'react-router-dom'
import dayjs from '../utils/dayjs'
import api, { TOKEN_KEY } from '../utils/api'
import { getReportEditionInfo, reportSlotStamp } from '../utils/reportEdition'
import { DEFAULT_WEEKDAY_FROM, DEFAULT_WEEKDAY_TO, WEEKDAY_OPTIONS, weekdaysOf } from '../utils/weekdays'
import { MAX_INTENT_LENGTH, normalizeIntent, topicIntentHint, topicOverview } from '../utils/topicOverview'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoChannels, demoSubscription, demoTodayStatus } from '../demo/fixtures'
import { earliestOnTimeLabel, type TodayProgress, type TopicProgressItem } from '../utils/pushDisplay'
import './Subscription.css'

interface PublicReportPreview {
  id: number
  edition: string
  title: string
  summary: string
  createdAt: string
  displayTime?: string
  reportDate?: string
}

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
  weekdayFrom: number
  weekdayTo: number
  channelIds: number[]
  intent: string
}

interface SubscriptionData {
  enabled: boolean
  topicSchedules: { items: TopicScheduleItem[] }
}

const AI_TECH_DIGEST = 'AI科技'
const ETF_DIGEST = '纳指标普沪深300ETF'
const COMMON_PRESETS = [
  'AI大模型', 'Web开发', '移动端', '云原生', '数据库',
  '安全', 'DevOps', '数据分析', '机器学习', '区块链',
]
const DIGEST_TOPICS = [AI_TECH_DIGEST, ETF_DIGEST]
const FIELD_OPTIONS = [...DIGEST_TOPICS, ...COMMON_PRESETS]
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
const isAiTechDigest = (topic: string) => {
  const key = interestKey(topic).replace(/\s+/g, '')
  return key === interestKey(AI_TECH_DIGEST) || key === '科技'
}
const isEtfDigest = (topic: string) => {
  const key = interestKey(topic).replace(/\s+/g, '')
  return key === interestKey(ETF_DIGEST) || key === 'etf' || key === '市场观察'
}
const digestBadge = (topic: string) => {
  if (isAiTechDigest(topic)) return '早晚报原文'
  if (isEtfDigest(topic)) return 'ETF原文'
  return ''
}
const isPreset = (topic: string) => FIELD_OPTIONS.some(option => interestKey(option) === interestKey(topic))
const topicKind = (topic: string) => {
  if (isAiTechDigest(topic)) return 'digest-ai'
  if (isEtfDigest(topic)) return 'digest-etf'
  if (!isPreset(topic)) return 'custom'
  return 'preset'
}
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
      ...weekdaysOf(row, 1, 7),
      channelIds: (Array.isArray(row?.channelIds) ? row.channelIds : [])
        .filter((id: unknown): id is number => Number.isInteger(id) && Number(id) > 0),
      intent: normalizeIntent(row?.intent),
    })
  })
  return items
}

const normalizeSubscription = (source: any): SubscriptionData => {
  const items = flattenItems(source)
  return {
    enabled: items.some(item => item.enabled),
    topicSchedules: { items },
  }
}

const isUnauthorized = (error?: any, body?: any) =>
  error?.response?.status === 401 || body?.code === 401 || error?.response?.data?.code === 401

const apiMessage = (error?: any, body?: any, fallback = '请求失败') => {
  const detail = body?.message || error?.response?.data?.message || error?.message || ''
  return detail ? `${fallback}：${detail}` : fallback
}

const PUBLIC_PREVIEWS: Array<{ edition: 'morning' | 'evening' | 'market_watch_evening'; hint: string }> = [
  { edition: 'morning', hint: '勾选「AI科技」后按早报原文生成' },
  { edition: 'evening', hint: '勾选「AI科技」后按晚报原文生成' },
  { edition: 'market_watch_evening', hint: '勾选 ETF 主题后按原文生成，仅管理员和 Demo 可见' },
]

async function loadPublicPreview(edition: string): Promise<PublicReportPreview | null> {
  try {
    const latest = await api.get('/reports/latest', { params: { edition } })
    if (latest.data?.code === 200 && latest.data.data) return latest.data.data
    const page = await api.get('/reports', { params: { edition, page: 1, size: 1 } })
    return page.data?.data?.records?.[0] || null
  } catch {
    return null
  }
}

export default function Subscription() {
  const { user, authReady, logout } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const isAdmin = user?.role === 'ADMIN'
  const [data, setData] = useState<SubscriptionData>(normalizeSubscription({}))
  const [channels, setChannels] = useState<Channel[]>([])
  const [publicReports, setPublicReports] = useState<Partial<Record<string, PublicReportPreview | null>>>({})
  const [customInput, setCustomInput] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState<'ok' | 'error'>('ok')
  const [todayStatus, setTodayStatus] = useState<TodayProgress>({ items: [] })
  const [moreTopicsOpen, setMoreTopicsOpen] = useState(false)
  const [allSchedulesOpen, setAllSchedulesOpen] = useState(false)
  const [featuredTopic, setFeaturedTopic] = useState('')

  const showMessage = (text: string, type: 'ok' | 'error' = 'ok') => {
    setMessageType(type)
    setMessage(text)
  }

  useEffect(() => {
    if (!authReady) return
    if (isDemo) {
      setData(normalizeSubscription(demoSubscription))
      setChannels(demoChannels)
      setTodayStatus(demoTodayStatus)
      setLoading(false)
      return
    }
    setLoading(true)
    setMessage('')
    const load = async () => {
      try {
        const subscriptionRes = await api.get('/subscription')
        if (isUnauthorized(undefined, subscriptionRes.data)) {
          showMessage('登录已过期，请退出后重新登录再保存', 'error')
          return
        }
        if (subscriptionRes.data?.code !== 200) {
          showMessage(apiMessage(undefined, subscriptionRes.data, '订阅配置加载失败'), 'error')
          return
        }
        setData(normalizeSubscription(subscriptionRes.data.data))
      } catch (err: any) {
        if (isUnauthorized(err)) {
          showMessage('登录已过期，请退出后重新登录再保存', 'error')
          return
        }
        showMessage(apiMessage(err, undefined, '订阅配置加载失败'), 'error')
        return
      }
      try {
        const channelRes = await api.get('/channels')
        if (channelRes.data?.code === 200) {
          setChannels(channelRes.data.data || [])
        } else if (!isUnauthorized(undefined, channelRes.data) && channelRes.data?.message) {
          showMessage(apiMessage(undefined, channelRes.data, '通讯录加载失败'), 'error')
        }
      } catch (err: any) {
        if (isUnauthorized(err)) {
          showMessage('登录已过期，请退出后重新登录再保存', 'error')
          return
        }
        showMessage(apiMessage(err, undefined, '通讯录加载失败'), 'error')
      }
      try {
        const statusRes = await api.get('/subscription/today-status')
        if (statusRes.data?.code === 200) {
          setTodayStatus(statusRes.data.data || { items: [] })
        }
      } catch {
        setTodayStatus({ items: [] })
      }
      if (user?.role === 'ADMIN') {
        const [morning, evening, market] = await Promise.all([
          loadPublicPreview('morning'),
          loadPublicPreview('evening'),
          loadPublicPreview('market_watch_evening'),
        ])
        setPublicReports({ morning, evening, market_watch_evening: market })
      }
    }
    load().finally(() => setLoading(false))
  }, [isDemo, authReady, user?.role])

  const items = data.topicSchedules.items
  const topics = useMemo(() => {
    const names = [...FIELD_OPTIONS]
    items.forEach(item => {
      if (!names.some(name => interestKey(name) === interestKey(item.topic))) names.push(item.topic)
    })
    return names
  }, [items])
  const subscribedTopics = useMemo(() => {
    const unique = new Map<string, string>()
    items.forEach(item => unique.set(interestKey(item.topic), item.topic))
    return Array.from(unique.values())
  }, [items])
  const enabledTopics = useMemo(
    () => subscribedTopics.filter(topic => items.some(item => interestKey(item.topic) === interestKey(topic) && item.enabled)),
    [items, subscribedTopics]
  )
  const extraTopics = useMemo(() => {
    const names = [...DIGEST_TOPICS]
    items.forEach(item => {
      if (COMMON_PRESETS.some(name => interestKey(name) === interestKey(item.topic))) return
      if (!names.some(name => interestKey(name) === interestKey(item.topic))) names.push(item.topic)
    })
    return names
  }, [items])
  const enabledSlots = items.filter(item => item.enabled)

  useEffect(() => {
    if (featuredTopic && subscribedTopics.some(topic => interestKey(topic) === interestKey(featuredTopic))) return
    setFeaturedTopic(subscribedTopics[0] || '')
  }, [subscribedTopics, featuredTopic])

  useEffect(() => {
    if (!moreTopicsOpen && !allSchedulesOpen) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMoreTopicsOpen(false)
        setAllSchedulesOpen(false)
      }
    }
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = previous
      window.removeEventListener('keydown', onKey)
    }
  }, [moreTopicsOpen, allSchedulesOpen])
  const channelsByType = useMemo(() => {
    const grouped: Partial<Record<ChannelType, Channel[]>> = {}
    channels.forEach(channel => {
      grouped[channel.channelType] = [...(grouped[channel.channelType] || []), channel]
    })
    return grouped
  }, [channels])

  const allToggleRef = useRef<HTMLInputElement>(null)
  const topicItems = (topic: string) => items.filter(item => interestKey(item.topic) === interestKey(topic))
  const topicSubscribed = (topic: string) => topicItems(topic).length > 0
  const topicEnabled = (topic: string) => topicItems(topic).some(item => item.enabled)
  const extraSubscribedCount = extraTopics.filter(topic => topicSubscribed(topic)).length
  const slotProgress = (topic: string, time: string): TopicProgressItem | undefined =>
    todayStatus.items.find(item => item.topic === topic && item.time === time)
  const canonicalTopic = (topic: string) => (
    isAiTechDigest(topic) ? AI_TECH_DIGEST : isEtfDigest(topic) ? ETF_DIGEST : topic
  )
  const sameTopic = (left: string, right: string) => (
    interestKey(left) === interestKey(right)
    || (isAiTechDigest(left) && isAiTechDigest(right))
    || (isEtfDigest(left) && isEtfDigest(right))
  )

  useEffect(() => {
    if (!allToggleRef.current) return
    allToggleRef.current.indeterminate = subscribedTopics.length > 0
      && enabledTopics.length > 0
      && enabledTopics.length < subscribedTopics.length
  }, [enabledTopics.length, subscribedTopics.length])

  const updateItems = (next: TopicScheduleItem[]) => {
    if (isDemo) return
    setData(prev => ({
      ...prev,
      enabled: next.some(item => item.enabled),
      topicSchedules: { items: next },
    }))
  }

  const defaultChannelIdsFrom = (current: TopicScheduleItem[]) => {
    const used = current.flatMap(item => item.channelIds || [])
    if (used.length) return [...new Set(used)]
    const picked: number[] = []
    ;(Object.keys(CHANNEL_META) as ChannelType[]).forEach(type => {
      const enabled = (channelsByType[type] || []).filter(channel => channel.enabled)
      if (enabled[0]) picked.push(enabled[0].id)
    })
    return picked
  }
  const defaultChannelIds = () => defaultChannelIdsFrom(items)

  const setTopicsEnabled = (topics: string[], enabled: boolean) => {
    if (isDemo || topics.length === 0) return
    setData(prev => {
      const nextItems = prev.topicSchedules.items.map(item => (
        topics.some(topic => sameTopic(topic, item.topic)) ? { ...item, enabled } : item
      ))
      return {
        ...prev,
        enabled: nextItems.some(item => item.enabled),
        topicSchedules: { items: nextItems },
      }
    })
  }
  const toggleAllSubscriptions = (enabled: boolean) => {
    setTopicsEnabled(subscribedTopics, enabled)
  }
  const subscribeTopics = (topics: string[]) => {
    if (isDemo || topics.length === 0) return
    const last = canonicalTopic(topics[topics.length - 1])
    setFeaturedTopic(last)
    setData(prev => {
      let next = [...prev.topicSchedules.items]
      topics.forEach(topic => {
        const name = canonicalTopic(topic)
        if (next.some(item => sameTopic(name, item.topic))) {
          next = next.map(item => sameTopic(name, item.topic) ? { ...item, enabled: true } : item)
          return
        }
        const channelIds = defaultChannelIdsFrom(next)
        if (isAiTechDigest(name)) {
          next = [
            ...next,
            { topic: AI_TECH_DIGEST, enabled: true, time: '08:00', weekdayFrom: 1, weekdayTo: 7, channelIds, intent: '' },
            { topic: AI_TECH_DIGEST, enabled: true, time: '20:00', weekdayFrom: 1, weekdayTo: 7, channelIds, intent: '' },
          ]
          return
        }
        if (isEtfDigest(name)) {
          next = [...next, { topic: ETF_DIGEST, enabled: true, time: '18:00', weekdayFrom: 1, weekdayTo: 5, channelIds, intent: '' }]
          return
        }
        next = [...next, { topic: name, enabled: true, time: DEFAULT_TIME, weekdayFrom: DEFAULT_WEEKDAY_FROM, weekdayTo: DEFAULT_WEEKDAY_TO, channelIds, intent: '' }]
      })
      return {
        ...prev,
        enabled: next.some(item => item.enabled),
        topicSchedules: { items: next },
      }
    })
  }
  const unsubscribeTopics = (topics: string[]) => {
    if (isDemo || topics.length === 0) return
    setData(prev => {
      const next = prev.topicSchedules.items.filter(item => !topics.some(topic => sameTopic(topic, item.topic)))
      return {
        ...prev,
        enabled: next.some(item => item.enabled),
        topicSchedules: { items: next },
      }
    })
  }
  const toggleTopic = (topic: string, enabled: boolean) => {
    if (enabled) subscribeTopics([topic])
    else unsubscribeTopics([topic])
  }

  const updateSlot = (index: number, patch: Partial<TopicScheduleItem>) => {
    updateItems(items.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item))
  }

  const updateTopicIntent = (topic: string, intent: string) => {
    const next = Array.from(intent).slice(0, MAX_INTENT_LENGTH).join('')
    updateItems(items.map(item => interestKey(item.topic) === interestKey(topic) ? { ...item, intent: next } : item))
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
    updateItems([...items, {
      topic,
      enabled: topicEnabled(topic),
      time: WINDOW_DEFAULTS[nextWindow],
      weekdayFrom: DEFAULT_WEEKDAY_FROM,
      weekdayTo: DEFAULT_WEEKDAY_TO,
      channelIds: defaultChannelIds(),
      intent: topicItems(topic)[0]?.intent || '',
    }])
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
    if (!exists && subscribedTopics.length >= MAX_INTERESTS) {
      return showMessage(`兴趣总数不能超过 ${MAX_INTERESTS} 个`, 'error')
    }
    if (!exists && new Set([...topics, topic].map(interestKey)).size > MAX_INTERESTS) {
      return showMessage(`兴趣总数不能超过 ${MAX_INTERESTS} 个`, 'error')
    }
    subscribeTopics([topic])
    setCustomInput('')
    setMessage('')
    setMoreTopicsOpen(true)
    setFeaturedTopic(isAiTechDigest(topic) ? AI_TECH_DIGEST : isEtfDigest(topic) ? ETF_DIGEST : topic)
  }

  const removeCustomInterest = (topic: string) => {
    if (isDemo || isPreset(topic)) return
    updateItems(items.filter(item => interestKey(item.topic) !== interestKey(topic)))
  }

  const handleSave = async () => {
    if (isDemo) return
    if (!localStorage.getItem(TOKEN_KEY)) {
      showMessage('登录已过期，请退出后重新登录再保存', 'error')
      logout()
      return
    }
    setSaving(true)
    setMessage('')
    try {
      const payload = {
        enabled: items.some(item => item.enabled),
        preferenceFields: subscribedTopics,
        topicSchedules: { items },
      }
      const res = await api.put('/subscription', payload)
      if (res.data?.code === 200) {
        setData(normalizeSubscription(res.data.data || payload))
        showMessage('设置已保存。到点后按你选的时刻展示和推送')
      } else if (isUnauthorized(undefined, res.data)) {
        showMessage('登录已过期，请退出后重新登录再保存', 'error')
        logout()
      } else {
        showMessage(apiMessage(undefined, res.data, '保存失败'), 'error')
      }
    } catch (error: any) {
      if (isUnauthorized(error)) {
        showMessage('登录已过期，请退出后重新登录再保存', 'error')
        logout()
      } else {
        showMessage(apiMessage(error, undefined, '保存失败'), 'error')
      }
    } finally {
      setSaving(false)
    }
  }

  const renderTopicCard = (topic: string, showOverview = false) => (
    <div key={topic} className={`topic-schedule-row kind-${topicKind(topic)}${topicSubscribed(topic) ? ' active' : ''}${topicSubscribed(topic) && !topicEnabled(topic) ? ' paused' : ''}`}>
      <label className="topic-check">
        <input
          type="checkbox"
          checked={topicSubscribed(topic)}
          disabled={isDemo}
          onChange={event => toggleTopic(topic, event.target.checked)}
        />
        <span>{topic}</span>
        {digestBadge(topic) && <small>{digestBadge(topic)}</small>}
        {!isPreset(topic) && <small>自定义</small>}
      </label>
      {showOverview && topicSubscribed(topic) && <p className="topic-overview">{topicOverview(topic)}</p>}
      {!isPreset(topic) && (
        <button type="button" className="remove-interest" disabled={isDemo} onClick={() => removeCustomInterest(topic)}>删除</button>
      )}
    </div>
  )

  const renderScheduleTopic = (topic: string) => {
    const slots = items
      .map((item, index) => ({ item, index }))
      .filter(({ item }) => interestKey(item.topic) === interestKey(topic))
    if (slots.length === 0) return null
    const active = topicEnabled(topic)
    return (
      <div key={topic} className={`schedule-topic kind-${topicKind(topic)}${active ? '' : ' paused'}`}>
        <div className="schedule-topic-head">
          <div className="schedule-topic-title">
            <strong>{topic}</strong>
            {digestBadge(topic) && <small>{digestBadge(topic)}</small>}
            {!isPreset(topic) && <small>自定义</small>}
            <label className="topic-switch" title={active ? '关闭后这个主题不再生成和推送，时刻和渠道会保留' : '开启后按下面的时刻生成并推送'}>
              <input type="checkbox" checked={active} disabled={isDemo} onChange={event => setTopicsEnabled([topic], event.target.checked)} />
              <span className="slider compact"></span>
              <em>{active ? '推送中' : '已暂停'}</em>
            </label>
          </div>
          <p className="topic-overview">{topicOverview(topic)}</p>
        </div>
        {slots.map(({ item, index }, slotIndex) => (
          <div key={`${topic}-${index}`} className="schedule-row">
            <div className="schedule-name">
              <div className="weekday-range">
                <select
                  value={item.weekdayFrom}
                  disabled={isDemo}
                  aria-label={`${topic}起始星期`}
                  onChange={event => updateSlot(index, { weekdayFrom: Number(event.target.value) })}
                >
                  {WEEKDAY_OPTIONS.map(option => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <span>至</span>
                <select
                  value={item.weekdayTo}
                  disabled={isDemo}
                  aria-label={`${topic}结束星期`}
                  onChange={event => updateSlot(index, { weekdayTo: Number(event.target.value) })}
                >
                  {WEEKDAY_OPTIONS.map(option => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </div>
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
            {slotProgress(topic, item.time) && (
              <span className={`schedule-badge status-${slotProgress(topic, item.time)?.status || ''}`} title={slotProgress(topic, item.time)?.message}>
                {slotProgress(topic, item.time)?.label}
              </span>
            )}
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
        <div className="topic-intent-card">
          <label className="topic-intent">
            <span>我想看 · {topic}</span>
            <textarea
              value={slots[0]?.item.intent || ''}
              maxLength={MAX_INTENT_LENGTH}
              disabled={isDemo}
              placeholder={topicIntentHint(topic)}
              onChange={event => updateTopicIntent(topic, event.target.value)}
            />
            <small>{Array.from(slots[0]?.item.intent || '').length}/{MAX_INTENT_LENGTH} · 优先按这句话找；没有这个角度就写主题相近内容</small>
          </label>
        </div>
      </div>
    )
  }

  if (!authReady || loading) return <div className="loading">加载中...</div>

  return (
    <ConfigProvider locale={zhCN} theme={subscriptionTheme}>
    <div className="subscription-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>订阅管理</h2>
        <p className="page-desc">{isAdmin ? '科技日报和 ETF 也请在下面勾选、设时刻并绑渠道。每个主题可写「我想看」，优先按这个角度检索；当天没有这个角度时，会用主题相近内容写一版，不会编造。不填就按系统默认生成。' : '勾选兴趣，再选星期、时刻，并可写「我想看」。想法优先，找不到就写主题相近内容；同一主题在同一 6 小时时段只生成一次。'}</p>
      </div>

      {isAdmin && (
        <section className="subscription-pane">
          <div className="section-title-row">
            <div>
              <p className="subscription-pane-kicker">公共内容</p>
              <h3>全站日报</h3>
              <p className="section-sub">管理员和 Demo 仍可在这里预览最近一期。要继续生成和推送，请在下面勾选「AI科技」和 ETF，并绑渠道。</p>
            </div>
            <Link to="/reports" className="ghost-link">历史日报 →</Link>
          </div>
          <div className="public-preview-list">
            {PUBLIC_PREVIEWS.map(item => {
              const report = publicReports[item.edition]
              const info = getReportEditionInfo(item.edition)
              return (
                <div key={item.edition} className="public-preview-card">
                  <div className="public-preview-meta">
                    <span>{info.icon} {info.shortLabel}</span>
                    <small>{item.hint}</small>
                  </div>
                  {report ? (
                    <>
                      <strong>{report.title}</strong>
                      <p>{report.summary}</p>
                      <div className="public-preview-footer">
                        <span>{reportSlotStamp(report)}</span>
                        <Link to={`/report/${report.id}`}>查看 →</Link>
                      </div>
                    </>
                  ) : (
                    <p className="public-preview-empty">暂无最近一期，生成后会显示在这里</p>
                  )}
                </div>
              )
            })}
          </div>
        </section>
      )}

      <section className={isAdmin ? 'subscription-pane personal' : undefined}>
      {isAdmin && todayStatus.poller && !todayStatus.poller.healthy && (
        <div className="alert-line danger">订阅生成器超过 20 分钟没有心跳，个人简报可能停了。最近一次：{todayStatus.poller.lastSeen || '从未上报'}</div>
      )}
      {isAdmin && (
        <div className="section-title-row pane-intro">
          <div>
            <p className="subscription-pane-kicker">个人内容</p>
            <h3>我的个人订阅</h3>
            <p className="section-sub">勾选主题、设时刻并绑渠道。科技日报和 ETF 走原来的全文，其他兴趣走短段落。</p>
          </div>
        </div>
      )}

      <div className="subscription-summary">
        <div><span className="summary-label">已订阅兴趣</span><strong>{subscribedTopics.length}</strong></div>
        <div><span className="summary-label">推送时刻</span><strong>{new Set(enabledSlots.map(item => item.time)).size}</strong></div>
        <div><span className="summary-label">渠道绑定</span><strong>{enabledSlots.filter(item => item.channelIds.length > 0).length}</strong></div>
      </div>

      <div className="section">
        <div className="section-title-row">
          <div>
            <h3>兴趣主题</h3>
            <p className="section-sub">这里只放 10 个常用主题。科技日报、ETF 和自定义兴趣在「其他订阅」里勾选。</p>
          </div>
          <span className="section-count">已选 {subscribedTopics.length} 个</span>
        </div>
        <div className="topic-actions">
          <button type="button" disabled={isDemo} onClick={() => subscribeTopics(COMMON_PRESETS)}>全选常用</button>
          <button type="button" disabled={isDemo} onClick={() => unsubscribeTopics(COMMON_PRESETS)}>清空勾选</button>
        </div>
        <div className="topic-schedule-list compact">
          {COMMON_PRESETS.map(topic => renderTopicCard(topic))}
        </div>
        <button type="button" className="more-panel-btn" onClick={() => setMoreTopicsOpen(true)}>
          <span>其他订阅 · 公共日报与自定义</span>
          <strong>{extraSubscribedCount > 0 ? `已选 ${extraSubscribedCount}` : '去勾选并设置'}</strong>
        </button>
      </div>

      <div className="section">
        <div className="section-title-row">
          <div>
            <h3>订阅开关</h3>
            <p className="section-sub">一键开或关全部已勾选主题；每个主题也可以单独暂停，时刻和渠道会保留。</p>
          </div>
          <span className="section-count">推送中 {enabledTopics.length}/{subscribedTopics.length}</span>
        </div>
        <label className={`toggle all-subscriptions${subscribedTopics.length === 0 ? ' disabled' : ''}`}>
          <input
            ref={allToggleRef}
            type="checkbox"
            checked={subscribedTopics.length > 0 && enabledTopics.length === subscribedTopics.length}
            disabled={isDemo || subscribedTopics.length === 0}
            onChange={event => toggleAllSubscriptions(event.target.checked)}
          />
          <span className="slider"></span>
          <span className="toggle-label">
            {subscribedTopics.length === 0
              ? '还没有已勾选的订阅'
              : enabledTopics.length === subscribedTopics.length
                ? `已开启全部 ${subscribedTopics.length} 个订阅`
                : enabledTopics.length === 0
                  ? '已关闭全部订阅'
                  : `一键开启全部（当前 ${enabledTopics.length}/${subscribedTopics.length} 个在推送）`}
          </span>
        </label>
        {subscribedTopics.length > 0 && (
          <div className="topic-switch-list">
            {subscribedTopics.map(topic => (
              <label key={topic} className={`topic-switch-row kind-${topicKind(topic)}${topicEnabled(topic) ? '' : ' paused'}`}>
                <span>
                  <strong>{topic}</strong>
                  {digestBadge(topic) && <small>{digestBadge(topic)}</small>}
                  {!isPreset(topic) && <small>自定义</small>}
                </span>
                <em>
                  <input type="checkbox" checked={topicEnabled(topic)} disabled={isDemo} onChange={event => setTopicsEnabled([topic], event.target.checked)} />
                  <span className="slider compact"></span>
                  {topicEnabled(topic) ? '推送中' : '已暂停'}
                </em>
              </label>
            ))}
          </div>
        )}
      </div>

      <div className="section schedule-section">
        <div className="section-title-row">
          <div>
            <h3>推送时间</h3>
            <p className="section-sub">
              默认只展开一个已勾选主题。准点请选 {todayStatus.earliestOnTime || earliestOnTimeLabel(dayjs.tz(), todayStatus.onTimeLeadMinutes || 5)} 及以后；更近的时刻也会生成，可能晚 1–2 分钟补推。
            </p>
          </div>
          <span className="section-count">{enabledSlots.length} 个时刻</span>
        </div>
        {subscribedTopics.length === 0 ? (
          <div className="schedule-empty">先在上面勾选兴趣，再设置星期和时刻</div>
        ) : (
          <div className="schedule-list">
            {renderScheduleTopic(
              featuredTopic && subscribedTopics.some(topic => interestKey(topic) === interestKey(featuredTopic))
                ? featuredTopic
                : subscribedTopics[0]
            )}
            {subscribedTopics.length > 1 && (
              <button type="button" className="more-panel-btn" onClick={() => setAllSchedulesOpen(true)}>
                <span>查看全部已订阅</span>
                <strong>还有 {subscribedTopics.length - 1} 个主题可设时刻和渠道</strong>
              </button>
            )}
          </div>
        )}
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

      <p className="interest-help">科技日报和 ETF 在「其他订阅」里勾选。推送渠道里有已启用账号就会投递；主题上再选一次只是指定用哪个。</p>
      <div className="subscription-save-bar">
        <button className="save-btn" onClick={handleSave} disabled={isDemo || saving}>{saving ? '保存中...' : '保存设置'}</button>
        {message && <div className={`message ${messageType}`}>{message}</div>}
      </div>
      </section>

      {moreTopicsOpen && (
        <div className="subscription-drawer" onClick={() => setMoreTopicsOpen(false)}>
          <div className="subscription-drawer-panel" onClick={event => event.stopPropagation()} role="dialog" aria-label="其他订阅">
            <div className="subscription-drawer-head">
              <div>
                <h3>其他订阅</h3>
                <p>勾选科技日报、ETF 和自定义兴趣，并可直接设时刻、绑渠道。</p>
              </div>
              <button type="button" className="ghost-link" onClick={() => setMoreTopicsOpen(false)}>关闭</button>
            </div>
            <div className="topic-schedule-list">
              {extraTopics.map(topic => renderTopicCard(topic, true))}
            </div>
            <div className="custom-interest-row">
              <input
                type="text"
                value={customInput}
                maxLength={MAX_INTEREST_LENGTH * 2}
                disabled={isDemo}
                placeholder="添加自定义兴趣，如：黄仁勋"
                onChange={event => setCustomInput(event.target.value)}
                onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); addCustomInterest() } }}
              />
              <button type="button" disabled={isDemo} onClick={addCustomInterest}>添加兴趣</button>
            </div>
            <div className="schedule-list drawer-schedules">
              {extraTopics.filter(topic => topicItems(topic).length > 0).map(topic => renderScheduleTopic(topic))}
            </div>
          </div>
        </div>
      )}

      {allSchedulesOpen && (
        <div className="subscription-drawer" onClick={() => setAllSchedulesOpen(false)}>
          <div className="subscription-drawer-panel" onClick={event => event.stopPropagation()} role="dialog" aria-label="全部已订阅">
            <div className="subscription-drawer-head">
              <div>
                <h3>全部已订阅</h3>
                <p>给每个主题设星期、时刻，并绑定邮箱 / 企业微信 / 钉钉 / 飞书。</p>
              </div>
              <button type="button" className="ghost-link" onClick={() => setAllSchedulesOpen(false)}>关闭</button>
            </div>
            <div className="schedule-list drawer-schedules">
              {subscribedTopics.map(topic => renderScheduleTopic(topic))}
            </div>
          </div>
        </div>
      )}
    </div>
    </ConfigProvider>
  )
}
