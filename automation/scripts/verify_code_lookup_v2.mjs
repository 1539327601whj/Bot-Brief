#!/usr/bin/env node
/*
 * 「代码查询 v2」的**无后端真机截图**。
 *
 * 这一页要看的是「读起来对不对」：结论卡有没有主次、六维块在窄屏是不是两列、
 * 缺数据的地方有没有写出原因而不是留下 0、429 会不会自己重试。
 * 这些都不是单元测试能断言的，只能画出来看。
 *
 * 做法与 `verify_analysis_page.mjs` 相同：起一个假后端（只回夹具 JSON），
 * 把构建好的 `frontend/dist` 静态发出去，用 Edge 无头模式打开，真的去填代码、
 * 点查询、切标签页，然后整页截图。
 *
 * **不需要真后端，也不需要数据库。** 夹具在本文件内构造，字段与
 * `frontend/src/pages/codeLookup/types.ts` 一一对应；DTO 改了这里会编译期对不上，
 * TypeScript 不检查这个文件，所以改 DTO 时请顺手看它一眼。
 *
 * 用法：
 *   node automation/scripts/verify_code_lookup_v2.mjs                 # 全跑
 *   node automation/scripts/verify_code_lookup_v2.mjs --case stock    # 只跑个股
 *   node automation/scripts/verify_code_lookup_v2.mjs --case rate-limited
 *
 * 前置：`cd frontend && npm run build`
 */

import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

// ---------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------

const args = process.argv.slice(2)
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}

const REPO = resolve(import.meta.dirname, '..', '..')
const DIST = resolve(REPO, flag('dist', 'frontend/dist'))
const OUT = resolve(REPO, flag('out', 'automation/screenshots/code-lookup-v2'))
const PORT = Number(flag('port', '8093'))
const ONLY = flag('case', null)

const EDGE_CANDIDATES = [
  process.env.EDGE_PATH,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
].filter(Boolean)

const DISCLAIMER = '本页仅为公开数据的规则化整理结果，不构成投资建议、证券推荐或买卖依据。'

// ---------------------------------------------------------------------
// 夹具
// ---------------------------------------------------------------------

const POINTS = 260

function tradingDates(count) {
  const dates = []
  const date = new Date(Date.UTC(2026, 9, 16))
  while (dates.length < count) {
    const day = date.getUTCDay()
    if (day !== 0 && day !== 6) dates.push(date.toISOString().slice(0, 10))
    date.setUTCDate(date.getUTCDate() - 1)
  }
  return dates.reverse()
}

function pricePoints(count = POINTS) {
  return tradingDates(count).map((date, index) => {
    const close = 1580 + Math.sin(index / 17) * 62 + index * 0.34
    return {
      date,
      open: Number((close - 6).toFixed(2)),
      high: Number((close + 11).toFixed(2)),
      low: Number((close - 13).toFixed(2)),
      close: Number(close.toFixed(2)),
      volume: 2_400_000 + (index % 13) * 90_000,
      amount: 3.7e9,
      pctChange: Number((Math.sin(index / 9) * 1.8).toFixed(2)),
      turnoverRate: 0.42,
    }
  })
}

function valuationPoints(count = 1200, basePe = 26) {
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(Date.UTC(2018, 0, 2))
    date.setUTCDate(date.getUTCDate() + index * 2)
    return {
      date: date.toISOString().slice(0, 10),
      peTtm: Number((basePe + Math.sin(index / 70) * basePe * 0.27).toFixed(2)),
      pbMrq: Number((basePe / 3.7 + Math.sin(index / 80) * 1.3).toFixed(2)),
      dividendYield: Number((2.1 + Math.sin(index / 110) * 0.5).toFixed(2)),
      percentile: Number((50 + Math.sin(index / 60) * 34).toFixed(1)),
    }
  })
}

function moneyflowPoints(count = 60) {
  return tradingDates(count).map((date, index) => ({
    date,
    mainNet: Math.sin(index / 4) * 1.5e8,
    superNet: Math.sin(index / 5) * 7e7,
    largeNet: Math.sin(index / 6) * 4e7,
    mediumNet: Math.cos(index / 5) * 3e7,
    smallNet: Math.cos(index / 4) * 2e7,
  }))
}

function metric(key, label, value, display, score, weight, evidenceId, basis) {
  return { key, label, value, display, score, weight, basis, evidenceId }
}

const DIMENSIONS = [
  {
    key: 'valuation', label: '估值', weight: 22, score: 71, coverage: 0.9, status: 'OK',
    metrics: [
      metric('pe_ttm', 'PE(TTM)', 21.4, '21.4 倍', 74, 30, 'ev:val:pe_ttm', '历史 PE 序列最新点'),
      metric('pe_percentile', 'PE 分位', 28, '28%', 78, 30, 'ev:val:pe_pct', '2018-01 至今'),
      metric('pb', 'PB', 7.1, '7.1 倍', 62, 20, 'ev:val:pb', '同一条估值序列'),
      metric('dividend_yield', '股息率', 2.3, '2.3%', 58, 20, 'ev:val:dy', '近 12 个月'),
    ],
    reasons: ['PE 分位低于 30%，估值处于历史偏低区间。'],
  },
  {
    key: 'growth', label: '成长', weight: 18, score: 64, coverage: 0.55, status: 'PARTIAL',
    metrics: [
      metric('revenue_yoy', '营收同比', 12.4, '+12.4%', 66, 30, 'ev:fin:rev_yoy', '2026 中报'),
      metric('profit_yoy', '净利同比', 15.1, '+15.1%', 68, 35, 'ev:fin:np_yoy', '2026 中报'),
      metric('revenue_cagr3', '3 年营收 CAGR', null, null, null, 20, null, null),
      metric('profit_cagr3', '3 年净利 CAGR', null, null, null, 15, null, null),
    ],
    reasons: ['只有 2026 中报一期可用于同比，三年复合增速缺失。'],
  },
  {
    key: 'quality', label: '盈利质量', weight: 18, score: 76, coverage: 1, status: 'OK',
    metrics: [
      metric('roe', 'ROE', 28.6, '28.6%', 82, 35, 'ev:fin:roe', '2026 中报'),
      metric('gross_margin', '毛利率', 91.2, '91.2%', 88, 25, 'ev:fin:gm', '2026 中报'),
      metric('ocf_np', 'CFO / 净利润', 1.06, '1.06', 74, 25, 'ev:fin:ocf', '2026 中报'),
      metric('ar_rev', '应收 / 营收', 4.2, '4.2%', 80, 15, 'ev:fin:ar', '2026 中报'),
    ],
    reasons: ['经营现金流覆盖净利润，应收占比低。'],
  },
  {
    key: 'health', label: '财务健康', weight: 14, score: 81, coverage: 1, status: 'OK',
    metrics: [
      metric('debt_ratio', '资产负债率', 18.5, '18.5%', 86, 35, 'ev:fin:debt', '2026 中报'),
      metric('current_ratio', '流动比率', 3.4, '3.4', 80, 25, 'ev:fin:current', '2026 中报'),
      metric('interest_cover', '利息保障倍数', 42, '42 倍', 90, 25, 'ev:fin:cover', '2026 中报'),
      metric('goodwill', '商誉 / 净资产', 0, '0%', 88, 15, 'ev:fin:goodwill', '2026 中报'),
    ],
    reasons: ['负债率低，利息覆盖充足。'],
  },
  {
    key: 'technical', label: '技术资金', weight: 20, score: 55, coverage: 0.85, status: 'PARTIAL',
    metrics: [
      metric('price_pct1y', '1 年价格分位', 62, '62%', 52, 20, 'ev:tech:pct1y', '近 250 个交易日'),
      metric('vs_ma250', '收盘 / MA250', 6.4, '+6.4%', 60, 20, 'ev:tech:ma250', '日线'),
      metric('momentum60', '60 日动量', 4.1, '+4.1%', 58, 15, 'ev:tech:mom60', '日线'),
      metric('main_flow5', '5 日主力净流入', -1.2e8, '-1.2 亿', 42, 20, 'ev:tech:flow5', '资金流'),
      metric('turnover', '换手率', 0.42, '0.42%', 38, 15, 'ev:tech:turn', '当日'),
      metric('amount', '成交额', 3.7e9, '37.0 亿', 72, 10, 'ev:tech:amount', '当日'),
    ],
    reasons: ['价格在 MA250 上方，但近 5 日主力资金净流出。'],
  },
  {
    key: 'peers', label: '行业同行', weight: 8, score: null, coverage: 0, status: 'EMPTY',
    metrics: [
      metric('peer_pe', '同行 PE 分位', null, null, null, 30, null, null),
      metric('peer_growth', '同行营收增速排名', null, null, null, 25, null, null),
      metric('peer_roe', '同行 ROE 排名', null, null, null, 25, null, null),
      metric('industry_momentum', '行业 20 日动量', null, null, null, 20, null, null),
    ],
    reasons: ['同行业样本不足 8 只，该维度不评分。'],
  },
]

