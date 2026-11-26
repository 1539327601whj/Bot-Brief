import type { CoreView, EventView, PricePoint } from './types'

export const GROUPS = [
  { key: 'technology', label: '科技' },
  { key: 'chinext', label: '创业板' },
  { key: 'csi300', label: '沪深 300' },
  { key: 'dividend', label: '红利' },
  { key: 'gold', label: '黄金' },
] as const
export type AssetGroup = typeof GROUPS[number]['key']
type Allocation = { weight: number; floor: number; benchmark: string }
export type Holding = { code: string; name: string; group: AssetGroup; shares: number; cost: number }
export type Fill = {
  code: string; name: string; group: AssetGroup; side: 'buy' | 'sell'; shares: number; price: number
}
export type Portfolio = { capital: number; allocations: Record<AssetGroup, Allocation>; holdings: Holding[] }

export const emptyPortfolio: Portfolio = {
  capital: 0,
  allocations: {
    technology: { weight: 0, floor: 1, benchmark: '' },
    chinext: { weight: 0, floor: 1, benchmark: '' },
    csi300: { weight: 0, floor: 1, benchmark: '510300' },
    dividend: { weight: 0, floor: 1, benchmark: '' },
    gold: { weight: 0, floor: 1, benchmark: '' },
  },
  holdings: [],
}

export function readPortfolio(key: string): Portfolio {
  try {
    const raw = localStorage.getItem(key)
    if (raw) {
      const value: Portfolio = JSON.parse(raw)
      if (value.capital >= 0 && Array.isArray(value.holdings)
        && GROUPS.every(({ key: group }) => value.allocations?.[group])) return value
    }
  } catch {
    // Storage can be unavailable or contain an older record.
  }
  return emptyPortfolio
}

export function applyFill(portfolio: Portfolio, fill: Fill): Portfolio {
  if (!Number.isSafeInteger(fill.shares) || fill.shares <= 0
    || !Number.isFinite(fill.price) || fill.price <= 0) throw new Error('请输入正整数份额和有效成交价')
  const value = Math.round(fill.price * fill.shares * 100) / 100
  if (!Number.isSafeInteger(Math.round(value * 100)) || value <= 0) throw new Error('成交金额超出可记录范围')
  const holding = portfolio.holdings.find(item => item.code === fill.code)
  if (holding && holding.group !== fill.group) throw new Error('这只 ETF 已有持仓，成交须记入原资产类别')
  if (fill.side === 'sell' && (!holding || fill.shares > holding.shares)) {
    throw new Error('卖出份额不能超过已录入的持仓')
  }
  const shares = (holding?.shares ?? 0) + (fill.side === 'buy' ? fill.shares : -fill.shares)
  const cost = fill.side === 'buy' ? (holding?.cost ?? 0) + value
    : holding ? holding.cost * shares / holding.shares : 0
  if (!Number.isSafeInteger(shares) || !Number.isSafeInteger(Math.round(cost * 100))) {
    throw new Error('更新后的持仓超出可记录范围')
  }
  const updated: Holding = {
    code: fill.code, name: fill.name, group: fill.group, shares, cost: Math.round(cost * 100) / 100,
  }
  const holdings = portfolio.holdings.filter(item => item.code !== fill.code)
  if (shares > 0) holdings.push(updated)
  return { ...portfolio, holdings }
}

export function groupAmounts(portfolio: Portfolio, group: AssetGroup) {
  const plan = portfolio.allocations[group]
  const budget = portfolio.capital * plan.weight / 100
  const totalUsed = portfolio.holdings.reduce((sum, position) => sum + position.cost, 0)
  const used = portfolio.holdings.filter(position => position.group === group)
    .reduce((sum, position) => sum + position.cost, 0)
  const floor = budget * plan.floor / 10
  return { budget, used, floor, wave: Math.max(0, used - floor),
    remaining: Math.max(0, budget - used), cash: Math.max(0, portfolio.capital - totalUsed) }
}

