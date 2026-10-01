import { useCallback, useMemo, useRef, useState } from 'react'
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
  pricePercentileMax: string
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
  pricePercentileMax: '',
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
 * 同一估值来源内部的排序：分位升序 → 规模降序 → 指数代码升序。
 *
 * 缺值的排在最后，**不当成最小或最大**：没有分位不等于分位为 0，
 * 把它排到最前面会让人读成「最便宜的那几个，只是没数而已」。
 * 末位的 `indexCode` 只是为了同样两条数据每次渲染顺序一致——顺序会跳的榜单没法核对。
 */
function compareIndexFund(a: IndexFundItem, b: IndexFundItem) {
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
  putNumber('pricePercentileMax', form.pricePercentileMax)
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

function List({ title, items, tone }: { title: string; items: string[]; tone: 'reason' | 'risk' }) {
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
          个股在东财口径下没有自身的 PE 历史分位，所以这里只有价格位置。PE 分位只在指数上有。
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

function IndexFundCard({ item }: { item: IndexFundItem }) {
  const pct = (v: number) => `${num(v, 1)}%`
  const negativePct = (v: number) => `-${num(v, 1)}%`

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
          // 子组优先拉平：这样「本类前 12 张」不会把某个口径整个切掉，
          // 而每一组内部仍是排好序的。
          items: subgroups.flatMap(sg => sg.items),
        }
      }),
      etfBacked: funds.filter(f => f.etfCode !== null).length,
    }
  }, [result])

  const etfBackedCount = indexGroups.etfBacked

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
              placeholder="留空按档位默认" inputMode="decimal" />
          </label>
          <label>
            <span>价格分位上限 %</span>
            <input value={form.pricePercentileMax} onChange={e => patch({ pricePercentileMax: e.target.value })}
              placeholder="留空不限" inputMode="decimal" />
          </label>
          <label>
            <span>市值下限（亿）</span>
            <input value={form.marketCapMinYi} onChange={e => patch({ marketCapMinYi: e.target.value })}
              placeholder="留空按档位默认" inputMode="decimal" />
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

          {result.indexFunds.length > 0 && (
            <section className="market-watch-section">
              <div className="market-watch-section-header">
                <h3>指数基金（优先）</h3>
                <span>
                  共 {result.indexFunds.length} 个指数 · {etfBackedCount} 只有对应 ETF
                  · 每个指数只用一个估值来源
                </span>
              </div>
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
                      <span className="stockpick-code">{g.total} 个指数</span>
                    </h4>
                    {g.subgroups.map(sg => {
                      const visible = shown.filter(f => f.valuationSource === sg.source)
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
    </div>
  )
}