const LOOKBACK_PRICE = [
  ['昨', '2026-10-15', 1712.4, 0.62],
  ['周', '2026-10-09', 1698.1, 1.47],
  ['月', '2026-09-16', 1654.2, 4.19],
  ['半年', '2026-04-16', 1520.6, 13.34],
  ['一年', '2025-10-16', 1466.0, 17.56],
  ['三年', '2023-10-16', 1728.5, -0.28],
  ['五年', null, null, null],
  ['十年', null, null, null],
].map(([label, baselineDate, baseline, change]) => ({
  label,
  baselineDate,
  baseline,
  change,
  status: baseline === null ? '本地日线覆盖不到这一天（需要 5 年以上日线）' : null,
}))

const LOOKBACK_PE = [
  ['昨', '2026-10-15', 21.5, -2.1],
  ['周', '2026-10-09', 21.1, 0.7],
  ['月', '2026-09-16', 22.4, -7.8],
  ['半年', '2026-04-16', 24.1, -16.3],
  ['一年', '2025-10-16', 26.8, -34.5],
  ['三年', '2023-10-16', 32.2, -54.6],
  ['五年', '2021-10-15', 44.1, -83.9],
  ['十年', null, null, null],
].map(([label, baselineDate, baseline, change]) => ({
  label,
  baselineDate,
  baseline,
  change,
  status: baseline === null ? '估值历史从 2018-01 开始，覆盖不到十年前' : null,
}))

const STOCK_CORE = {
  inputCode: '600519',
  code: 'STOCK:600519',
  name: '贵州茅台',
  assetType: 'STOCK',
  market: '沪市',
  industry: '白酒',
  sectorProfile: 'CONSUMER',
  trackingIndexCode: null,
  snapshotId: '6d1c2a9f4b7e0358',
  snapshotAt: '2026-10-16T15:05:11',
  fromCache: false,
  syncPending: false,
  retryAfterMs: 0,
  quote: {
    provider: 'eastmoney', providerLabel: '东方财富',
    price: 1722.6, pctChange: 0.62, amount: 3.7e9, turnoverRate: 0.42,
    marketCap: 2.16e12, peTtm: 21.4, pb: 7.1, dividendYield: 2.3,
    asOf: '2026-10-16T15:00:00',
  },
  valuation: {
    source: 'eastmoney-valueanalysis', sourceLabel: '东方财富估值分析',
    method: 'PE_TTM 时间分位', peTtm: 21.4, pePercentile: 28,
    peWindowFrom: '2018-01-02', pePoints: 1200, pbMrq: 7.1, pbPercentile: 31,
    dividendYield: 2.3, historyLength: 1200, asOf: '2026-10-16',
    note: '窗口为 2018-01 至今，不是完整十年。',
  },
  technical: {
    barCount: 260, lastDate: '2026-10-16', price: 1722.6, hasOhlc: true,
    ma20: 1698.2, ma60: 1666.4, ma120: 1621.7, ma250: 1618.9,
    atr14: 33.4, low20: 1650.2, low60: 1596.8, low120: 1511.3,
    high1y: 1810.4, low1y: 1388.2, pricePercentile1y: 62,
    momentum20d: 2.8, momentum60d: 4.1,
  },
  verdict: {
    label: '可分批关注', labelKey: 'ACCUMULATE', score: 68, scoreLow: 58, scoreHigh: 79,
    coverage: 0.86, confidence: 0.78, eventAdjustment: 0,
    hardFlags: [],
    reasons: [
      { text: 'PE 分位 28%，估值处于历史偏低区间。', evidenceIds: ['ev:val:pe_pct'] },
      { text: 'ROE 28.6%、毛利率 91.2%，盈利质量稳定。', evidenceIds: ['ev:fin:roe', 'ev:fin:gm'] },
      { text: '收盘在 MA250 上方 6.4%，趋势未破坏。', evidenceIds: ['ev:tech:ma250'] },
    ],
    risks: [
      { text: '近 5 日主力资金净流出 1.2 亿。', evidenceIds: ['ev:tech:flow5'] },
      { text: '同行业样本不足，行业维度未评分。', evidenceIds: [] },
    ],
    withheldReason: null,
  },
  dimensions: DIMENSIONS,
  buyZone: {
    status: 'IN_ZONE', low: 1650, high: 1730, stopLoss: 1580,
    distanceToZonePct: -0.4, riskPct: 8.3,
    basis: [
      { text: '估值锚：PE 分位 25% / 40% 对应 1648 / 1728。', evidenceIds: ['ev:val:pe_pct'] },
      { text: '技术锚：MA20 与 20 日低点共振在 1650–1700。', evidenceIds: ['ev:tech:ma20', 'ev:tech:low20'] },
    ],
    notes: ['区间由估值分位与技术支撑合成，当前价已在区间内。'],
  },
  decision: {
    position: {
      key: 'NEUTRAL', label: '中性位置', availableAxes: 3, score: 1,
      axes: [
        { key: 'valuation', label: '估值位置', value: '28', display: '28%（偏低）', score: 1, status: 'OK', basis: 'PE 分位 28%' },
        { key: 'price', label: '价格位置', value: '62', display: '62%（中性）', score: 0, status: 'OK', basis: '一年价格分位 62%' },
        { key: 'trend', label: '趋势位置', value: 'ma250=6.4;momentum60=4.1', display: '相对 MA250 +6.4%，60 日动量 +4.1%（向上）', score: 1, status: 'OK', basis: '站上 MA250 且动量为正' },
      ],
      reasons: [{ text: '估值位置：28%（偏低）', evidenceIds: ['ev:decision:valuation'] }],
    },
    timing: {
      key: 'ACCUMULATE', label: '可以分批',
      reasons: [{ text: '估值偏低且趋势未破坏。', evidenceIds: ['ev:decision:timing'] }],
      triggers: ['跌破 MA250 且 60 日动量 ≤ -10% 时重新评估'],
    },
    action: {
      key: 'BUILD_GRADUALLY', label: '分批建仓',
      tranchePlan: ['先建 1/3 观察仓，跌破区间下沿再评估'],
      invalidation: ['趋势转为向下或估值分位升破 70%'],
    },
    horizon: {
      key: 'LONG', label: '长期更优', longScore: 71.2, shortScore: 58.4, gap: 12.8,
      summary: '长期分 71.2，短期分 58.4，长期更优：长期估值与趋势证据强于短期动量。',
    },
    longTerm: {
      key: 'UP', label: '长期向上',
      threeYearCagrPct: 8.2, fiveYearCagrPct: 6.1,
      threeYearMaxDrawdownPct: 42.0, drawdownRecovered: true,
      summary: '近 3 年年化 +8.2%；近 5 年年化 +6.1%；近 3 年最大回撤 42.0%，已回到回撤前高点',
    },
    outlook: {
      available: true, currentPercentile: 62,
      horizons: [
        { key: '1y', label: '未来 1 年（250 个交易日）', sampleCount: 96, medianReturnPct: 7.4, p25ReturnPct: -8.2, p75ReturnPct: 21.6, winRatePct: 63.5 },
        { key: '3y', label: '未来 3 年（750 个交易日）', sampleCount: 34, medianReturnPct: 25.1, p25ReturnPct: -3.4, p75ReturnPct: 48.9, winRatePct: 76.5 },
      ],
      status: null,
      note: '历史统计来自本标的已有日线，样本相互重叠，且不包含未来可能出现的制度、行业与估值变化；它不预测收益，也不构成投资建议。',
    },
    reasons: [
      { text: '估值位置：28%（偏低）', evidenceIds: ['ev:decision:valuation'] },
      { text: '趋势位置：相对 MA250 +6.4%，60 日动量 +4.1%（向上）', evidenceIds: ['ev:decision:trend'] },
    ],
    risks: [{ text: '近 5 日主力资金净流出 1.2 亿。', evidenceIds: ['ev:tech:flow5'] }],
    watchItems: ['跌破 MA250 且 60 日动量 ≤ -10% 时重新评估'],
    summary: '位置：中性位置 · 时机：可以分批 · 行动：分批建仓 · 周期：长期更优',
    disclaimer: '以上为规则计算的倾向与观察条件，不构成投资建议、证券推荐或买卖依据。',
  },
  priceLookbacks: LOOKBACK_PRICE,
  peLookbacks: LOOKBACK_PE,
  data: {
    quote: { status: 'OK', note: null, asOf: '2026-10-16T15:00:00' },
    valuation: { status: 'OK', note: null, asOf: '2026-10-16' },
    technical: { status: 'OK', note: null, asOf: '2026-10-16' },
    financial: { status: 'PARTIAL', note: '只取到 1 期财报，三年复合增速缺失', asOf: '2026-06-30' },
    moneyflow: { status: 'EMPTY', note: '免费源当日未返回资金流', asOf: null },
    industry: { status: 'OK', note: null, asOf: '2026-10-16' },
    events: { status: 'OK', note: null, asOf: '2026-10-16' },
  },
  notes: ['财务数据按公告日 available_from 过滤，未使用未来数据。'],
  rulesVersion: 'code-lookup-v2-p5',
  disclaimer: DISCLAIMER,
}