export function suggestedEntry(core: CoreView, group: AssetGroup, hasFloor: boolean) {
  const technical = core.technical
  const research = core.etfResearch
  const action = core.decision?.action.key
  const price = core.quote?.price
  if (price == null || price <= 0 || technical?.lastDate == null
    || core.verdict.hardFlags?.includes('PRICE_STALE')) {
    return { tranches: 0, reason: '需要近期有效行情才能评估进场' }
  }
  if (action === 'AVOID' || core.verdict.hardFlags?.some(flag => /SUSPEND|HALT|DELIST|LOW_LIQUIDITY/.test(flag))) {
    return { tranches: 0, reason: '当前命中风险约束，等待解除后复核' }
  }
  const momentum20 = technical.momentum20d
  const momentum60 = technical.momentum60d
  if (momentum20 == null || momentum20 < 0 || technical.ma20 == null || price < technical.ma20) {
    return { tranches: 0, reason: '等待价格站上 20 日均线、20 日涨跌转为非负' }
  }
  if (group === 'gold') {
    if (!isGoldFund(core) || core.etfResearch?.gold?.lowPrice !== true) {
      return { tranches: 0, reason: '等待确认黄金 ETF 进入低位区间' }
    }
  } else {
    if (research?.lowPrice !== true || !research.candidate || action !== 'BUILD_GRADUALLY') {
      return { tranches: 0, reason: '需要低位、底层盈利支撑和进场时机同时确认' }
    }
    if (core.buyZone?.status !== 'IN_ZONE') {
      return { tranches: 0, reason: '当前参考区间未确认或价格未进入区间' }
    }
  }
  const volume = research?.volume
  const confirmation = hasFloor && momentum60 != null && momentum60 >= 0
    && volume?.volumeVsPrevious20 != null && volume.volumeVsPrevious20 > 1
    && volume.closeChange != null && volume.closeChange > 0
    && core.decision?.longTerm?.key === 'UP'
  return { tranches: confirmation ? 2 : 1, reason: confirmation
    ? '已有底仓，长短期趋势得到放量收涨的支持'
    : '价格已在低位，短期出现企稳信号，可评估分批进场' }
}

export function isGoldFund(core: CoreView) {
  return core.etfResearch?.gold != null
    || (/黄金|上海金/.test(core.name ?? '') && !/黄金股|黄金产业|金矿/.test(core.name ?? ''))
}

export function closedPrices(points: PricePoint[], now = new Date()) {
  const shanghai = new Date(now.getTime() + 8 * 3600_000).toISOString()
  const cutoff = shanghai.slice(11, 16) < '15:00'
    ? new Date(now.getTime() - 16 * 3600_000).toISOString().slice(0, 10) : shanghai.slice(0, 10)
  const unique = new Map(points.filter(point => point.date <= cutoff && point.close != null && point.close > 0)
    .map(point => [point.date, point.close as number]))
  return [...unique].sort(([left], [right]) => left.localeCompare(right))
}

export function compareReturns(first: PricePoint[], second: PricePoint[], days: number) {
  const pricesA = closedPrices(first)
  const pricesB = closedPrices(second)
  const intervalsB = new Map(pricesB.slice(1).map(([date, close], i) =>
    [date, { from: pricesB[i][0], start: pricesB[i][1], close }]))
  const matched = pricesA.slice(1).flatMap(([to, close], i) => {
    const [from, start] = pricesA[i]
    const other = intervalsB.get(to)
    return other?.from === from ? [{ from, to, start, close, other }] : []
  }).slice(-days)
  if (matched.length < days) return null
  const pairs = matched.map(({ start, close, other }) => [
    close / start - 1, other.close / other.start - 1,
  ])
  const meanA = pairs.reduce((sum, [a]) => sum + a, 0) / days
  const meanB = pairs.reduce((sum, [, b]) => sum + b, 0) / days
  const covariance = pairs.reduce((sum, [a, b]) => sum + (a - meanA) * (b - meanB), 0)
  const varianceA = pairs.reduce((sum, [a]) => sum + (a - meanA) ** 2, 0)
  const varianceB = pairs.reduce((sum, [, b]) => sum + (b - meanB) ** 2, 0)
  const downDays = pairs.filter(([, b]) => b < 0)
  const firstPoint = matched[0]
  const lastPoint = matched[days - 1]
  return {
    from: firstPoint.from, to: lastPoint.to, correlation: varianceA > 0 && varianceB > 0
      ? Math.max(-1, Math.min(1, covariance / Math.sqrt(varianceA * varianceB))) : null,
    relative: (lastPoint.close / firstPoint.start - lastPoint.other.close / firstPoint.other.start) * 100,
    down: downDays.length, bothDown: downDays.filter(([a]) => a < 0).length,
  }
}

export function eventReaction(event: EventView, points: PricePoint[]) {
  const stamp = event.publishedAt
  if (!stamp) return null
  const newsDay = stamp.slice(0, 10)
  const time = stamp.slice(11, 16)
  const afterOpenOrUnknown = stamp.length < 16 || time === '00:00' || time >= '09:30'
  const prices = closedPrices(points)
  const index = prices.findIndex(([date]) => afterOpenOrUnknown ? date > newsDay : date >= newsDay)
  if (index <= 0 || Date.parse(prices[index][0]) - Date.parse(newsDay) > 7 * 86400_000) return null
  const baseline = prices[index - 1][1]
  return { from: prices[index - 1][0], firstDate: prices[index][0],
    first: (prices[index][1] / baseline - 1) * 100,
    thirdDate: prices[index + 2]?.[0],
    third: prices[index + 2] ? (prices[index + 2][1] / baseline - 1) * 100 : null }
}
