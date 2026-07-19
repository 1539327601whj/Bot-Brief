export function getReportEditionInfo(edition: string) {
  if (edition === 'morning') {
    return { icon: '🌅', label: 'AI 早间简报', shortLabel: 'AI 早报', className: 'tag tag-morning', version: 'AI 简报', expectedLabel: '08:00' }
  }
  if (edition === 'evening') {
    return { icon: '🌙', label: 'AI 晚间简报', shortLabel: 'AI 晚报', className: 'tag tag-evening', version: 'AI 简报', expectedLabel: '20:00' }
  }
  if (edition === 'market_watch_morning') {
    return { icon: '📈', label: '市场观察早间版', shortLabel: '早间观察', className: 'tag tag-etf', version: '市场观察', expectedLabel: '10:00' }
  }
  if (edition === 'market_watch_evening') {
    return { icon: '📊', label: '市场观察晚间版', shortLabel: '晚间观察', className: 'tag tag-etf-evening', version: '市场观察', expectedLabel: '17:00' }
  }
  if (edition === 'etf_morning') {
    return { icon: '📈', label: 'ETF/A股早间观察', shortLabel: 'ETF 早报', className: 'tag tag-etf', version: 'ETF/A股观察', expectedLabel: '10:00' }
  }
  if (edition === 'etf_evening') {
    return { icon: '📊', label: 'ETF/A股晚间观察', shortLabel: 'ETF 晚报', className: 'tag tag-etf-evening', version: 'ETF/A股观察', expectedLabel: '17:00' }
  }
  return { icon: '📄', label: '其他简报', shortLabel: '其他', className: 'tag tag-other', version: '其他', expectedLabel: '--:--' }
}
