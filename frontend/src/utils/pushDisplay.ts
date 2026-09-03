export type ChannelType = 'email' | 'wechat' | 'dingtalk' | 'feishu'

export const CHANNEL_LABEL: Record<string, string> = {
  email: '邮箱',
  wechat: '企业微信',
  dingtalk: '钉钉',
  feishu: '飞书',
}

export function channelLabel(type?: string) {
  if (!type) return '渠道'
  return CHANNEL_LABEL[type] || type
}

export function slotFromDispatchKey(dispatchKey?: string | null) {
  if (!dispatchKey) return ''
  const parts = dispatchKey.split(':')
  if (parts[0] === 'test') return '测试推送'
  if (parts[0] === 'ops') return '脚本直推'
  if (parts[0] !== 'scheduled' || parts.length < 4) return ''
  return /^\d{2}:\d{2}$/.test(parts[2]) ? parts[2] : ''
}

export type TopicProgressStatus =
  | 'upcoming'
  | 'preparing'
  | 'ready'
  | 'skipped'
  | 'failed'
  | 'delivered'
  | 'web_ready'
  | 'pushed'
  | 'push_failed'
  | 'push_partial'

export interface TopicProgressItem {
  topic: string
  time: string
  window?: string
  status: TopicProgressStatus
  label: string
  message: string
}

export interface TodayProgress {
  date?: string
  leadMinutes?: number
  onTimeLeadMinutes?: number
  earliestOnTime?: string
  poller?: { healthy: boolean; lastSeen?: string; detail?: string }
  items: TopicProgressItem[]
}

export function earliestOnTimeLabel(now: { startOf: (unit: 'minute') => { add: (value: number, unit: 'minute') => { format: (fmt: string) => string } } }, leadMinutes = 5) {
  const lead = Math.max(1, leadMinutes)
  return now.startOf('minute').add(lead, 'minute').format('HH:mm')
}

export function progressForSlot(items: TopicProgressItem[], time: string) {
  return items.filter(item => item.time === time)
}

export function slotEmptyHint(items: TopicProgressItem[], time: string, fallback: string) {
  const rows = progressForSlot(items, time)
  if (rows.length === 0) return fallback
  const failed = rows.find(item => item.status === 'failed')
  if (failed) return failed.message
  const skipped = rows.filter(item => item.status === 'skipped')
  if (skipped.length === rows.length) {
    return skipped.map(item => `「${item.topic}」${item.message}`).join('；')
  }
  const preparing = rows.find(item => item.status === 'preparing')
  if (preparing) return preparing.message
  const upcoming = rows.find(item => item.status === 'upcoming')
  if (upcoming) return upcoming.message
  const ready = rows.find(item => item.status === 'ready')
  if (ready) return ready.message
  return rows[0].message || fallback
}
