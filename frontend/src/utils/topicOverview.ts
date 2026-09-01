const AI_TECH = 'AI科技'
const ETF = '纳指标普沪深300ETF'

const PRESET_OVERVIEWS: Record<string, string> = {
  [AI_TECH]: '默认覆盖 AI、芯片、互联网、航天等科技要闻，生成全站科技早晚报原文。',
  [ETF]: '默认覆盖纳指、标普、沪深300 ETF 的行情与估值，生成市场观察原文。',
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
  return `按「${label}」检索近 24 小时相关资讯并写成简报。不填想法时按这个词本身检索。`
}

export function topicIntentHint(topic: string) {
  const key = interestKey(topic)
  if (key === interestKey(AI_TECH) || key === '科技') {
    return '例如：只要芯片和航天，不要消费电子。不填则生成全站科技原文。'
  }
  if (key === interestKey(ETF) || key === 'etf' || key === '市场观察') {
    return '例如：只要溢折价和资金流向。不填则生成 ETF 原文。'
  }
  return '例如：只要产品发布和人物言论，不要股价涨跌。不填则按系统默认检索。'
}