const ETF_DIMENSIONS = [
  {
    key: 'valuation', label: '估值', weight: 30, score: 62, coverage: 1, status: 'OK',
    metrics: [
      metric('index_pe_pct', '跟踪指数 PE 分位', 46, '46%', 55, 70, 'ev:val:pe_pct', '中证指数，2016-01 至今'),
      metric('pe_to_median', 'PE / 10 年中位数', 0.96, '0.96', 72, 30, 'ev:val:pe_median', '中证指数'),
    ],
    reasons: ['跟踪指数 PE 分位 46%，处于中位。'],
  },
  {
    key: 'trend', label: '趋势', weight: 22, score: 58, coverage: 1, status: 'OK',
    metrics: [
      metric('price_pct1y', '1 年价格分位', 55, '55%', 50, 40, 'ev:tech:pct1y', 'ETF 日线'),
      metric('vs_ma250', '收盘 / MA250', 3.3, '+3.3%', 60, 30, 'ev:tech:ma250', 'ETF 日线'),
      metric('momentum60', '60 日动量', 4.1, '+4.1%', 58, 30, 'ev:tech:mom60', 'ETF 日线'),
    ],
    reasons: ['价格在 MA250 上方，趋势未破坏。'],
  },
  {
    key: 'liquidity', label: '流动性', weight: 18, score: 66, coverage: 1, status: 'OK',
    metrics: [
      metric('amount20', '20 日均成交额', 1.9e9, '19.0 亿', 75, 50, 'ev:liq:amount', 'ETF 日线'),
      metric('turnover', '换手率', 0.37, '0.37%', 57, 50, 'ev:liq:turn', '当日'),
    ],
    reasons: ['成交额充足，换手处于常态区间。'],
  },
  {
    key: 'size', label: '规模', weight: 12, score: 88, coverage: 1, status: 'OK',
    metrics: [metric('etf_aum', '基金规模（亿）', 194.68, '194.68 亿', 88, 100,
      'ev:fund:aum', '基金净资产（截止 2026-06-30），越大越不容易被清盘')],
    reasons: ['基金净资产 194.68 亿，规模在同类型中靠前。'],
  },
  {
    key: 'structure', label: '结构', weight: 10, score: 66, coverage: 1, status: 'OK',
    metrics: [
      metric('top10', '前十大权重', 22.4, '22.4%', 66, 50, 'ev:etf:top10', '中证指数'),
      metric('industry_hhi', '行业 HHI', 0.14, '0.14', 68, 50, 'ev:etf:hhi', '中证指数'),
    ],
    reasons: ['权重分散，单一行业集中度不高。'],
  },
  {
    key: 'tracking', label: '跟踪质量', weight: 8, score: 74, coverage: 0.6, status: 'PARTIAL',
    metrics: [
      metric('etf_tracking_error', '年化跟踪误差', 1.02, '1.02%', 74, 60, 'ev:fund:tracking',
        '年化跟踪误差，越小越好；同类平均 2.21%（截止 2026-10-19）'),
      // 折溢价是**带权重的不可用**：保留 40 的权重，覆盖率因此如实为 0.6，而不是把缺项当满分。
      metric('etf_premium', '折溢价', null, null, null, 40, 'ev:etf:premium',
        '折溢价率没有稳定的免费时间序列，该子项不计分'),
    ],
    reasons: ['跟踪误差 1.02%，优于同类平均 2.21%；折溢价缺数据，覆盖率按 60% 计。'],
  },
]

const INDEX_DIMENSIONS = [
  {
    key: 'valuation', label: '估值', weight: 32, score: 60, coverage: 1, status: 'OK',
    metrics: [
      metric('pe_pct', 'PE 分位', 46, '46%', 52, 60, 'ev:val:pe_pct', '中证指数'),
      metric('dividend_yield', '股息率', 2.6, '2.6%', 62, 20, 'ev:val:dy', '中证指数'),
      metric('pb_pct', 'PB 分位', 52, '52%', 48, 20, 'ev:val:pb_pct', '中证指数'),
    ],
    reasons: ['估值处于历史中位。'],
  },
  {
    key: 'trend', label: '趋势', weight: 22, score: 58, coverage: 1, status: 'OK',
    metrics: [
      metric('price_pct1y', '1 年价格分位', 55, '55%', 50, 40, 'ev:tech:pct1y', '指数日线'),
      metric('vs_ma250', '收盘 / MA250', 3.3, '+3.3%', 60, 30, 'ev:tech:ma250', '指数日线'),
      metric('momentum60', '60 日动量', 4.1, '+4.1%', 58, 30, 'ev:tech:mom60', '指数日线'),
    ],
    reasons: ['指数在 MA250 上方。'],
  },
  {
    key: 'structure', label: '结构', weight: 16, score: 74, coverage: 1, status: 'OK',
    metrics: [
      metric('top10', '前十大权重', 22.4, '22.4%', 70, 50, 'ev:idx:top10', '中证指数'),
      metric('industry_hhi', '行业 HHI', 0.14, '0.14', 78, 50, 'ev:idx:hhi', '中证指数'),
    ],
    reasons: ['权重分散。'],
  },
  {
    key: 'scale', label: '规模 / 流动性', weight: 15, score: 72, coverage: 1, status: 'OK',
    metrics: [
      metric('constituents', '成份数量', 300, '300 只', 70, 40, 'ev:idx:count', '中证指数'),
      metric('market_cap', '总市值', 6.4e13, '64.0 万亿', 75, 30, 'ev:idx:cap', '中证指数'),
      metric('amount', '成交额', 2.6e11, '2600 亿', 72, 30, 'ev:idx:amount', '当日'),
    ],
    reasons: ['覆盖度与流动性处于宽基指数前列。'],
  },
  {
    key: 'earnings', label: '成份盈利', weight: 10, score: null, coverage: 0, status: 'EMPTY',
    metrics: [
      metric('roe_median', '成份 ROE 中位数', null, null, null, 50, null, null),
      metric('profit_growth', '成份净利增速中位数', null, null, null, 50, null, null),
    ],
    reasons: ['免费源没有成份盈利中位数。'],
  },
  {
    key: 'industry', label: '行业景气', weight: 5, score: null, coverage: 0, status: 'EMPTY',
    metrics: [
      metric('industry_momentum', '行业板块 60 日相对收益', null, null, null, 100, null, null),
    ],
    reasons: ['行业板块历史积累不足。'],
  },
]

