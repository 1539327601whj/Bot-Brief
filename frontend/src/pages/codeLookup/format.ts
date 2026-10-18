/** 页面上所有数字与状态文案的唯一出口。缺值一律返回 null / 原因，不返回 0。 */

export function num(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return Number(value).toFixed(digits)
}

export function signedPct(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  const n = Number(value)
  return `${n > 0 ? '+' : ''}${n.toFixed(digits)}%`
}

export function percent(value: number | null | undefined, digits = 0): string {
  if (value === null || value === undefined) return '—'
  return `${Number(value).toFixed(digits)}%`
}

/** 后端原始金额是「元」，页面统一显示「亿」。 */
export function yi(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${(Number(value) / 1e8).toFixed(2)} 亿`
}

export function dateOnly(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : '—'
}

export function dateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

/** 覆盖率/置信度：后端给 0–1 之间的小数。 */
export function ratioPct(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${(Number(value) * 100).toFixed(0)}%`
}

export type Tone = 'good' | 'ok' | 'warn' | 'bad' | 'muted'

export function scoreTone(score: number | null | undefined): Tone {
  if (score === null || score === undefined) return 'muted'
  if (score >= 65) return 'good'
  if (score >= 50) return 'ok'
  if (score >= 35) return 'warn'
  return 'bad'
}

export function verdictTone(labelKey: string | null | undefined): Tone {
  switch (labelKey) {
    case 'FOCUS': return 'good'
    case 'ACCUMULATE': return 'ok'
    case 'WATCH': return 'warn'
    case 'CAUTIOUS': return 'warn'
    case 'AVOID': return 'bad'
    default: return 'muted'
  }
}

export function confidenceText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未计算'
  if (value >= 0.8) return '高'
  if (value >= 0.6) return '中'
  return '低'
}

export const DOMAIN_STATUS: Record<string, string> = {
  OK: '正常',
  PARTIAL: '部分可用',
  EMPTY: '没有数据',
  FAILED: '取数失败',
  NOT_APPLICABLE: '不适用',
  SYNCING: '同步中',
}

export function domainStatusText(status: string | null | undefined): string {
  if (!status) return '未标注'
  return DOMAIN_STATUS[status] || status
}

export const DIMENSION_STATUS: Record<string, string> = {
  OK: '完整',
  PARTIAL: '部分指标缺失',
  'N/A': '不适用',
}

export function dimensionStatusText(status: string): string {
  return DIMENSION_STATUS[status] || status
}

export const BUY_ZONE_STATUS: Record<string, string> = {
  IN_ZONE: '当前价已在参考区间内',
  ABOVE_ZONE: '当前价高于参考区间上沿',
  BELOW_ZONE: '当前价低于参考区间下沿',
  WITHHELD: '本次不给参考区间',
  BROKEN: '已跌破参考止损',
}

export function buyZoneStatusText(status: string): string {
  return BUY_ZONE_STATUS[status] || status
}

export function eventCategoryLabel(category: string | null | undefined): string {
  switch (category) {
    case 'NOTICE': return '公告'
    case 'NEWS': return '新闻'
    case 'POLICY': return '政策'
    default: return category || '事件'
  }
}

export function polarityTone(polarity: string | null | undefined): Tone {
  switch ((polarity || '').toUpperCase()) {
    case 'POSITIVE': return 'good'
    case 'NEGATIVE': return 'bad'
    default: return 'muted'
  }
}

export function assetTypeLabel(assetType: string | null | undefined): string {
  switch (assetType) {
    case 'STOCK': return 'A 股个股'
    case 'ETF': return 'ETF'
    case 'INDEX': return '指数'
    default: return assetType || '标的不明'
  }
}

/** 服务端返回的 `status/note` 里偶尔会带原始英文枚举；状态文案只在页面这一层翻译。 */
export function sectionNote(note: string | null | undefined, status: string): string {
  if (note && note.trim()) return note
  if (status === 'EMPTY') return '本地库里没有这一段数据。'
  if (status === 'FAILED') return '这一段取数失败，原因未标注。'
  if (status === 'NOT_APPLICABLE') return '该标的类型不适用这一段。'
  if (status === 'PARTIAL') return '这一段只取到部分字段。'
  return ''
}
