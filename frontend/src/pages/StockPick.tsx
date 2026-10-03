import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import api from '../utils/api'
import './MarketWatch.css'
import './StockPick.css'

/** 后端 record 的字段名与这里一一对应，改一处要同时改两边。 */
interface StockRow {
  code: string
  name: string | null
  industry: string | null
  industryUnknown?: boolean
  price: number | null
  pctChange: number | null
  amount: number | null
  turnoverRate: number | null
  totalMarketCap: number | null
  floatMarketCap: number | null
  pb: number | null
  peTtm: number | null
  roe: number | null
  revenueGrowth: number | null
  profitGrowth: number | null
  grossMargin: number | null
  debtRatio: number | null
  dividendYield: number | null
  change60d: number | null
  ytdChange: number | null
}

interface DimensionScore {
  key: string
  label: string
  weight: number
  score: number
}

interface PricePosition {
  pricePercentile: number | null
  drawdownFromHigh: number | null
  maxDrawdownInYear: number | null
  annualizedVolatility: number | null
  vsMa20: number | null
  vsMa60: number | null
  vsMa250: number | null
  barCount: number
  lastTradeDate: string | null
  degradations: string[]
  available: boolean
}

interface Selected {
  row: StockRow
  bucket: 'STEADY' | 'GROWTH'
  score: number
  dimensions: DimensionScore[]
  factorPercentiles: Record<string, number> | null
  reasons: string[]
  risks: string[]
  degradations: string[]
  position: PricePosition | null
}

/**
 * 一张指数卡。**单位是指数**，ETF 只是取价格位置与规模的手段。
 *
 * 每条可空数值都配了一个状态串，后端保证 `xStatus != null ⟺ 对应数值 == null`，
 * 于是渲染时不需要任何「猜」：值为 null 就直接印状态串。前端**不再**有 `|| '—'` 这种兜底。
 */
interface IndexFundItem {
  indexCode: string
  indexName: string
  category: string
  etfCode: string | null
  etfName: string | null
  tracking: string
  price: number | null
  pctChange: number | null
  amountYi: number | null
  scaleYi: number | null
  pricePercentile: number | null
  drawdownFromHigh: number | null
  maxDrawdownInYear: number | null
  annualizedVolatility: number | null
  vsMa250: number | null
  peTtm: number | null
  pePercentile: number | null
  priceStatus: string | null
  pctChangeStatus: string | null
  amountStatus: string | null
  scaleStatus: string | null
  positionStatus: string | null
  peStatus: string | null
  percentileStatus: string | null
  quoteSource: string | null
  positionSource: string | null
  valuationSource: string | null
  percentileMethod: string | null
  valuationTradeDate: string | null
  barCount: number | null
  lastTradeDate: string | null
  notes: string[]
  /**
   * 本次条件下的判定，取值见后端 `ScreeningRules.IndexQualification`。
   *
   * 四个取值里 `no_conditions`（用户一条指数条件都没填）**不渲染徽章**：
   * 那种情况下每张卡都挂一个「未设条件」只是噪音，整段用一句标题说明更清楚。
   */
  qualification: 'qualified' | 'blocked' | 'unconfirmed' | 'no_conditions'
  /** 被哪条挡下 / 哪条判不了。`blocked`、`unconfirmed` 时非空。 */
  qualificationReasons: string[]
}

/** 回显当时生效的条件（解析默认值之后）。后端一直有这一块，前端此前漏声明了。 */
interface ScreenerParamsView {
  mode: string
  bucket: string
  perBucket: number
  peMax: number | null
  pePercentileMax: number | null
  marketCapMinYi: number | null
  pbMax: number | null
  dividendYieldMin: number | null
  roeMin: number | null
  debtRatioMax: number | null
  excludedIndustries: string[]
  revenueGrowthMin: number | null
  profitGrowthMin: number | null
}

/**
 * 筛选历史一行。
 *
 * 字段与后端 `ScreenerHistoryDTO.Item` 一一对应：**只有摘要列，没有整页快照**——
 * 快照几十 KB，列表页读它纯属浪费。要点开某一条时才去取详情。
 */
interface HistoryRef {
  code: string
  name: string | null
  qualified: boolean
}

interface HistoryItem {
  id: number
  scannedAt: string
  scannedByEmail: string | null
  mode: string
  bucket: string
  perBucket: number
  scanned: number
  afterVetoes: number
  steadyPool: number
  growthPool: number
  shortlistFetched: number
  indexCount: number
  qualifiedIndexCount: number
  indices: HistoryRef[]
  steadyStocks: HistoryRef[]
  growthStocks: HistoryRef[]
  appliedParams: ScreenerParamsView | null
}

interface HistoryPage {
  records: HistoryItem[]
  total: number
  pages: number
  current: number
  size: number
}

/** 类别顺序。**顺序在这里写死**，不按数据里出现的先后——那会随池子内容变来变去。 */
const CATEGORY_ORDER = ['broad', 'strategy', 'sector', 'theme', 'overseas', 'other']

const CATEGORY_LABEL: Record<string, string> = {
  broad: '宽基',
  strategy: '红利与策略',
  sector: '行业',
  theme: '主题',
  overseas: '海外',
  other: '其它',
}

const categoryLabel = (c: string) => CATEGORY_LABEL[c] ?? c

/** 每个类别首次渲染多少张。展开是纯本地 state，不进 URL、不发请求。 */
const DEFAULT_PER_CATEGORY = 12

interface VetoCount {
  rule: string
  label: string
  count: number
}

interface Summary {
  scanned: number
  afterVetoes: number
  steadyPool: number
  growthPool: number
  shortlistFetched: number
  vetoCounts: VetoCount[]
  degradations: string[]
  notes: string[]
}

interface ScanResult {
  dataTime: string
  priceAsOf: string
  appliedParams: ScreenerParamsView | null
  summary: Summary
  indexFunds: IndexFundItem[]
  steadyStocks: Selected[]
  growthStocks: Selected[]
  disclaimer: string
}

interface Form {
  mode: string
  bucket: string
  perBucket: string
  peMax: string
  /** PE 分位上限 %。**只作用于指数基金**：个股在东财口径下没有自身的 PE 历史分位。 */
  pePercentileMax: string
  marketCapMinYi: string
  pbMax: string
  dividendYieldMin: string
  roeMin: string
  debtRatioMax: string
  excludedIndustries: string[]
  revenueGrowthMin: string
  profitGrowthMin: string
}

const DEFAULT_FORM: Form = {
  mode: 'index_first',
  bucket: 'both',
  perBucket: '3',
  peMax: '',
  pePercentileMax: '',
  marketCapMinYi: '',
  pbMax: '',
  dividendYieldMin: '',
  roeMin: '',
  debtRatioMax: '',
  excludedIndustries: [],
  revenueGrowthMin: '',
  profitGrowthMin: '',
}