const ETF_CORE = {
  ...STOCK_CORE,
  inputCode: '510300', code: 'ETF:510300', name: '沪深300ETF', assetType: 'ETF',
  market: '沪市', industry: null, sectorProfile: 'BROAD_INDEX',
  trackingIndexCode: '000300', snapshotId: 'a8f30c1d9e2b7461',
  quote: { ...STOCK_CORE.quote, price: 4.128, pctChange: 0.34, amount: 1.9e9, marketCap: 3.4e11 },
  valuation: {
    source: 'csindex', sourceLabel: '中证指数', method: '跟踪指数 PE 分位',
    peTtm: 13.2, pePercentile: 46, peWindowFrom: '2016-01-04', pePoints: 2600,
    pbMrq: 1.42, pbPercentile: 52, dividendYield: 2.6, historyLength: 2600,
    asOf: '2026-10-16', note: null,
  },
  verdict: {
    ...STOCK_CORE.verdict, label: '观察等待', labelKey: 'WAIT', score: 57,
    scoreLow: 49, scoreHigh: 68, coverage: 0.78, confidence: 0.72,
    reasons: [{ text: '跟踪指数 PE 分位 46%，处于中位。', evidenceIds: ['ev:val:pe_pct'] }],
    risks: [{ text: '折溢价缺少可靠免费源，跟踪质量只按跟踪误差评分。', evidenceIds: [] }],
  },
  dimensions: ETF_DIMENSIONS,
  buyZone: {
    ...STOCK_CORE.buyZone, status: 'ABOVE_ZONE', low: 3.92, high: 4.06, stopLoss: 3.78,
    distanceToZonePct: 1.7, riskPct: 8.4,
    basis: [
      { text: '估值锚：跟踪指数 PE 分位 25% / 40% 对应 ETF 3.90 / 4.02。', evidenceIds: ['ev:val:pe_pct'] },
      { text: '技术锚：MA20 与 20 日低点共振在 3.95–4.05。', evidenceIds: ['ev:tech:ma20', 'ev:tech:low20'] },
    ],
    notes: ['用跟踪指数估值分位反推 ETF 价格，假设成分股盈利不变。'],
  },
  peLookbacks: LOOKBACK_PE.map(row => ({
    ...row,
    status: row.status ?? row.status,
  })),
  data: {
    ...STOCK_CORE.data,
    financial: { status: 'NOT_APPLICABLE', note: 'ETF 不适用财务三表', asOf: null },
    moneyflow: { status: 'EMPTY', note: 'ETF 资金流免费源不稳定', asOf: null },
  },
}

const INDEX_CORE = {
  ...STOCK_CORE,
  inputCode: '000300', code: 'INDEX:000300', name: '沪深300', assetType: 'INDEX',
  market: '中证指数', industry: null, sectorProfile: 'BROAD_INDEX',
  trackingIndexCode: '000300', snapshotId: 'f0b4d7c2a19e6538',
  quote: { ...STOCK_CORE.quote, price: 4128.6, pctChange: 0.41, amount: 2.6e11, marketCap: null },
  valuation: {
    source: 'csindex', sourceLabel: '中证指数', method: 'PE_TTM 时间分位',
    peTtm: 13.2, pePercentile: 46, peWindowFrom: '2016-01-04', pePoints: 2600,
    pbMrq: 1.42, pbPercentile: 52, dividendYield: 2.6, historyLength: 2600,
    asOf: '2026-10-16', note: null,
  },
  technical: { ...STOCK_CORE.technical, price: 4128.6, ma250: 3998.2 },
  verdict: {
    ...STOCK_CORE.verdict, label: '观察等待', labelKey: 'WAIT', score: 57,
    scoreLow: 50, scoreHigh: 66, coverage: 0.82, confidence: 0.8,
    reasons: [{ text: 'PE 分位 46%，估值中位；指数在 MA250 上方。', evidenceIds: ['ev:val:pe_pct', 'ev:tech:ma250'] }],
    risks: [{ text: '成份盈利数据未覆盖，盈利维度不评分。', evidenceIds: [] }],
  },
  dimensions: INDEX_DIMENSIONS,
  buyZone: {
    ...STOCK_CORE.buyZone, status: 'ABOVE_ZONE', low: 3920, high: 4060, stopLoss: 3790,
    distanceToZonePct: 1.7, riskPct: 8.2,
    basis: [
      { text: '估值锚：PE 分位 25% / 40% 对应 3905 / 4022 点。', evidenceIds: ['ev:val:pe_pct'] },
      { text: '技术锚：MA20 与 20 日低点共振在 3950–4060。', evidenceIds: ['ev:tech:ma20', 'ev:tech:low20'] },
    ],
    notes: ['指数点位区间；对应 ETF 价格需按跟踪比例换算。'],
  },
  data: {
    ...STOCK_CORE.data,
    financial: { status: 'NOT_APPLICABLE', note: '指数不适用财务三表', asOf: null },
    moneyflow: { status: 'NOT_APPLICABLE', note: '指数没有主力资金口径', asOf: null },
  },
}

const PARTIAL_CORE = {
  ...STOCK_CORE,
  inputCode: '300274', code: 'STOCK:300274', name: '阳光电源', assetType: 'STOCK',
  industry: '光伏设备', snapshotId: 'c31e5a8d2f640b97',
  syncPending: true,
  quote: { ...STOCK_CORE.quote, price: 92.4, pctChange: -1.2, amount: 8.2e8, marketCap: 1.9e11 },
  valuation: {
    ...STOCK_CORE.valuation,
    source: null, sourceLabel: null, method: null,
    peTtm: null, pePercentile: null, peWindowFrom: null, pePoints: 0,
    pbMrq: null, pbPercentile: null, dividendYield: null, historyLength: null,
    asOf: null, note: '估值源本轮 429，未重试',
  },
  technical: {
    ...STOCK_CORE.technical,
    barCount: 80, ma120: null, ma250: null, pricePercentile1y: null, momentum60d: null,
  },
  verdict: {
    label: '数据不足，暂不评级', labelKey: 'INSUFFICIENT', score: null,
    scoreLow: null, scoreHigh: null, coverage: 0.42, confidence: 0.4,
    eventAdjustment: null, hardFlags: [],
    reasons: [], risks: [],
    withheldReason: '估值与财务数据覆盖不足 50%，不输出分数与区间。',
  },
  dimensions: DIMENSIONS.map((d, index) => (index < 2
    ? { ...d, score: null, coverage: 0, status: 'FAILED', reasons: ['本次同步失败，等待后台补齐。'] }
    : index === 2
      ? { ...d, score: null, coverage: 0, status: 'EMPTY', reasons: ['财务数据为空。'] }
      : { ...d, score: null, coverage: 0, status: 'EMPTY', reasons: ['依赖财务与估值，本次不评分。'] })),
  buyZone: { status: 'WITHHELD', low: null, high: null, stopLoss: null, distanceToZonePct: null,
    riskPct: null, basis: [], notes: ['覆盖率不足 50%，不提供参考区间。'] },
  decision: null,
  priceLookbacks: LOOKBACK_PRICE.map(row => ({ ...row, baseline: null, change: null,
    status: '本地日线同步中，稍后重试' })),
  peLookbacks: LOOKBACK_PE.map(row => ({ ...row, baseline: null, change: null,
    status: '估值历史缺失' })),
  data: {
    quote: { status: 'OK', note: null, asOf: '2026-10-16T15:00:00' },
    valuation: { status: 'FAILED', note: '估值源本轮 429，未重试', asOf: null },
    technical: { status: 'PARTIAL', note: '只有 80 根日线，MA250 不可用', asOf: '2026-10-16' },
    financial: { status: 'EMPTY', note: '后台同步中', asOf: null },
    moneyflow: { status: 'FAILED', note: '资金流源不可达', asOf: null },
    industry: { status: 'EMPTY', note: '行业映射缺失', asOf: null },
    events: { status: 'PARTIAL', note: '只有公告，没有政策条目', asOf: '2026-10-15' },
  },
}

