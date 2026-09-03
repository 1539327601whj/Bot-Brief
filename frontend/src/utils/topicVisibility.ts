export const AI_TECH_DIGEST = 'AI科技'
export const ETF_DIGEST = '纳指标普沪深300ETF'

export function interestKey(topic: string) {
  return topic.trim().toLocaleLowerCase()
}

export function isAiTechDigest(topic: string) {
  const key = interestKey(topic).replace(/\s+/g, '')
  return key === interestKey(AI_TECH_DIGEST) || key === '科技'
}

export function isEtfDigest(topic: string) {
  const key = interestKey(topic).replace(/\s+/g, '')
  return key === interestKey(ETF_DIGEST) || key === 'etf' || key === '市场观察'
}

export function isDigestTopic(topic: string) {
  return isAiTechDigest(topic) || isEtfDigest(topic)
}

export function defaultSiteVisible(topic: string) {
  return isDigestTopic(topic)
}

export function sameTopic(left: string, right: string) {
  return interestKey(left) === interestKey(right)
    || (isAiTechDigest(left) && isAiTechDigest(right))
    || (isEtfDigest(left) && isEtfDigest(right))
}

export function topicSiteVisible(
  topic: string,
  items?: { topic: string; siteVisible?: boolean }[] | null,
) {
  const rows = (items || []).filter(item => sameTopic(item.topic, topic))
  if (rows.length === 0) return defaultSiteVisible(topic)
  if (rows.some(item => item.siteVisible === false)) return false
  if (rows.some(item => item.siteVisible === true)) return true
  return defaultSiteVisible(topic)
}