/** 人工核实清单：这些事项没有数据源，只能由人去看公告。 */
const MANUAL_CHECKS = [
  '公告里是否有大额商誉、减值计提',
  '大股东质押比例、近期减持与限售解禁安排',
  '是否有诉讼、处罚、问询函，审计意见是否为非标',
  '关联交易与资金占用情况',
  '实控人是否稳定，是否存在借壳或重组历史',
]

function num(v: number | null | undefined, digits = 2) {
  if (v === null || v === undefined) return '—'
  return Number(v).toFixed(digits)
}

function signed(v: number | null | undefined, digits = 2) {
  if (v === null || v === undefined) return '—'
  const n = Number(v)
  return `${n > 0 ? '+' : ''}${n.toFixed(digits)}%`
}

function yi(v: number | null | undefined) {
  if (v === null || v === undefined) return '—'
  return `${Number(v).toFixed(2)} 亿`
}

/**
 * 本次判定的排序权重：**合格在前，其次判不了，最后是被条件挡下的**。
 *
 * 不合格的那些**不隐藏**（隐藏就回到了「池子里只有这几个」的错觉）——放在后面，
 * 并挂着被谁挡下的原因。判不了的排在被挡下之前：它既不是坏消息也不是好消息。
 */
const QUAL_RANK: Record<IndexFundItem['qualification'], number> = {
  qualified: 0,
  unconfirmed: 1,
  blocked: 2,
  no_conditions: 0, // 一条条件都没填时没有判定，不该因此被排到后面
}

/**
 * 同一估值来源内部的排序：判定 → 分位升序 → 规模降序 → 指数代码升序。
 *
 * 缺值的排在最后，**不当成最小或最大**：没有分位不等于分位为 0，
 * 把它排到最前面会让人读成「最便宜的那几个，只是没数而已」。
 * 末位的 `indexCode` 只是为了同样两条数据每次渲染顺序一致——顺序会跳的榜单没法核对。
 */
function compareIndexFund(a: IndexFundItem, b: IndexFundItem) {
  const ra = QUAL_RANK[a.qualification] ?? 0
  const rb = QUAL_RANK[b.qualification] ?? 0
  if (ra !== rb) return ra - rb
  if (a.pePercentile !== b.pePercentile) {
    if (a.pePercentile === null) return 1
    if (b.pePercentile === null) return -1
    return a.pePercentile - b.pePercentile
  }
  if (a.scaleYi !== b.scaleYi) {
    if (a.scaleYi === null) return 1
    if (b.scaleYi === null) return -1
    return b.scaleYi - a.scaleYi
  }
  return a.indexCode.localeCompare(b.indexCode)
}

/**
 * 按估值来源分子组，各组内部排序。
 *
 * 组顺序按来源名固定（否则每次渲染顺序都可能变，没法核对）。**「未接入估值来源」那一组排最后**：
 * 它没有分位可比，摆在最前面会被读成「这批指数里最靠前的几个」，而它恰恰是信息最少的一组。
 */
function subgroupsOf(items: IndexFundItem[]) {
  const sources = [...new Set(items.map(f => f.valuationSource))]
    .sort((a, b) => {
      if (a === b) return 0
      if (a === null) return 1      // 没接来源的排最后
      if (b === null) return -1
      return a.localeCompare(b)
    })
  return sources.map(source => ({
    source,
    items: items.filter(f => f.valuationSource === source).sort(compareIndexFund),
  }))
}

/** 本次判定的统计。页面上「共 N 个指数」必须拆成入选/挡下/未确认，否则扩容看不出来。 */
function qualCounts(items: IndexFundItem[]) {
  const by = (q: IndexFundItem['qualification']) => items.filter(f => f.qualification === q).length
  const counts = {
    total: items.length,
    qualified: by('qualified'),
    blocked: by('blocked'),
    unconfirmed: by('unconfirmed'),
    noConditions: by('no_conditions'),
  }
  // 一条指数条件都没填时后端不做判定，四类计数里只会有 noConditions —— 页面上要说这件事
  return { ...counts, judged: counts.qualified + counts.blocked + counts.unconfirmed > 0 }
}

/** 空字符串 = 用户没填 = 走后端默认，不能当成 0 发过去。 */
function buildPayload(form: Form) {
  const payload: Record<string, unknown> = {
    mode: form.mode,
    bucket: form.bucket,
    perBucket: Number(form.perBucket),
  }
  const putNumber = (key: string, raw: string) => {
    if (raw.trim() === '') return
    const n = Number(raw)
    if (Number.isFinite(n)) payload[key] = n
  }
  putNumber('peMax', form.peMax)
  putNumber('pePercentileMax', form.pePercentileMax)
  putNumber('marketCapMinYi', form.marketCapMinYi)
  putNumber('pbMax', form.pbMax)
  putNumber('dividendYieldMin', form.dividendYieldMin)
  putNumber('roeMin', form.roeMin)
  putNumber('debtRatioMax', form.debtRatioMax)
  putNumber('revenueGrowthMin', form.revenueGrowthMin)
  putNumber('profitGrowthMin', form.profitGrowthMin)
  if (form.excludedIndustries.length > 0) payload.excludedIndustries = form.excludedIndustries
  return payload
}

/**
 * 回显「本次真正生效的条件」。
 *
 * 留空的三格要写出**它到底变成了什么**：`peMax` / `marketCapMinYi` / `pbMax` /
 * `debtRatioMax` 留空是「按档位默认」，而 `pePercentileMax` 留空是「不限」——
 * 指数没有档位。都写成「未填」等于把两个不同的含义糊成一件事。
 */
function paramRows(p: ScreenerParamsView): Array<[string, string]> {
  const rows: Array<[string, string]> = [
    ['类别', p.mode === 'index_only' ? '只看指数基金' : p.mode === 'stock_only' ? '只看个股' : '指数基金优先'],
    ['档位', p.bucket === 'steady' ? '只看稳健低估' : p.bucket === 'growth' ? '只看低估成长' : '两档都出'],
    ['每档只数', String(p.perBucket)],
    ['PE(TTM) 上限', p.peMax === null
      ? '按档位默认（稳健 30 / 成长 45）；指数不限'
      : num(p.peMax)],
    ['PE 分位上限', p.pePercentileMax === null ? '不限' : `${num(p.pePercentileMax)}%（只作用于指数）`],
    ['市值下限', p.marketCapMinYi === null
      ? '按档位默认（稳健 200 亿 / 成长 50 亿）；指数不限'
      : `${num(p.marketCapMinYi)} 亿`],
  ]
  if (p.pbMax !== null) rows.push(['PB 上限', num(p.pbMax)])
  if (p.dividendYieldMin !== null) rows.push(['股息率下限', `${num(p.dividendYieldMin)}%`])
  if (p.roeMin !== null) rows.push(['ROE 下限', `${num(p.roeMin)}%`])
  if (p.debtRatioMax !== null) rows.push(['资产负债率上限', `${num(p.debtRatioMax)}%`])
  else rows.push(['资产负债率上限', '按档位默认（稳健 65% / 成长 75%）'])
  if (p.revenueGrowthMin !== null) rows.push(['营收同比下限', `${num(p.revenueGrowthMin)}%`])
  if (p.profitGrowthMin !== null) rows.push(['净利同比下限', `${num(p.profitGrowthMin)}%`])
  rows.push(['排除行业', p.excludedIndustries.length > 0 ? p.excludedIndustries.join('、') : '不排除'])
  return rows
}