const CORES = { stock: STOCK_CORE, etf: ETF_CORE, index: INDEX_CORE, partial: PARTIAL_CORE }

function section(data, status = 'OK', note = null, asOf = '2026-10-16') {
  return { data, status, note, asOf }
}

function detailFor(kind) {
  const financials = [
    ['2026-06-30', 8.34e10, 12.4, 4.17e10, 15.1, 28.6, 91.2, 50.0, 4.42e10],
    ['2026-03-31', 3.92e10, 11.2, 1.96e10, 14.2, 14.8, 90.8, 50.0, 1.81e10],
    ['2025-12-31', 1.52e11, 10.6, 7.38e10, 13.4, 26.1, 90.4, 48.9, 7.71e10],
    ['2025-09-30', 1.14e11, 9.8, 5.53e10, 12.1, 19.9, 90.1, 48.4, 5.84e10],
    ['2025-06-30', 7.42e10, 9.1, 3.62e10, 11.6, 13.4, 89.8, 48.1, 3.95e10],
    ['2025-03-31', 3.53e10, 8.7, 1.72e10, 10.8, 6.4, 89.4, 47.9, 1.63e10],
    ['2024-12-31', 1.37e11, 8.2, 6.51e10, 10.1, 24.3, 89.1, 47.6, 6.92e10],
    ['2024-09-30', 1.03e11, 7.6, 4.93e10, 9.4, 18.4, 88.9, 47.4, 5.21e10],
  ].map(([reportDate, revenue, revenueYoy, netProfit, netProfitYoy, roe, grossMargin, netMargin, ocf]) => ({
    reportDate, reportType: 'Q', announceDate: reportDate,
    revenue, revenueYoy, netProfit, netProfitYoy, roe, grossMargin, netMargin, ocf,
    totalAssets: 3.1e11, totalEquity: 2.5e11, totalLiabilities: 6.0e10,
    currentAssets: 2.7e11, currentLiabilities: 8.0e10,
    interestExpense: 1.2e8, ebit: 5.6e10, goodwill: 0, accountsReceivable: 3.5e9,
    source: 'eastmoney',
  }))

  const moneyflow = moneyflowPoints(30).map(p => ({ ...p, mainRatio: 3.2, source: 'eastmoney' }))

  const industry = [
    ['食品饮料', 38.6], ['电子', 15.2], ['银行', 12.8], ['电力设备', 9.4], ['医药生物', 7.6],
  ].map(([industryName, weight]) => ({
    industryCode: null, industryName, weight, asOf: '2026-10-16',
  }))

  const events = [
    { category: 'NOTICE', eventType: '分红', title: '2026 年半年度权益分派实施公告',
      summary: '每 10 股派发现金红利 238.50 元（含税）。',
      url: 'https://example.com/notice/1', publishedAt: '2026-10-14', source: '东方财富',
      importance: 'HIGH', polarity: 'POSITIVE' },
    { category: 'NEWS', eventType: '经营', title: '公司三季度动销保持平稳',
      summary: '渠道调研显示核心单品批价稳定。',
      url: 'https://example.com/news/2', publishedAt: '2026-10-12', source: '东方财富',
      importance: 'MEDIUM', polarity: 'NEUTRAL' },
    { category: 'POLICY', eventType: '政策', title: '扩大内需政策持续落地',
      summary: '官方媒体披露促消费举措。',
      url: 'https://example.com/policy/3', publishedAt: '2026-10-09', source: 'Google News',
      importance: 'MEDIUM', polarity: 'POSITIVE' },
  ]

  const scenarios = [
    { key: 'bull', label: '乐观', triggered: false,
      condition: '分数 ≥65，估值 ≥55，技术 ≥55，无硬约束',
      narrative: '估值与趋势同向；若盈利增速延续、估值分位不升破 80，当前状态偏积极。',
      evidenceIds: ['ev:val:pe_pct', 'ev:tech:ma250'],
      invalidation: 'PE 分位 >80，或收盘跌破参考止损。',
      disclaimer: '条件推演，不是预测，不含概率与目标价。' },
    { key: 'base', label: '中性', triggered: true,
      condition: '45 ≤ 分数 <65，或估值/技术方向相反',
      narrative: '估值与趋势未形成共振，等待方向确认。',
      evidenceIds: ['ev:val:pe_pct', 'ev:tech:mom60'],
      invalidation: '放量突破区间上沿或跌破下沿。',
      disclaimer: '条件推演，不是预测，不含概率与目标价。' },
    { key: 'bear', label: '悲观', triggered: false,
      condition: '分数 <45，或技术 <35，或存在硬约束',
      narrative: '趋势、盈利或估值至少一项偏弱；若跌破参考止损，弱势可能延续。',
      evidenceIds: ['ev:tech:flow5'],
      invalidation: '重新站上 MA250 且估值分位回落。',
      disclaimer: '条件推演，不是预测，不含概率与目标价。' },
  ]

  const profile = kind === 'index' || kind === 'etf'
    ? { indexCode: '000300', name: '沪深300', asOf: '2026-10-16', constituentCount: 300,
        top10Weight: 22.4, peTtm: 13.2, dividendYield: 2.6, totalMarketCap: 6.4e13, source: 'csindex' }
    : null

  // 基金档案只在 ETF 上有；个股是指标为空而不是字段缺席（前端据此不渲染卡片）。
  const fundProfile = kind === 'etf'
    ? {
        code: '510300', name: '沪深300ETF',
        fullName: '华泰柏瑞沪深300交易型开放式指数证券投资基金',
        fundType: '指数型-股票', company: '华泰柏瑞基金', custodian: '中国银行',
        manager: '柳军', inceptionDate: '2012-05-04', inceptionScale: '329.69亿份',
        aum: 19468000000, aumAsOf: '2026-06-30', managementFeePct: 0.5, custodyFeePct: 0.1,
        benchmark: '沪深300指数收益率', trackingTarget: '沪深300指数',
        trackingErrorPct: 1.02, peerTrackingErrorPct: 2.21,
        trackingErrorAsOf: '2026-10-19', source: '天天基金（基金档案）',
      }
    : null
  const fundSection = section(fundProfile, fundProfile ? 'OK' : 'NOT_APPLICABLE',
    fundProfile ? null : '个股没有基金档案', fundProfile ? '2026-06-30' : null)

  if (kind === 'partial') {
    return {
      code: '300274', snapshotId: PARTIAL_CORE.snapshotId, snapshotChanged: false,
      financials: section([], 'EMPTY', '后台同步中，通常 10–30 秒', null),
      moneyflow: section([], 'FAILED', '资金流源不可达', null),
      indexProfile: section(null, 'NOT_APPLICABLE', '个股没有指数概况', null),
      fundProfile: fundSection,
      industry: section([], 'EMPTY', '行业映射缺失', null),
      events: section(events.slice(0, 1), 'PARTIAL', '只有公告', '2026-10-15'),
      scenarios: [], notes: ['后台任务 STOCK_DETAIL 尚未完成。'],
    }
  }

  return {
    code: kind === 'etf' ? '510300' : kind === 'index' ? '000300' : '600519',
    snapshotId: kind === 'etf' ? ETF_CORE.snapshotId : kind === 'index' ? INDEX_CORE.snapshotId : STOCK_CORE.snapshotId,
    snapshotChanged: false,
    financials: kind === 'index'
      ? section([], 'NOT_APPLICABLE', '指数不适用财务三表', null)
      : section(financials, 'OK'),
    moneyflow: kind === 'index'
      ? section([], 'NOT_APPLICABLE', '指数没有主力资金口径', null)
      : section(moneyflow, kind === 'etf' ? 'EMPTY' : 'OK', kind === 'etf' ? 'ETF 资金流免费源不稳定' : null),
    indexProfile: section(profile, profile ? 'OK' : 'NOT_APPLICABLE', profile ? null : '个股没有指数概况', profile ? '2026-10-16' : null),
    fundProfile: fundSection,
    industry: section(industry, 'OK'),
    events: section(events, 'OK'),
    scenarios,
    notes: ['新闻标题不参与评分，只影响事件调整。'],
  }
}

