import dayjs from './dayjs'

export function reportSlotStamp(report?: {
  createdAt?: string
  displayTime?: string
  reportDate?: string
}, format = 'MM-DD HH:mm') {
  if (!report) return ''
  const date = report.reportDate || (report.createdAt ? dayjs(report.createdAt).format('YYYY-MM-DD') : '')
  const time = report.displayTime
    ? report.displayTime.slice(0, 5)
    : (report.createdAt ? dayjs(report.createdAt).format('HH:mm') : '')
  if (!date) return report.createdAt ? dayjs(report.createdAt).format(format) : ''
  return dayjs(`${date} ${time || '00:00'}`).format(format)
}

export function reportIsOnDate(report?: { createdAt?: string; reportDate?: string } | null, day = dayjs()) {
  if (!report) return false
  const date = report.reportDate || report.createdAt
  if (!date) return false
  return dayjs(date).format('YYYY-MM-DD') === day.format('YYYY-MM-DD')
}

export function getReportEditionInfo(edition: string, displayTime?: string) {
  const time = displayTime ? displayTime.slice(0, 5) : ''
  if (edition === 'personal') {
    return {
      icon: '✨',
      label: time ? `我的简报 ${time}` : '我的简报',
      shortLabel: time || '简报',
      className: 'tag tag-morning',
      version: '我的简报',
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