/**
 * 条件面板 ← 当时的生效条件。
 *
 * 数字回填成字符串，null 回填成空串（空串 = 没填 = 走后端默认）。
 * **不要**把 null 写成 '0'：那会变成「PE 上限 0」，一个永远筛不出东西的条件。
 */
function formFromParams(p: ScreenerParamsView): Form {
  const s = (v: number | null | undefined) => (v === null || v === undefined ? '' : String(v))
  return {
    mode: p.mode,
    bucket: p.bucket,
    perBucket: String(p.perBucket),
    peMax: s(p.peMax),
    pePercentileMax: s(p.pePercentileMax),
    marketCapMinYi: s(p.marketCapMinYi),
    pbMax: s(p.pbMax),
    dividendYieldMin: s(p.dividendYieldMin),
    roeMin: s(p.roeMin),
    debtRatioMax: s(p.debtRatioMax),
    excludedIndustries: p.excludedIndustries ?? [],
    revenueGrowthMin: s(p.revenueGrowthMin),
    profitGrowthMin: s(p.profitGrowthMin),
  }
}

const MODE_LABEL: Record<string, string> = {
  index_first: '指数基金优先',
  index_only: '只看指数基金',
  stock_only: '只看个股',
}
const BUCKET_LABEL: Record<string, string> = {
  both: '两档都出',
  steady: '只看稳健低估',
  growth: '只看低估成长',
}

/** 一行入选标的：`名称(代码)`，超过 `limit` 个就用「等」收尾，不换行堆成一片。 */
function refLine(refs: HistoryRef[], limit = 8): string {
  if (refs.length === 0) return '无'
  const shown = refs.slice(0, limit).map(r => `${r.name || r.code}(${r.code})`)
  return refs.length > limit ? `${shown.join('、')} 等 ${refs.length} 个` : shown.join('、')
}

function FactorBars({ item }: { item: Selected }) {
  return (
    <div className="stockpick-factors">
      {item.dimensions.map(d => (
        <div key={d.key} className="stockpick-factor">
          <span className="stockpick-factor-label">{d.label}</span>
          <span className="stockpick-factor-track">
            <span className="stockpick-factor-fill" style={{ width: `${Math.max(0, Math.min(100, d.score))}%` }} />
          </span>
          <span className="stockpick-factor-value">
            {num(d.score, 1)} <em>·权重 {(d.weight * 100).toFixed(0)}%</em>
          </span>
        </div>
      ))}
    </div>
  )
}

/**
 * 一个小标题 + 列表。`tone` 决定标题的颜色：`reason` 绿（通过类）、`risk` 黄（风险/降级）、
 * `info` 蓝（「判不了」这类既非通过也非失败的信息，用绿色会被读成入选理由）。
 */
function List({ title, items, tone }: { title: string; items: string[]; tone: 'reason' | 'risk' | 'info' }) {
  if (items.length === 0) return null
  return (
    <div className={`stockpick-list stockpick-list-${tone}`}>
      <h5>{title}</h5>
      <ul>
        {items.map((t, i) => <li key={`${i}-${t}`}>{t}</li>)}
      </ul>
    </div>
  )
}

function StockCandidate({ item, rank }: { item: Selected; rank: number }) {
  const r = item.row
  const pos = item.position
  const risky = item.risks.length > 0

  return (
    <article className="stockpick-card">
      <header className="stockpick-card-head">
        <div>
          <h4>
            <span className="stockpick-rank">{rank}</span>
            {r.name || '—'}
            <span className="stockpick-code">{r.code}</span>
          </h4>
          <div className="stockpick-meta">
            <span>{r.industry || '行业不可确认'}</span>
            <span className={`stockpick-badge risk-${item.bucket === 'STEADY' ? 'low' : 'mid'}`}>
              风险等级 {item.bucket === 'STEADY' ? '中低' : '中'}
            </span>
            <span className="stockpick-score">综合分 {num(item.score, 1)}</span>
          </div>
        </div>
      </header>

      <div className="stockpick-numbers">
        <div><label>最新价</label><span>{num(r.price)}</span></div>
        <div><label>当日</label><span>{signed(r.pctChange)}</span></div>
        <div><label>PE(TTM)</label><span>{num(r.peTtm)}</span></div>
        <div><label>市净率</label><span>{num(r.pb)}</span></div>
        <div><label>股息率</label><span>{r.dividendYield === null ? '—' : `${num(r.dividendYield)}%`}</span></div>
        <div><label>ROE(加权)</label><span>{r.roe === null ? '—' : `${num(r.roe)}%`}</span></div>
        <div><label>资产负债率</label><span>{r.debtRatio === null ? '—' : `${num(r.debtRatio)}%`}</span></div>
        <div><label>毛利率</label><span>{r.grossMargin === null ? '—' : `${num(r.grossMargin)}%`}</span></div>
        <div>
          <label>总市值</label>
          <span>{r.totalMarketCap === null && r.floatMarketCap === null ? '—' : yi((r.totalMarketCap ?? r.floatMarketCap ?? 0) / 1e8)}</span>
        </div>
        <div><label>成交额</label><span>{r.amount === null ? '—' : yi(r.amount / 1e8)}</span></div>
      </div>

      <div className="stockpick-position">
        <h5>价格位置（前复权，非估值口径）</h5>
        {pos && pos.available ? (
          <div className="stockpick-numbers">
            <div><label>一年价格分位</label><span>{pos.pricePercentile === null ? '—' : `${num(pos.pricePercentile, 1)}%`}</span></div>
            <div><label>距一年最高点</label><span>{pos.drawdownFromHigh === null ? '—' : `-${num(pos.drawdownFromHigh, 1)}%`}</span></div>
            <div><label>一年最大回撤</label><span>{pos.maxDrawdownInYear === null ? '—' : `${num(pos.maxDrawdownInYear, 1)}%`}</span></div>
            <div><label>年化波动</label><span>{pos.annualizedVolatility === null ? '—' : `${num(pos.annualizedVolatility, 1)}%`}</span></div>
            <div><label>对 MA20</label><span>{signed(pos.vsMa20)}</span></div>
            <div><label>对 MA250</label><span>{pos.vsMa250 === null ? '未确认' : signed(pos.vsMa250)}</span></div>
            <div><label>日线截止</label><span>{pos.lastTradeDate || '—'}</span></div>
          </div>
        ) : (
          <p className="stockpick-gap">未取到日线，价格位置维未计入本次得分。</p>
        )}
        <p className="stockpick-note">
          个股在这个筛选口径下只有价格位置，没有 PE 历史分位：筛选器整池遍历，走的还是
          指数那一个估值源，不逐只拉个股的 PE 长历史。想看单只个股的 PE 分位，
          用「代码查询」——那里会按需取个股的 PE 历史序列。
        </p>
      </div>

      <FactorBars item={item} />

      <List title="入选理由（全部来自上方数字）" items={item.reasons} tone="reason" />
      {risky && <List title="风险清单" items={item.risks} tone="risk" />}
      {item.degradations.length > 0 && <List title="数据降级" items={item.degradations} tone="risk" />}
    </article>
  )
}