function briefFor(kind) {
  const base = kind === 'partial'
    ? { available: false, fallback: false, reason: '数据不足，未调用模型。', sections: [] }
    : {
        available: true, fallback: false, reason: null,
        sections: [
          { heading: '结论', text: '当前数据下，估值与技术未形成强共振，结论为「观察等待」。',
            evidenceIds: ['ev:val:pe_pct', 'ev:tech:ma250'] },
          { heading: '依据', text: 'PE 分位 28%，收盘在 MA250 上方 6.4%；近 5 日主力净流出 1.2 亿。',
            evidenceIds: ['ev:val:pe_pct', 'ev:tech:ma250', 'ev:tech:flow5'] },
          { heading: '风险', text: '同行业样本不足，行业维度未评分。', evidenceIds: [] },
        ],
      }
  return {
    code: kind === 'etf' ? '510300' : kind === 'index' ? '000300' : kind === 'partial' ? '300274' : '600519',
    name: kind === 'etf' ? '沪深300ETF' : kind === 'index' ? '沪深300' : kind === 'partial' ? '阳光电源' : '贵州茅台',
    snapshotId: kind === 'partial' ? PARTIAL_CORE.snapshotId : STOCK_CORE.snapshotId,
    snapshotChanged: false,
    available: base.available, fallback: base.fallback, cached: false,
    title: '规则结论解读',
    sections: base.sections,
    reason: base.reason,
    note: '模型只改写已算好的事实，不参与判定。',
    rulesVersion: 'code-lookup-v2-p5',
    promptVersion: 'code-lookup-v2-prompt-1',
    generatedAt: '2026-10-16T15:20:00',
    disclaimer: DISCLAIMER,
  }
}

// ---------------------------------------------------------------------
// 假后端
// ---------------------------------------------------------------------

let currentCase = 'stock'
let coreHits = 0

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
}

const SEED = `<!doctype html><meta charset="utf-8"><title>seed</title><script>
localStorage.setItem('briefmind_token', 'fake-token-for-screenshots');
localStorage.setItem('briefmind_user', JSON.stringify({
  id: 1, email: 'demo@example.com', displayName: '演示账号',
  role: 'USER', accountType: 'DEMO'
}));
location.replace('/code-lookup');
</script>`

function report(message, data) {
  return { code: 200, message, data }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname

  if (path === '/__seed') {
    res.writeHead(200, { 'Content-Type': MIME['.html'] })
    res.end(SEED)
    return
  }

  if (path.startsWith('/api/')) {
    const json = (body) => {
      res.writeHead(200, { 'Content-Type': MIME['.json'] })
      res.end(JSON.stringify(body))
    }

    if (path === '/api/auth/me') {
      return json(report('ok', {
        id: 1, email: 'demo@example.com', displayName: '演示账号',
        role: 'USER', accountType: 'DEMO',
      }))
    }

    if (path === '/api/code-lookup/v2/core') {
      if (currentCase === 'rate-limited') {
        // 业务码 429 走 HTTP 200：与真后端一致，前端必须看 body.code 而不是只看 HTTP 状态。
        return json({ code: 429, message: '请求过于频繁，请 5 分钟后再试', data: null })
      }
      coreHits += 1
      const core = CORES[currentCase]
      // 部分数据这一张要先让人看到「同步中」，再看到它自己消失。
      const syncPending = currentCase === 'partial' && coreHits === 1
      return json(report('ok', { ...core, syncPending }))
    }

    if (path === '/api/code-lookup/v2/detail') {
      return json(report('ok', detailFor(currentCase)))
    }

    if (path === '/api/code-lookup/v2/series') {
      const kind = url.searchParams.get('kind')
      const partial = currentCase === 'partial'
      if (kind === 'price') {
        return json(report('ok', {
          code: '', kind, range: url.searchParams.get('range') || '1y', interval: '1d',
          price: partial ? pricePoints(80) : pricePoints(),
          valuation: [], moneyflow: [],
          note: partial ? '本地只有最近 80 个交易日的日线。' : null,
        }))
      }
      if (kind === 'valuation') {
        if (partial) {
          return json(report('ok', {
            code: '', kind, range: url.searchParams.get('range') || '5y', interval: '1w',
            price: [], valuation: [], moneyflow: [], note: '估值历史缺失，等待后台同步。',
          }))
        }
        const basePe = currentCase === 'stock' ? 26 : 13.2
        return json(report('ok', {
          code: '', kind, range: url.searchParams.get('range') || '5y', interval: '1w',
          price: [], valuation: valuationPoints(1200, basePe), moneyflow: [],
          note: currentCase === 'stock'
            ? '估值窗口从 2018-01 开始，覆盖不到十年前。'
            : '估值窗口从 2016-01 开始。',
        }))
      }
      return json(report('ok', {
        code: '', kind, range: '1y', interval: '1d',
        price: [], valuation: [],
        moneyflow: partial ? [] : moneyflowPoints(),
        note: partial ? '资金流源不可达。' : null,
      }))
    }

    if (path === '/api/code-lookup/v2/status') {
      const core = CORES[currentCase]
      return json(report('ok', {
        code: core.inputCode, snapshotId: core.snapshotId,
        priceAsOf: '2026-10-16', valuationAsOf: '2026-10-16',
        financialAsOf: null, eventsAsOf: '2026-10-15',
        syncPending: false, retryAfterMs: 0,
        data: core.data, jobs: [], checkedAt: '2026-10-16T15:06:00',
      }))
    }

    if (path === '/api/code-lookup/v2/brief') {
      return json(report('ok', briefFor(currentCase)))
    }

    if (path === '/api/code-lookup/v2/search') {
      return json(report('ok', []))
    }

    /*
     * **没打桩的接口回 500，不回空数据。** 回 `{}` 会让页面静静地显示「—」，
     * 看起来像「这次没取到数」，没人会去查假接口是不是少写了一条。
     */
    return json({
      code: 500,
      message: `假接口没有为 ${path} 打桩——补上它，不要让页面把「没打桩」显示成「没取到数」`,
      data: null,
    })
  }

  const rel = path === '/' ? '/index.html' : path
  const file = join(DIST, rel)
  if (existsSync(file) && extname(file)) {
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' })
    res.end(await readFile(file))
    return
  }
  res.writeHead(200, { 'Content-Type': MIME['.html'] })
  res.end(await readFile(join(DIST, 'index.html')))
})

