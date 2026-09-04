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

export type PushKind = 'test' | 'scheduled' | 'ops' | 'unknown'

export interface PushKindInfo {
  kind: PushKind
  label: string
  slot: string
}

export function dispatchKeyOf(log: { dispatchKey?: string | null; dispatch_key?: string | null } | null | undefined) {
  return log?.dispatchKey || log?.dispatch_key || ''
}

export function pushKindFromDispatchKey(dispatchKey?: string | null): PushKindInfo {
  if (!dispatchKey) {
    return { kind: 'unknown', label: '来源未知', slot: '' }
  }
  if (dispatchKey.startsWith('test:')) {
    return { kind: 'test', label: '测试推送', slot: '' }
  }
  if (dispatchKey.startsWith('ops:')) {
    return { kind: 'ops', label: '脚本直推', slot: '' }
  }
  const scheduledSlot = dispatchKey.match(/^scheduled:\d{4}-\d{2}-\d{2}:(\d{2}:\d{2})(?::|$)/)
  if (scheduledSlot) {
    return { kind: 'scheduled', label: '订阅投递', slot: scheduledSlot[1] }
  }
  if (dispatchKey.startsWith('scheduled:')) {
    return { kind: 'scheduled', label: '订阅投递', slot: '' }
  }
  return { kind: 'unknown', label: '来源未知', slot: '' }
}

export function slotFromDispatchKey(dispatchKey?: string | null) {
  const info = pushKindFromDispatchKey(dispatchKey)
  if (info.kind === 'test' || info.kind === 'ops') return info.label
  return info.slot
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

const SETTLED_TODAY_STATUS = new Set<TopicProgressStatus>(['pushed', 'delivered'])

/** 还有生成、补推或生成器异常时，订阅页/首页应继续拉今日进度。 */
export function todayStatusNeedsLiveRefresh(progress: TodayProgress) {
  if (progress.poller && progress.poller.healthy === false) return true
  return progress.items.some(item => !SETTLED_TODAY_STATUS.has(item.status))
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