/**
 * 指数卡里的一格。
 *
 * **有值渲染值，无值渲染那一格自己的原因串**——指数卡里不再出现破折号。
 * 后端保证 `status` 在 `value` 为 null 时非空；万一没有，也得把「没有原因」这件事
 * 印出来，而不是留一格空白：空白的读法太多了，读者会以为是我们没取到、或者以为那是 0。
 *
 * 缺值的格子跨两列：原因串是一句话，而一列的宽度只有 104px，硬挤会变成竖着一列字。
 */
function Cell({ label, value, status, format }: {
  label: string
  value: number | null
  status: string | null
  format: (v: number) => string
}) {
  if (value === null || value === undefined) {
    return (
      <div className="stockpick-cell-wide">
        <label>{label}</label>
        <span className="stockpick-missing">{status || '未确认（后端未给出原因，请反馈）'}</span>
      </div>
    )
  }
  return (
    <div>
      <label>{label}</label>
      <span>{format(value)}</span>
    </div>
  )
}

/**
 * 判定 → 徽章。〔`no_conditions` 返回 null：一条指数条件都没填时不做判定，
 * 每张卡挂个「未设条件」只是噪音，整段用一句标题说明就够了。〕
 */
function qualBadge(q: IndexFundItem['qualification']): { text: string; cls: string } | null {
  switch (q) {
    case 'qualified':
      return { text: '本次入选', cls: 'qual-ok' }
    case 'blocked':
      return { text: '被条件挡下', cls: 'qual-blocked' }
    case 'unconfirmed':
      return { text: '数据不足 · 未确认', cls: 'qual-pending' }
    default:
      return null
  }
}

function IndexFundCard({ item }: { item: IndexFundItem }) {
  const pct = (v: number) => `${num(v, 1)}%`
  const negativePct = (v: number) => `-${num(v, 1)}%`
  const qual = qualBadge(item.qualification)

  return (
    <article className="stockpick-card stockpick-index-card">
      <header className="stockpick-card-head">
        <div>
          <h4>
            {item.indexName}
            <span className="stockpick-code">{item.indexCode}</span>
          </h4>
          <div className="stockpick-meta">
            <span className="stockpick-badge stockpick-cat">{categoryLabel(item.category)}</span>
            {qual && <span className={`stockpick-badge ${qual.cls}`}>{qual.text}</span>}
            {item.etfCode ? (
              <span>
                {item.etfName}<span className="stockpick-code">{item.etfCode}</span>
                {' · 跟踪 '}{item.tracking}
              </span>
            ) : (
              <span>池中暂无对应的可交易 ETF，本卡只有指数本身的估值</span>
            )}
          </div>
        </div>
      </header>

      <div className="stockpick-numbers">
        <Cell label="最新价" value={item.price} status={item.priceStatus} format={v => num(v, 3)} />
        <Cell label="当日" value={item.pctChange} status={item.pctChangeStatus} format={signed} />
        <Cell label="成交额" value={item.amountYi} status={item.amountStatus} format={yi} />
        <Cell label="规模" value={item.scaleYi} status={item.scaleStatus} format={yi} />
        <Cell label="一年价格分位" value={item.pricePercentile} status={item.positionStatus} format={pct} />
        <Cell label="距一年最高点" value={item.drawdownFromHigh} status={item.positionStatus} format={negativePct} />
        <Cell label="一年最大回撤" value={item.maxDrawdownInYear} status={item.positionStatus} format={pct} />
        <Cell label="年化波动" value={item.annualizedVolatility} status={item.positionStatus} format={pct} />
        <Cell label="对 MA250" value={item.vsMa250} status={item.positionStatus} format={signed} />
        <Cell label="PE(TTM)" value={item.peTtm} status={item.peStatus} format={v => num(v)} />
        <Cell label="PE 分位" value={item.pePercentile} status={item.percentileStatus} format={pct} />
      </div>

      {/*
        「这条曲线有多旧」是读者该自己判断的事实，所以把日期和来源都摆出来。
        不去猜「几天算旧」：交易日历在只看指数时拿不到，猜出来的阈值会在春节误报。
      */}
      <div className="stockpick-provenance">
        <div>
          <label>价格</label>
          <span>{item.quoteSource ?? '三个行情源都没取到'}</span>
        </div>
        <div>
          <label>价格位置</label>
          <span>
            {item.positionSource ?? '未取到日线'}
            {item.lastTradeDate && ` · 日线截止 ${item.lastTradeDate}`}
            {item.barCount !== null && ` · ${item.barCount} 根`}
          </span>
        </div>
        <div>
          <label>估值口径</label>
          <span>
            每个指数只用一个来源：
            {item.valuationSource ?? '未接入'}
            {item.percentileMethod && ` · ${item.percentileMethod}`}
            {item.valuationTradeDate ? ` · 数据日 ${item.valuationTradeDate}` : ''}
          </span>
        </div>
      </div>

      {/*
        被挡下 / 判不了的原因**必须贴在卡上**：只把徽章换个颜色，读者仍然不知道
        是「PE 太高」还是「PE 分位太高」，于是会以为这条判定是随机的。
      */}
      {item.qualification === 'blocked' && (
        <List title="被这些条件挡下" items={item.qualificationReasons} tone="risk" />
      )}
      {item.qualification === 'unconfirmed' && (
        <List title="数据不足，这几条没判成" items={item.qualificationReasons} tone="info" />
      )}

      <List title="说明与缺口" items={item.notes} tone="risk" />
    </article>
  )
}