// ---------------------------------------------------------------------
// CDP
// ---------------------------------------------------------------------

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.consoleErrors = []
    ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data)
      if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        this.consoleErrors.push(msg.params.args
          .map(arg => arg.value ?? arg.description ?? '')
          .join(' '))
      }
      const waiting = this.pending.get(msg.id)
      if (!waiting) return
      this.pending.delete(msg.id)
      msg.error ? waiting.reject(new Error(JSON.stringify(msg.error))) : waiting.resolve(msg.result)
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  async eval(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    })
    if (result.exceptionDetails) {
      throw new Error(`页面里抛错：${result.exceptionDetails.exception?.description
        ?? result.exceptionDetails.text}`)
    }
    return result.result.value
  }

  async waitFor(expression, label, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (await this.eval(expression)) return
      if (Date.now() > deadline) {
        const text = await this.eval('document.body.innerText.slice(0, 1200)')
          .catch(() => '(页面读不出来了)')
        throw new Error(`等「${label}」超时（${timeoutMs}ms）。\n`
          + `表达式：${expression}\n页面上的字：\n${text}`)
      }
      await new Promise((r) => setTimeout(r, 150))
    }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 启动一个**自己的**无头 Edge。
 *
 * 端口用 `0` 让浏览器自己挑，再读 profile 里的 `DevToolsActivePort`：
 * 写死一个端口，如果本机已经有一个开了远程调试的 Edge（用户自己的浏览器），
 * 探测会把它当成「我们刚起的」——于是截图截到的是前台标签页，
 * `Page.captureScreenshot` 在后台标签上还可能一直不返回。
 */
async function launchEdge() {
  const exe = EDGE_CANDIDATES.find((p) => existsSync(p))
  if (!exe) {
    throw new Error(`找不到 Edge。用 EDGE_PATH=<路径> 指一个，试过：${EDGE_CANDIDATES.join(', ')}`)
  }
  const profile = join(tmpdir(), `code-lookup-v2-shots-${Date.now()}`)
  const child = spawn(exe, [
    '--headless=new',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    '--remote-allow-origins=*',
    '--no-first-run',
    '--no-default-browser-check',
    // 本机 Edge 会强制加载扩展（Tampermonkey）并弹同步确认页；它们会把
    // 我们自己的标签页挤到后台，后台页的 rAF / 截图会停住。
    '--disable-extensions',
    '--disable-component-extensions-with-background-pages',
    '--disable-sync',
    '--disable-gpu',
    '--hide-scrollbars',
    'about:blank',
  ], { stdio: 'ignore' })

  const activePort = join(profile, 'DevToolsActivePort')
  for (let i = 0; i < 100; i++) {
    if (existsSync(activePort)) {
      const [port] = (await readFile(activePort, 'utf8')).trim().split(/\r?\n/)
      if (port) {
        const res = await fetch(`http://127.0.0.1:${port}/json/version`).catch(() => null)
        if (res?.ok) return { child, profile, port: Number(port) }
      }
    }
    await sleep(150)
  }
  killTree(child)
  throw new Error('Edge 没有在 15 秒内把调试端口开起来')
}

function killTree(child) {
  if (!child?.pid) return
  if (process.platform === 'win32') {
    spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    child.kill()
  }
}

async function openTarget(port) {
  const res = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })
  if (!res.ok) throw new Error(`开新标签失败：HTTP ${res.status}`)
  const target = await res.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('连不上标签的调试 WebSocket')), { once: true })
  })
  return { cdp: new Cdp(ws), targetId: target.id }
}

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label}超时（${ms}ms）`)), ms)),
  ])
}

async function shoot(cdp, name, width, height, mobile) {
  await cdp.send('Page.bringToFront').catch(() => {})
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile,
    screenWidth: width, screenHeight: height,
  })
  await sleep(400)
  // 图表在容器尺寸变化后会重排，等两帧再拍。**必须带 setTimeout 兜底**：
  // 页面一旦被挤到后台，rAF 会被浏览器暂停，awaitPromise 就永远不返回。
  await cdp.eval(`Promise.race([
    new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))),
    new Promise(r => setTimeout(r, 500)),
  ])`)
  // 明确给 clip：只靠 captureBeyondViewport，移动端模拟会按设备像素放大，
  // 拍出来的图宽度不是 CSS 宽度，读起来像被缩放过。
  const pageHeight = await cdp.eval('document.documentElement.scrollHeight')
  const shot = await withTimeout(cdp.send('Page.captureScreenshot', {
    format: 'png',
    captureBeyondViewport: true,
    clip: {
      x: 0,
      y: 0,
      width,
      height: Math.max(height, Math.min(pageHeight, 12000)),
      scale: 1,
    },
  }), 30000, `截图 ${name}`)
  const file = join(OUT, `${name}.png`)
  await writeFile(file, Buffer.from(shot.data, 'base64'))
  return file
}

const SET_INPUT = (selector, value) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return false;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`

const CLICK_BY_TEXT = (text) => `(() => {
  const button = [...document.querySelectorAll('button')]
    .find(b => b.textContent.includes(${JSON.stringify(text)}) && !b.disabled);
  if (!button) return false;
  button.click();
  return true;
})()`

/**
 * 标签页必须按 `.cl-tabs` 限定再点。
 * 六维评分块本身也是 `<button>`，且「估值」正好是其中一块的标题；
 * 用全页 `button` 找文字会先点开证据抽屉，而不是切标签页。
 */
const CLICK_TAB = (text) => `(() => {
  const button = [...document.querySelectorAll('.cl-tabs button')]
    .find(b => b.textContent.trim() === ${JSON.stringify(text)});
  if (!button) return false;
  button.click();
  return true;
})()`

/** 页面内的横向溢出检查：只看 `.cl-page` 自己，不把浏览器/侧栏的问题算进来。 */
const NO_PAGE_OVERFLOW = `(() => {
  const page = document.querySelector('.cl-page');
  if (!page) return '找不到 .cl-page';
  if (page.scrollWidth <= page.clientWidth + 1) return true;
  const right = page.getBoundingClientRect().right + 1;
  const offenders = [...page.querySelectorAll('*')]
    .filter(el => !el.closest('.cl-table-wrap') && el.getBoundingClientRect().right > right)
    .slice(0, 6)
    .map(el => el.tagName.toLowerCase() + '.' + [...el.classList].join('.'));
  return '页面横向溢出：scrollWidth=' + page.scrollWidth
    + ' clientWidth=' + page.clientWidth
    + (offenders.length ? '；越界元素：' + offenders.join(', ') : '');
})()`

/**
 * App 外壳（侧栏 240px + 桌面内边距）没有做移动端适配，直接在 390px 视口下截图
 * 会把页面挤成一条。这里只**在截图进程里**临时收起侧栏，让 `.cl-page` 拿到
 * 手机宽度的内容区；页面自身的响应式规则仍然是真实生效的那一套。
 */
const MOBILE_SHELL = `(() => {
  if (document.getElementById('__mobile_shell')) return true;
  const style = document.createElement('style');
  style.id = '__mobile_shell';
  style.textContent = '@media (max-width: 760px) {'
    + '.sidebar { display: none !important; }'
    + '.main-wrapper { margin-left: 0 !important; }'
    + '.main-content { padding: 12px !important; }'
    + '.header-bar { padding: 0 12px !important; }'
    + '}';
  document.head.appendChild(style);
  return true;
})()`

// ---------------------------------------------------------------------
// 场景
// ---------------------------------------------------------------------

async function assertNoOverflow(cdp, width, height, mobile, label) {
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile,
  })
  await sleep(350)
  const result = await cdp.eval(NO_PAGE_OVERFLOW)
  if (result !== true) throw new Error(`${label}：${result}`)
}

