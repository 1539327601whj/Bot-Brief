import dayjs from './dayjs'

const PUBLIC_SLOT: Record<string, string> = {
  morning: '08:00',
  evening: '20:00',
  market_watch_evening: '18:00',
  market_watch_morning: '08:00',
  etf_evening: '18:00',
  etf_morning: '08:00',
}

export function reportDisplayClock(
  report?: { displayTime?: string; createdAt?: string; edition?: string },
  edition = report?.edition,
) {
  if (report?.displayTime && report.displayTime.length >= 5) {
    return report.displayTime.slice(0, 5)
  }
  if (edition && PUBLIC_SLOT[edition]) return PUBLIC_SLOT[edition]
  return report?.createdAt ? dayjs(report.createdAt).tz('Asia/Shanghai').format('HH:mm') : ''
}

export function reportSlotStamp(report?: {
  createdAt?: string
  displayTime?: string
  reportDate?: string
  edition?: string
}, format = 'MM-DD HH:mm') {
  if (!report) return ''
  const date = report.reportDate
    || (report.createdAt ? dayjs(report.createdAt).tz('Asia/Shanghai').format('YYYY-MM-DD') : '')
  const time = reportDisplayClock(report)
  if (!date) return time
  return dayjs.tz(`${date} ${time || '00:00'}`, 'Asia/Shanghai').format(format)
}

export function reportIsOnDate(report?: { createdAt?: string; reportDate?: string } | null, day = dayjs()) {
  if (!report) return false
  const date = report.reportDate || report.createdAt
  if (!date) return false
  return dayjs(date).format('YYYY-MM-DD') === day.format('YYYY-MM-DD')
}

export function personalEditionName(reportTitle?: string, topics?: string[]) {
  const fromTitle = (reportTitle || '')
    .replace(/^【\d{2}:\d{2}】\s*/, '')
    .replace(/\s+\d{4}-\d{2}-\d{2}\s*$/, '')
    .replace(/^我的简报\s*/, '')
    .trim()
  if (fromTitle) return fromTitle
  const names = [...new Set((topics || []).map(topic => topic.trim()).filter(Boolean))]
  if (names.length === 1) return `${names[0]}日报`
  if (names.length === 2) return `${names[0]}与${names[1]}`
  if (names.length > 2) return `${names[0]}等`
  return '订阅日报'
}

export function getReportEditionInfo(edition: string, displayTime?: string, reportTitle?: string, topics?: string[]) {
  const time = displayTime ? displayTime.slice(0, 5) : ''
  if (edition === 'personal') {
    const name = personalEditionName(reportTitle, topics)
    return {
      icon: '✨',
      label: time ? `${name} ${time}` : name,
      shortLabel: name,
      className: 'tag tag-morning',
      version: '订阅日报',
      expectedLabel: time || '--:--',
    }
  }
  if (edition === 'morning') {
    return { icon: '🌅', label: 'AI 早间简报', shortLabel: 'AI 早报', className: 'tag tag-morning', version: 'AI 简报', expectedLabel: '08:00' }
  }
  if (edition === 'evening') {
    return { icon: '🌙', label: 'AI 晚间简报', shortLabel: 'AI 晚报', className: 'tag tag-evening', version: 'AI 简报', expectedLabel: '20:00' }
  }
  if (edition === 'market_watch_morning') {
    return { icon: '📈', label: '历史市场观察早间版', shortLabel: '历史早报', className: 'tag tag-etf', version: '市场观察', expectedLabel: '--:--' }
  }
  if (edition === 'market_watch_evening') {
    return { icon: '📊', label: 'ETF 行情日报', shortLabel: 'ETF 日报', className: 'tag tag-etf-evening', version: 'ETF/A股观察', expectedLabel: '18:00' }
  }
  if (edition === 'etf_morning') {
    return { icon: '📈', label: '历史 ETF/A股早间观察', shortLabel: '历史早报', className: 'tag tag-etf', version: 'ETF/A股观察', expectedLabel: '--:--' }
  }
  if (edition === 'etf_evening') {
    return { icon: '📊', label: 'ETF 行情日报', shortLabel: 'ETF 日报', className: 'tag tag-etf-evening', version: 'ETF/A股观察', expectedLabel: '18:00' }
  }
  return { icon: '📄', label: '其他简报', shortLabel: '其他', className: 'tag tag-other', version: '其他', expectedLabel: '--:--' }
}