export default function StockPick() {
  const { user } = useAuth()
  // 能看 ≠ 能跑。Demo 进得来这个页面，但点不动「开始筛选」。
  const canSee = user?.accountType === 'DEMO' || user?.role === 'ADMIN'
  const isAdmin = user?.role === 'ADMIN'

  const [form, setForm] = useState<Form>(DEFAULT_FORM)
  const [advanced, setAdvanced] = useState(false)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<ScanResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 限流单独一份状态：它的处置与「取数失败」完全不同——不能自动重试，
  // 每重试一次都是在把这个 IP 往更深的封禁里推。
  const [rateLimited, setRateLimited] = useState<string | null>(null)
  const [elapsed, setElapsed] = useState<number | null>(null)
  // 指数那一块的两个纯前端状态：筛选与展开。都不进 URL、不发请求——
  // 服务端返回的是一次扫描的完整口径，客户端切片是即时的，少改一处协议。
  const [categoryFilter, setCategoryFilter] = useState('all')
  const [expandedCategories, setExpandedCategories] = useState<string[]>([])
  const startedAt = useRef(0)

  // 筛选历史：后端每次成功筛选落一行，这里只读。
  const [history, setHistory] = useState<HistoryPage | null>(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const [historyPage, setHistoryPage] = useState(1)
  // 跑完一次筛选要让列表重取一次，否则刚跑完的那条要刷新页面才看得见
  const [historyKey, setHistoryKey] = useState(0)
  // 当前正在回放的那条（点了「恢复这次结果」之后），用来在那一行上做标记
  const [replayedId, setReplayedId] = useState<number | null>(null)

  const run = useCallback(async (payload: Record<string, unknown>) => {
    // 双保险：按钮已经置灰了，但这里再挡一次。真正生效的那道在服务端
    // （见 StockScreenerController）——只靠前端置灰，拿着 token 直接调接口照样能跑。
    if (!isAdmin) return
    setLoading(true)
    setError(null)
    setRateLimited(null)
    startedAt.current = Date.now()
    try {
      const res = await api.post('/stock-screener/scan', payload)
      if (res.data?.code === 200 && res.data.data) {
        setResult(res.data.data as ScanResult)
        // 这一次已经被后端记进历史了，让列表跟着刷新（回到第一页，新的在最上面）
        setReplayedId(null)
        setHistoryPage(1)
        setHistoryKey(k => k + 1)
      } else if (res.data?.code === 429) {
        setResult(null)
        setRateLimited(res.data?.message || '行情源正在限流，请稍后再试')
      } else {
        // 取数失败必须如实显示，不能把空结果当成「今天没有候选」
        setResult(null)
        setError(res.data?.message || '筛选未返回结果')
      }
    } catch (e: unknown) {
      const err = e as { response?: { data?: { code?: number; message?: string } } }
      setResult(null)
      if (err.response?.data?.code === 429) {
        setRateLimited(err.response.data.message || '行情源正在限流，请稍后再试')
      } else {
        setError(err.response?.data?.message || '筛选请求失败，请稍后重试')
      }
    } finally {
      setElapsed(Date.now() - startedAt.current)
      setLoading(false)
    }
  }, [isAdmin])

  /**
   * 拉历史列表。
   *
   * 与「进页面自动跑一次筛选」**不是一回事**：这条只读自己库里的台账，不碰任何行情源，
   * 所以每次进页面拉一次是安全的（不会消耗东财那边的配额）。
   * Demo 也进得来这个页面，看历史与看页面同权限；能不能跑由服务端决定。
   */
  useEffect(() => {
    if (!canSee) return
    const controller = new AbortController()

    setHistoryLoading(true)
    setHistoryError('')
    api.get(`/stock-screener/history?page=${historyPage}&size=10`, { signal: controller.signal })
      .then(res => {
        if (controller.signal.aborted) return
        if (res.data?.code !== 200 || !res.data.data) {
          setHistory(null)
          setHistoryError(res.data?.message || '筛选历史加载失败，请稍后重试')
          return
        }
        const d = res.data.data
        setHistory({
          records: d.records ?? [],
          total: d.total ?? 0,
          pages: d.pages ?? 0,
          current: d.current ?? historyPage,
          size: d.size ?? 10,
        })
      })
      .catch(err => {
        if (controller.signal.aborted) return
        setHistory(null)
        setHistoryError(err?.response?.data?.message || '筛选历史加载失败，请稍后重试')
      })
      .finally(() => {
        if (!controller.signal.aborted) setHistoryLoading(false)
      })

    return () => controller.abort()
  }, [canSee, historyPage, historyKey])

  /**
   * 回放某一次筛选：取那条的整页快照，连带把条件面板也填回当时那一套。
   *
   * **只读历史，不重新算**：重算会打一次行情源，而且结果可能因为行情变了而与当时不同——
   * 那就不再是「那一次」了。快照里存的就是当初的返回体。
   */
  const replay = useCallback(async (id: number) => {
    setHistoryError('')
    try {
      const res = await api.get(`/stock-screener/history/${id}`)
      if (res.data?.code !== 200 || !res.data.data?.result) {
        setHistoryError(res.data?.message || '这次历史回放失败')
        return
      }
      const snapshot = res.data.data.result as ScanResult
      // 条件在两处都有（列表项一份、整页快照一份），**只认一个来源**：
      // 面板渲染的是快照里的那一份，条件面板就也用同一份，否则万一两者哪天不一致，
      // 页面会一边显示 A 一边显示 B，而看不出谁是对的。列表项那份只作兜底。
      const applied = (snapshot?.appliedParams
        ?? res.data.data.item?.appliedParams ?? null) as ScreenerParamsView | null
      setResult(snapshot)
      if (applied) setForm(formFromParams(applied))
      setReplayedId(id)
      setError(null)
      setRateLimited(null)
      // 回放的是历史那一页，不是「刚跑完」：用时那句要清掉，免得两个时间对不上
      setElapsed(null)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } }
      setHistoryError(err.response?.data?.message || '这次历史回放失败')
    }
  }, [])

  // 这里刻意**没有**「进页面自动跑一次」。东财按 IP 计时限流，自动跑等于每进出一次
  // 这个页面就消耗一次配额——反复进出本身就是撞上限流的主要来源。
  // 现在只有点「开始筛选」和限流块里的「再试一次」会真的发请求；
  // 「重置为默认条件」只重置表单，不发请求。
  //
  // 别再把这个 effect 加回来：真要「进来就有内容」，该做的是把上次结果留在前端复用，
  // 而不是每次重新打一遍行情源。

  /** 排除行业的选择项来自上一次结果——比让人凭空输入行业名有用。 */
  const industryOptions = useMemo(() => {
    if (!result) return []
    const set = new Set<string>()
    ;[...result.steadyStocks, ...result.growthStocks].forEach(s => set.add(s.row.industry || '行业不可确认'))
    return [...set].sort()
  }, [result])

  /**
   * 指数按「类别 → 估值来源」组织好。
   *
   * **排序只在同一估值来源的子组内部做。** 跨口径排一个总榜是在比较不可比的数
   * （实测中证500 两个源差 22%、科创50 差 75%），而榜单一出来，读者一定会横向读它。
   * 章程 §5 明令禁止；这里用数据结构而不是注释来保证。
   */
  const indexGroups = useMemo(() => {
    const funds = result?.indexFunds ?? []
    const present = funds.map(f => f.category)
    // 认不出的类别原样列在最后，不吞掉——吞掉就是「有条目悄悄不见了」
    const order = [
      ...CATEGORY_ORDER.filter(c => present.includes(c)),
      ...[...new Set(present)].filter(c => !CATEGORY_ORDER.includes(c)),
    ]
    return {
      all: order.map(category => {
        const items = funds.filter(f => f.category === category)
        const subgroups = subgroupsOf(items)
        return {
          category,
          total: items.length,
          subgroups,
          counts: qualCounts(items),
          // 子组优先拉平：这样「本类前 12 张」不会把某个口径整个切掉，
          // 而每一组内部仍是排好序的。
          items: subgroups.flatMap(sg => sg.items),
        }
      }),
      etfBacked: funds.filter(f => f.etfCode !== null).length,
      counts: qualCounts(funds),
    }
  }, [result])

  const etfBackedCount = indexGroups.etfBacked
  const indexCounts = indexGroups.counts

  const patch = (part: Partial<Form>) => setForm(f => ({ ...f, ...part }))

  const toggleIndustry = (name: string) => {
    setForm(f => ({
      ...f,
      excludedIndustries: f.excludedIndustries.includes(name)
        ? f.excludedIndustries.filter(x => x !== name)
        : [...f.excludedIndustries, name],
    }))
  }

  if (!canSee) {
    return (
      <div className="market-watch-page">
        <header className="market-watch-hero">
          <div>
            <div className="market-watch-kicker">Stock Screening</div>
            <h2>低估精选</h2>
            <p>低估精选仅管理员和 Demo 可见。普通用户只看自己订阅的简报。</p>
          </div>
        </header>
      </div>
    )
  }

  const gotAny = result
    ? result.indexFunds.length + result.steadyStocks.length + result.growthStocks.length > 0
    : false

  return (
    <div className="market-watch-page">
      <header className="market-watch-hero">
        <div>
          <div className="market-watch-kicker">Stock Screening</div>
          <h2>低估精选</h2>
          <p>
            按事先写死的规则排掉明显不合格的标的，再把剩下的按「便宜程度 + 质量 + 位置」排序。
            结果是<b>待核实的观察清单</b>，不预测涨跌，不构成投资建议或买卖依据。
          </p>
          <p className="stockpick-rule-note">
            规则全文见 <code>automation/agents/stock_screening_rules.md</code>；「跌得多」不构成入选理由。
          </p>
        </div>
        <div className="market-watch-risk-badge">风险提示优先</div>
      </header>

      <section className="stockpick-panel">
        <div className="stockpick-panel-row">
          <label>
            <span>类别</span>
            <select value={form.mode} onChange={e => patch({ mode: e.target.value })}>
              <option value="index_first">指数基金优先（推荐）</option>
              <option value="index_only">只看指数基金</option>
              <option value="stock_only">只看个股</option>
            </select>
          </label>
          <label>
            <span>档位</span>
            <select value={form.bucket} onChange={e => patch({ bucket: e.target.value })}>
              <option value="both">两档都出</option>
              <option value="steady">只看稳健低估</option>
              <option value="growth">只看低估成长</option>
            </select>
          </label>
          <label>
            <span>每档只数</span>
            <select value={form.perBucket} onChange={e => patch({ perBucket: e.target.value })}>
              <option value="1">1</option>
              <option value="2">2</option>
              <option value="3">3</option>
              <option value="5">5</option>
            </select>
          </label>
          <label>
            <span>PE(TTM) 上限</span>
            <input value={form.peMax} onChange={e => patch({ peMax: e.target.value })}
              placeholder="留空按档位默认 · 指数同用" inputMode="decimal" />
          </label>
          <label>
            <span>PE 分位上限 %</span>
            <input value={form.pePercentileMax} onChange={e => patch({ pePercentileMax: e.target.value })}
              placeholder="留空不限 · 只作用于指数" inputMode="decimal" />
          </label>
          <label>
            <span>市值下限（亿）</span>
            <input value={form.marketCapMinYi} onChange={e => patch({ marketCapMinYi: e.target.value })}
              placeholder="留空按档位默认 · 指数按基金规模判" inputMode="decimal" />
          </label>
        </div>

        <button type="button" className="stockpick-advanced-toggle" onClick={() => setAdvanced(v => !v)}>
          {advanced ? '收起高级条件' : '展开高级条件'}
        </button>

        {advanced && (
          <div className="stockpick-panel-row stockpick-panel-advanced">
            <label>
              <span>PB 上限</span>
              <input value={form.pbMax} onChange={e => patch({ pbMax: e.target.value })} inputMode="decimal" />
            </label>
            <label>
              <span>股息率下限 %</span>
              <input value={form.dividendYieldMin} onChange={e => patch({ dividendYieldMin: e.target.value })} inputMode="decimal" />
            </label>
            <label>
              <span>ROE 下限 %</span>
              <input value={form.roeMin} onChange={e => patch({ roeMin: e.target.value })} inputMode="decimal" />
            </label>
            <label>
              <span>资产负债率上限 %</span>
              <input value={form.debtRatioMax} onChange={e => patch({ debtRatioMax: e.target.value })} inputMode="decimal" />
            </label>
            <label>
              <span>营收同比下限 %</span>
              <input value={form.revenueGrowthMin} onChange={e => patch({ revenueGrowthMin: e.target.value })} inputMode="decimal" />
            </label>
            <label>
              <span>净利同比下限 %</span>
              <input value={form.profitGrowthMin} onChange={e => patch({ profitGrowthMin: e.target.value })} inputMode="decimal" />
            </label>
            <div className="stockpick-industry-picker">
              <span>排除行业</span>
              {industryOptions.length === 0 ? (
                <em>先跑一次筛选，这里会列出候选中出现的行业</em>
              ) : (
                <div className="stockpick-chips">
                  {industryOptions.map(name => (
                    <button
                      key={name}
                      type="button"
                      className={`stockpick-chip ${form.excludedIndustries.includes(name) ? 'on' : ''}`}
                      onClick={() => toggleIndustry(name)}
                    >
                      {name}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        <div className="stockpick-actions">
          <button type="button" className="stockpick-run" disabled={loading || !isAdmin}
            title={isAdmin ? undefined : '筛选需管理员账号'}
            onClick={() => run(buildPayload(form))}>
            {loading ? '筛选中…' : '开始筛选'}
          </button>
          <button type="button" className="stockpick-reset" disabled={loading}
            onClick={() => setForm(DEFAULT_FORM)}>
            重置为默认条件
          </button>
          <span className="stockpick-timing">
            {loading && '正在抓行情与日线，首次通常 3–5 秒；同一交易日内再点会快很多。'}
            {!loading && result && elapsed !== null && `用时 ${(elapsed / 1000).toFixed(1)} 秒 · 数据时间 ${result.dataTime}`}
          </span>
        </div>
      </section>

      {rateLimited && (
        <section className="stockpick-throttled">
          <h3>行情源正在限流，暂时停止自动筛选</h3>
          <p>{rateLimited}</p>
          <p className="stockpick-note">
            这不是网络故障，也不用改任何设置：东财按 IP 计时封一阵。
            重试越频繁封得越久，所以这里不会自动重试。等上面说的时间过去，点下面的按钮即可。
          </p>
          <div className="stockpick-actions">
            <button type="button" className="stockpick-run" disabled={loading || !isAdmin}
              title={isAdmin ? undefined : '筛选需管理员账号'}
              onClick={() => run(buildPayload(form))}>
              {loading ? '正在重试…' : '再试一次'}
            </button>
          </div>
        </section>
      )}

      {error && (
        <section className="stockpick-error">
          <h3>本次筛选没有拿到数据</h3>
          <p>{error}</p>
          <p className="stockpick-note">
            这里显示错误而不是空列表，是因为「取数失败」和「今天确实没有合格候选」是两件事。
          </p>
        </section>
      )}

      {!isAdmin && !loading && !result && !error && !rateLimited && (
        <section className="stockpick-forbidden">
          <h3>当前账号只能查看</h3>
          <p>
            筛选条件可以照常调整，但发起筛选需要管理员账号：
            一次筛选要向东财发二十多次请求，出口 IP 会被按 IP 限流，所以触发口只留一个。
          </p>
        </section>
      )}

      {isAdmin && !loading && !result && !error && !rateLimited && (
        <section className="stockpick-idle">
          <h3>还没开始筛选</h3>
          <p>
            条件已经填好默认值，点上面的「开始筛选」再跑。
            进页面不会自动跑——行情源按 IP 限流，进一次跑一次只会让限流来得更快。
          </p>
        </section>
      )}

      {result && (
        <>
          {!gotAny && !error && (
            <section className="market-watch-empty stockpick-empty">
              本次条件下没有合格候选。见下方口径摘要里每一条规则拦下了多少只。
            </section>
          )}

          {/*
            条件回显放在最前面：这一轮修的就是「条件像没生效」。填了值却看不到结果变化的
            时候，第一件要能确认的事是「服务端收到的到底是哪一套」——包括那些留空的格子
            实际变成了什么（按档位默认 / 不限）。
          */}
          {result.appliedParams && (
            <section className="market-watch-section">
              <div className="market-watch-section-header">
                <h3>本次生效条件</h3>
                <span>留空的格子在这里写出它实际变成了什么</span>
              </div>
              <div className="stockpick-numbers stockpick-summary-numbers stockpick-params-numbers">
                {paramRows(result.appliedParams).map(([label, value]) => (
                  <div key={label}><label>{label}</label><span>{value}</span></div>
                ))}
              </div>
            </section>
          )}

          {result.indexFunds.length > 0 && (
            <section className="market-watch-section">
              <div className="market-watch-section-header">
                <h3>指数基金（优先）</h3>
                {/*
                  计数按判定拆开，**不再只报总数**：只报「共 N 个」时，扩池或改条件
                  在标题上看不出任何变化，而这正是上次让人以为「条件没用」的地方。
                */}
                <span>
                  {indexCounts.judged ? (
                    <>
                      共 {indexCounts.total} 个指数 · 本次入选 {indexCounts.qualified} ·
                      被条件挡下 {indexCounts.blocked} · 数据不足未确认 {indexCounts.unconfirmed}
                      {' · '}{etfBackedCount} 只有对应 ETF
                    </>
                  ) : (
                    <>
                      共 {indexCounts.total} 个指数 · {etfBackedCount} 只有对应 ETF
                      · 每个指数只用一个估值来源
                    </>
                  )}
                </span>
              </div>
              {!indexCounts.judged && (
                <p className="stockpick-note">
                  上面「PE(TTM) 上限 / PE 分位上限 / 市值下限」三格都没填，
                  所以这次没有对指数做任何入选判定，下面按分位从低到高列出。
                  填了任一条，卡片上就会标出谁入选、谁被挡下、为什么。
                </p>
              )}
              <p className="stockpick-caliber-warning">
                ⚠ 不同来源的 PE 分位口径不同，不可横向比较——下面按来源分了子组，
                排序只在子组内部进行。
              </p>

              {/*
                刻意**不复用** .stockpick-chip：那个类的 .on 是「已排除」的意思，带删除线。
                复用会让「选中宽基」看起来像「把宽基排除了」。
              */}
              <div className="stockpick-cat-chips">
                <button
                  type="button"
                  className={`stockpick-filter-chip ${categoryFilter === 'all' ? 'on' : ''}`}
                  onClick={() => setCategoryFilter('all')}
                >
                  全部（{result.indexFunds.length}）
                </button>
                {indexGroups.all.map(g => (
                  <button
                    key={g.category}
                    type="button"
                    className={`stockpick-filter-chip ${categoryFilter === g.category ? 'on' : ''}`}
                    onClick={() => setCategoryFilter(g.category)}
                  >
                    {categoryLabel(g.category)}（{g.total}）
                  </button>
                ))}
              </div>

              {(categoryFilter === 'all' ? indexGroups.all : indexGroups.all.filter(
                g => g.category === categoryFilter,
              )).map(g => {
                const shown = expandedCategories.includes(g.category)
                  ? g.items
                  : g.items.slice(0, DEFAULT_PER_CATEGORY)
                return (
                  <div className="stockpick-cat-block" key={g.category}>
                    <h4 className="stockpick-cat-title">
                      {categoryLabel(g.category)}
                      <span className="stockpick-code">
                        {g.total} 个指数
                        {g.counts.judged && `（入选 ${g.counts.qualified}）`}
                      </span>
                    </h4>
                    {g.subgroups.map(sg => {
                      const visible = shown.filter(f => f.valuationSource === sg.source)
                      // 计数按整组算，不按本页显示的那几张：标题说「12 个，显示前 8 个」，
                      // 那么「入选几个」也该是这 12 个里的数。
                      // 另外因为排序把合格的放在最前，被截断时先露出来的正是入选的那几支。
                      const counts = qualCounts(sg.items)
                      return (
                        <div className="stockpick-source-group" key={sg.source ?? 'none'}>
                          {/*
                            子组标题**任何时候都渲染**，哪怕这一组的卡片被上限全切掉了。
                            标题上写着这个口径有几个指数——一个口径整个消失比一张长列表难发现得多，
                            而它消失的后果正是「跨口径比较」：读者会以为榜上就这些。
                          */}
                          <h5 className="stockpick-source-title">
                            {sg.source ?? '未接入任何估值来源'}
                            <span className="stockpick-code">
                              口径 {sg.items[0]?.percentileMethod ?? '无'} ·{' '}
                              {visible.length === sg.items.length
                                ? `${sg.items.length} 个`
                                : `${sg.items.length} 个，本页显示前 ${visible.length} 个`}
                              {counts.judged && ` · 入选 ${counts.qualified}`}
                            </span>
                          </h5>
                          {visible.length > 0 && (
                            <div className="stockpick-grid">
                              {visible.map(f => <IndexFundCard key={f.indexCode} item={f} />)}
                            </div>
                          )}
                        </div>
                      )
                    })}
                    {g.items.length > DEFAULT_PER_CATEGORY && !expandedCategories.includes(g.category) && (
                      <button
                        type="button"
                        className="stockpick-expand"
                        onClick={() => setExpandedCategories(prev => [...prev, g.category])}
                      >
                        展开本类全部 {g.total} 个指数
                      </button>
                    )}
                  </div>
                )
              })}
            </section>
          )}

          {result.steadyStocks.length > 0 && (
            <section className="market-watch-section">
              <div className="market-watch-section-header">
                <h3>稳健低估</h3>
                <span>上市 ≥3 年 · 市值 ≥200 亿 · 负债率 ≤65% · PE(TTM) ≤30</span>
              </div>
              <div className="stockpick-grid">
                {result.steadyStocks.map((s, i) => <StockCandidate key={s.row.code} item={s} rank={i + 1} />)}
              </div>
            </section>
          )}

          {result.growthStocks.length > 0 && (
            <section className="market-watch-section">
              <div className="market-watch-section-header">
                <h3>低估成长</h3>
                <span>上市 ≥2 年 · 市值 ≥50 亿 · 营收与净利同比为正 · PE(TTM) ≤45</span>
              </div>
              <div className="stockpick-grid">
                {result.growthStocks.map((s, i) => <StockCandidate key={s.row.code} item={s} rank={i + 1} />)}
              </div>
            </section>
          )}

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>本次口径摘要</h3>
              <span>剔除数之和 + 进入打分数 = 扫描总数，可自行核对</span>
            </div>
            <div className="stockpick-numbers stockpick-summary-numbers">
              <div><label>扫描总数</label><span>{result.summary.scanned}</span></div>
              <div><label>通过排雷</label><span>{result.summary.afterVetoes}</span></div>
              <div><label>稳健档池子</label><span>{result.summary.steadyPool}</span></div>
              <div><label>成长档池子</label><span>{result.summary.growthPool}</span></div>
              <div><label>抓取日线</label><span>{result.summary.shortlistFetched}</span></div>
              <div><label>价格位置截止</label><span>{result.priceAsOf}</span></div>
            </div>

            {result.summary.vetoCounts.length > 0 && (
              <div className="stockpick-veto-grid">
                {result.summary.vetoCounts.map(v => (
                  <div key={v.rule} className="stockpick-veto">
                    <b>{v.rule}</b>
                    <span>{v.label}</span>
                    <em>{v.count} 只</em>
                  </div>
                ))}
              </div>
            )}

            <List title="口径说明" items={result.summary.notes} tone="reason" />
            <List title="数据降级与缺口" items={result.summary.degradations} tone="risk" />
          </section>

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>需要你人工核实的</h3>
              <span>这些没有数据源，本页不做判断</span>
            </div>
            <ul className="stockpick-manual">
              {MANUAL_CHECKS.map(t => <li key={t}>{t}</li>)}
            </ul>
            <p className="stockpick-disclaimer">{result.disclaimer}</p>
          </section>
        </>
      )}

      {/*
        历史放在最后：它是「回头看」，不该把当前结果挤下去。
        每次成功筛选后端都会落一行（含具体时间与完整快照），这里只读不写。
      */}
      <section className="market-watch-section stockpick-history">
        <div className="market-watch-section-header">
          <h3>筛选历史</h3>
          <span>
            {history ? `最近 ${history.total} 次 · 每次成功筛选留一行，保留最新的一份` : '每次成功筛选都会记下时间与当时的结果'}
          </span>
        </div>

        {historyLoading && history === null && (
          <p className="stockpick-note">正在读取筛选历史…</p>
        )}

        {historyError && (
          <p className="stockpick-history-error">{historyError}</p>
        )}

        {history && history.records.length === 0 && !historyError && (
          <p className="stockpick-note">
            还没有历史记录。跑一次筛选之后，这里会出现那一行（含点击时刻）。
          </p>
        )}

        {history && history.records.length > 0 && (
          <>
            <ul className="stockpick-history-list">
              {history.records.map(h => (
                <li key={h.id} className={replayedId === h.id ? 'on' : ''}>
                  <div className="stockpick-history-head">
                    <b>{h.scannedAt}</b>
                    <span className="stockpick-history-operator">
                      {h.scannedByEmail || '触发人未记录'}
                    </span>
                    {replayedId === h.id && <span className="stockpick-badge qual-pending">正在看这一次</span>}
                  </div>
                  <div className="stockpick-history-line">
                    {MODE_LABEL[h.mode] ?? h.mode} · {BUCKET_LABEL[h.bucket] ?? h.bucket} · 每档 {h.perBucket} 只
                  </div>
                  <div className="stockpick-history-line">
                    扫描 {h.scanned} · 通过排雷 {h.afterVetoes} ·
                    指数入选 {h.qualifiedIndexCount}/{h.indexCount} ·
                    稳健池 {h.steadyPool} · 成长池 {h.growthPool}
                  </div>
                  <div className="stockpick-history-line">
                    <em>指数</em> {refLine(h.indices)}
                  </div>
                  {h.steadyStocks.length > 0 && (
                    <div className="stockpick-history-line">
                      <em>稳健</em> {refLine(h.steadyStocks)}
                    </div>
                  )}
                  {h.growthStocks.length > 0 && (
                    <div className="stockpick-history-line">
                      <em>成长</em> {refLine(h.growthStocks)}
                    </div>
                  )}
                  <div className="stockpick-history-actions">
                    <button type="button" className="stockpick-history-replay"
                      onClick={() => replay(h.id)}>
                      恢复这次结果
                    </button>
                  </div>
                </li>
              ))}
            </ul>

            {history.pages > 1 && (
              <div className="stockpick-history-pager">
                <button type="button" disabled={historyPage <= 1}
                  onClick={() => setHistoryPage(p => Math.max(1, p - 1))}>
                  上一页
                </button>
                <span>第 {history.current} / {history.pages} 页</span>
                <button type="button" disabled={historyPage >= history.pages}
                  onClick={() => setHistoryPage(p => p + 1)}>
                  下一页
                </button>
              </div>
            )}

            {/*
              回放出来的那一页是**当时**的行情：卡片上的数据时间不会变成现在。
              不说这句，用户会以为「恢复」等于「重算」。
            */}
            {replayedId !== null && (
              <p className="stockpick-note">
                上面的结果是把那一次的快照原样放回来（含当时的行情与条件），
                没有重新请求行情源，也不是重算——要看现在的数就点「开始筛选」。
              </p>
            )}
          </>
        )}
      </section>
    </div>
  )
}