const CASES = [
  { name: 'stock', code: '600519', label: '个股' },
  { name: 'etf', code: '510300', label: 'ETF' },
  { name: 'index', code: '000300', label: '指数' },
  { name: 'partial', code: '300274', label: '数据不足' },
  { name: 'rate-limited', code: '600519', label: '429 限流' },
]

async function runCase(port, item) {
  currentCase = item.name
  coreHits = 0

  const { cdp, targetId } = await openTarget(port)
  try {
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')
    await cdp.send('Page.bringToFront').catch(() => {})

    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${PORT}/__seed` })
    await cdp.waitFor('!!document.querySelector(".cl-search-row input")', '查询框')

    if (!await cdp.eval(SET_INPUT('.cl-search-row input', item.code))) {
      throw new Error('没找到代码输入框')
    }
    if (!await cdp.eval(CLICK_BY_TEXT('查询'))) throw new Error('没找到「查询」按钮')

    if (item.name === 'rate-limited') {
      await cdp.waitFor('!!document.querySelector(".cl-failure-rate-limited")', '限流提示')
      const text = await cdp.eval('document.body.innerText')
      if (!text.includes('不会自动重试')) throw new Error('限流块没有说明不会自动重试')
      const files = [
        await shoot(cdp, `code-lookup-v2-${item.name}-desktop`, 1440, 1200, false),
      ]
      if (cdp.consoleErrors.length) throw new Error(`控制台报错：\n${cdp.consoleErrors.join('\n')}`)
      console.log(`  ✓ ${item.label}（${files.length} 张）`)
      return files
    }

    await cdp.waitFor('!!document.querySelector(".cl-hero")', '结论主卡')

    // 六维评分必须刚好 6 块。
    const tiles = await cdp.eval('document.querySelectorAll(".cl-tile").length')
    if (tiles !== 6) throw new Error(`六维评分块应有 6 个，实际 ${tiles} 个`)

    if (item.name === 'partial') {
      await cdp.waitFor('!!document.querySelector(".cl-withheld")', '数据不足说明')
      const text = await cdp.eval('document.querySelector(".cl-withheld").innerText')
      if (!text.includes('数据不足')) throw new Error('数据不足时没有写出原因')
    } else {
      const score = await cdp.eval('document.querySelector(".cl-gauge-inner strong")?.innerText || ""')
      if (!/^\d+$/.test(score)) throw new Error(`结论卡上没有分数，读到「${score}」`)
    }

    // 概览的价格图必须真的画出来。
    await cdp.waitFor('!!document.querySelector(".cl-chart canvas")', '价格图 canvas')
    const canvasBlank = await cdp.eval(`(() => {
      const canvas = document.querySelector('.cl-chart canvas');
      const ctx = canvas.getContext('2d');
      const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
      for (let i = 3; i < data.length; i += 4) if (data[i] !== 0) return false;
      return true;
    })()`)
    if (canvasBlank) throw new Error('价格图 canvas 是空白的')

    // 基金档案：ETF 概览必须有这张卡，字段带单位与截止日；个股不该出现。
    if (item.name === 'etf') {
      await cdp.waitFor('!!document.querySelector(".cl-fund-profile")', '基金档案卡')
      const profileText = await cdp.eval('document.querySelector(".cl-fund-profile").innerText')
      for (const expected of ['基金档案', '194.68 亿', '1.02%', '2.21%', '2026-06-30']) {
        if (!profileText.includes(expected)) throw new Error(`基金档案卡缺少「${expected}」`)
      }
      await shoot(cdp, 'code-lookup-v2-etf-overview', 1440, 2700, false)
      // 窄屏下这张卡要塌成一列，不能横向溢出。
      await cdp.eval(MOBILE_SHELL)
      await assertNoOverflow(cdp, 390, 900, false, '基金档案 390')
      await shoot(cdp, 'code-lookup-v2-etf-overview-mobile', 390, 3200, false)
    } else if (await cdp.eval('!!document.querySelector(".cl-fund-profile")')) {
      throw new Error(`${item.label}不应该渲染基金档案卡`)
    }

    // 切到估值页：序列按需加载，图要出来。
    if (!await cdp.eval(CLICK_TAB('估值'))) throw new Error('没找到「估值」标签页')
    await cdp.waitFor('document.querySelector(".cl-tabs button.is-active")?.textContent.includes("估值")',
      '估值标签页激活')
    await cdp.waitFor('!!document.querySelector(".cl-overview-grid, .cl-chart canvas") '
      + '|| !!document.querySelector(".cl-empty")', '估值内容')

    await assertNoOverflow(cdp, 1440, 1000, false, '桌面 1440')

    if (cdp.consoleErrors.length) {
      throw new Error(`控制台报错：\n${cdp.consoleErrors.join('\n')}`)
    }

    const desktop = await shoot(cdp, `code-lookup-v2-${item.name}-desktop`, 1440, 3200, false)
    await cdp.eval(MOBILE_SHELL)
    // `mobile: true` 会让 Edge 先按 980px 布局再整体缩放，媒体查询不会按 390 触发。
    // 这里要验的是页面自己的响应式，所以用 mobile: false + 390 视口。
    await assertNoOverflow(cdp, 390, 900, false, '移动端 390')
    if (process.env.DEBUG_LAYOUT) {
      console.log(await cdp.eval(`JSON.stringify({
        innerWidth: window.innerWidth,
        docClient: document.documentElement.clientWidth,
        page: document.querySelector('.cl-page').clientWidth,
        grid: getComputedStyle(document.querySelector('.cl-tiles')).gridTemplateColumns,
        tiles: [...document.querySelectorAll('.cl-tile')].map(t => {
          const r = t.getBoundingClientRect();
          return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)];
        }),
        chain: (() => {
          let el = document.querySelector('.cl-page');
          const out = [];
          while (el) { out.push([el.tagName, String(el.className), el.clientWidth]); el = el.parentElement; }
          return out;
        })(),
        wide: [...document.querySelectorAll('.cl-page *')]
          .filter(el => el.getBoundingClientRect().width > 390)
          .slice(0, 12)
          .map(el => [el.tagName, String(el.className), Math.round(el.getBoundingClientRect().width),
            getComputedStyle(el).minWidth]),
        probes: (() => {
          const page = document.querySelector('.cl-page');
          const hide = (sel) => {
            const els = [...document.querySelectorAll(sel)];
            const prev = els.map(e => e.style.display);
            els.forEach(e => { e.style.display = 'none'; });
            const width = page.clientWidth;
            els.forEach((e, i) => { e.style.display = prev[i]; });
            return width;
          };
          return {
            chart: hide('.cl-chart'),
            tables: hide('.cl-table-wrap'),
            tabs: hide('.cl-tabs'),
            search: hide('.cl-search-row'),
            banner: hide('.cl-banner'),
          };
        })(),
      })`))
    }
    const mobile = await shoot(cdp, `code-lookup-v2-${item.name}-mobile`, 390, 2400, false)
    const files = [desktop, mobile]
    console.log(`  ✓ ${item.label}（${files.length} 张）`)
    return files
  } finally {
    await fetch(`http://127.0.0.1:${port}/json/close/${targetId}`).catch(() => {})
  }
}

async function main() {
  if (!existsSync(join(DIST, 'index.html'))) {
    throw new Error(`找不到 ${DIST}/index.html。先跑：cd frontend && npm run build`)
  }
  await mkdir(OUT, { recursive: true })

  await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
  const { child, port: browserPort } = await launchEdge()

  try {
    const selected = ONLY ? CASES.filter(item => item.name === ONLY) : CASES
    if (selected.length === 0) {
      throw new Error(`未知的 --case ${ONLY}，可选：${CASES.map(item => item.name).join(' | ')}`)
    }
    for (const item of selected) await runCase(browserPort, item)
    console.log(`\n截图在 ${OUT}`)
  } finally {
    killTree(child)
    server.close()
  }
}

main().catch((error) => {
  console.error(`\n✗ ${error.message}`)
  process.exitCode = 1
})
