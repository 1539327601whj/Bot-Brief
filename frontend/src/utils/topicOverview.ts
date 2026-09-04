const AI_TECH = 'AI科技'
const ETF = '纳指标普沪深300ETF'

const PRESET_OVERVIEWS: Record<string, string> = {
  [AI_TECH]: '默认覆盖 AI、芯片、互联网、航天等科技要闻，生成全站科技早晚报原文。',
  [ETF]: '默认覆盖纳指、标普、沪深300 ETF 的行情与估值，生成市场观察原文。只能订傍晚，默认 18:00。',
  AI大模型: '默认覆盖大模型发布、评测、接口与开源动态。',
  Web开发: '默认覆盖前端、后端、浏览器与常见 Web 框架动态。',
  移动端: '默认覆盖 iOS、Android、跨端框架与应用发布。',
  云原生: '默认覆盖 Kubernetes、容器、Serverless 与云平台动态。',
  数据库: '默认覆盖关系库、缓存、向量库与查询引擎动态。',
  安全: '默认覆盖漏洞、攻击、隐私与供应链安全。',
  DevOps: '默认覆盖 CI/CD、部署、可观测性与工程效能。',
  数据分析: '默认覆盖数据工程、BI 与分析平台动态。',
  机器学习: '默认覆盖训练、推理、MLOps 与深度学习动态。',
  区块链: '默认覆盖公链、合约、Web3 与加密市场要闻。',
}

const PRESET_HINTS: Record<string, string> = {
  [AI_TECH]: '例如：只要芯片和航天，不要消费电子。不填则生成全站科技原文。',
  [ETF]: '例如：只要溢折价和资金流向。不填则生成 ETF 原文。',
  AI大模型: '例如：只要评测和开源权重，不要融资传闻。',
  Web开发: '例如：只要框架大版本和浏览器 API，不要招聘信息。',
  移动端: '例如：只要系统版本和应用上架，不要股价涨跌。',
  云原生: '例如：只要 Kubernetes 和成本优化，不要云厂商营销。',
  数据库: '例如：只要新引擎和兼容性变更，不要招聘信息。',
  安全: '例如：只要高危漏洞和供应链投毒，不要会议预告。',
  DevOps: '例如：只要流水线和可观测性工具，不要大会日程。',
  数据分析: '例如：只要数仓和 BI 产品发布，不要培训广告。',
  机器学习: '例如：只要训练框架和推理加速，不要课程推销。',
  区块链: '例如：只要协议升级和监管，不要币价喊单。',
}

const interestKey = (topic: string) => topic.trim().toLocaleLowerCase().replace(/\s+/g, '')

export const MAX_INTENT_LENGTH = 120

export function normalizeIntent(value?: string | null) {
  return (value || '').trim().replace(/\s+/g, ' ')
}

export function topicOverview(topic: string) {
  const key = interestKey(topic)
  if (key === interestKey(AI_TECH) || key === '科技') return PRESET_OVERVIEWS[AI_TECH]
  if (key === interestKey(ETF) || key === 'etf' || key === '市场观察') return PRESET_OVERVIEWS[ETF]
  for (const [name, text] of Object.entries(PRESET_OVERVIEWS)) {
    if (interestKey(name) === key) return text
  }
  const label = topic.trim() || '这个主题'
  return `按「${label}」检索近 48 小时公开资讯（含中英文别名）。写了「我想看」会优先按这个角度找；当天没有这个角度时，会用主题相近资讯写一版，不会编造。`
}

export function topicIntentHint(topic: string) {
  const key = interestKey(topic)
  if (key === interestKey(AI_TECH) || key === '科技') return PRESET_HINTS[AI_TECH]
  if (key === interestKey(ETF) || key === 'etf' || key === '市场观察') return PRESET_HINTS[ETF]
  for (const [name, text] of Object.entries(PRESET_HINTS)) {
    if (interestKey(name) === key) return text
  }
  const label = topic.trim() || '这个主题'
  return `例如：只要「${label}」的产品发布和人物言论，不要股价涨跌。优先按这句话检索；没有这个角度就写主题相近内容。`
}
