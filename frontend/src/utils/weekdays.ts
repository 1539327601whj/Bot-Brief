export const WEEKDAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
] as const

export const DEFAULT_WEEKDAY_FROM = 1
export const DEFAULT_WEEKDAY_TO = 5

export function normalizeWeekday(value: unknown, fallback: number) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 7 ? parsed : fallback
}

export function weekdaysOf(row: { weekdayFrom?: unknown; weekdayTo?: unknown } | null | undefined, fallbackFrom = 1, fallbackTo = 7) {
  const hasFrom = Number.isInteger(row?.weekdayFrom)
  const hasTo = Number.isInteger(row?.weekdayTo)
  if (!hasFrom && !hasTo) {
    return { weekdayFrom: fallbackFrom, weekdayTo: fallbackTo }
  }
  return {
    weekdayFrom: normalizeWeekday(row?.weekdayFrom, fallbackFrom),
    weekdayTo: normalizeWeekday(row?.weekdayTo, fallbackTo),
  }
}

export function weekdayRangeLabel(from: number, to: number) {
  if (from === 1 && to === 7) return '每天'
  if (from === 1 && to === 5) return '周一至周五'
  if (from === to) return `仅${WEEKDAY_OPTIONS[from - 1].label}`
  return `${WEEKDAY_OPTIONS[from - 1].label}至${WEEKDAY_OPTIONS[to - 1].label}`
}

export function coversWeekday(from: number, to: number, isoDay: number) {
  if (from <= to) return isoDay >= from && isoDay <= to
  return isoDay >= from || isoDay <= to
}
