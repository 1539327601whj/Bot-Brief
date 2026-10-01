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

interface IndexFundItem {
  code: string
  name: string
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
  barCount: number | null
  lastTradeDate: string | null
  peTtm: number | null
  pePercentile: number | null
  percentileMethod: string | null
  percentileStatus: string
  valuationTradeDate: string | null
  notes: string[]
}

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

function IndexFundCard({ item }: { item: IndexFundItem }) {
  return (
    <article className="stockpick-card stockpick-index-card">
      <header className="stockpick-card-head">
        <div>
          <h4>
            {item.name}
            <span className="stockpick-code">{item.code}</span>
          </h4>
          <div className="stockpick-meta">
            <span>跟踪 {item.tracking}</span>
            <span className="stockpick-badge risk-low">宽基 · 破产下市风险低</span>
          </div>
        </div>
      </header>

      <div className="stockpick-numbers">
        <div><label>最新价</label><span>{num(item.price, 3)}</span></div>
        <div><label>当日</label><span>{signed(item.pctChange)}</span></div>
        <div><label>成交额</label><span>{yi(item.amountYi)}</span></div>
        <div><label>规模</label><span>{yi(item.scaleYi)}</span></div>
        <div><label>一年价格分位</label><span>{item.pricePercentile === null ? '—' : `${num(item.pricePercentile, 1)}%`}</span></div>
        <div><label>距一年最高点</label><span>{item.drawdownFromHigh === null ? '—' : `-${num(item.drawdownFromHigh, 1)}%`}</span></div>
        <div><label>一年最大回撤</label><span>{item.maxDrawdownInYear === null ? '—' : `${num(item.maxDrawdownInYear, 1)}%`}</span></div>
        <div><label>年化波动</label><span>{item.annualizedVolatility === null ? '—' : `${num(item.annualizedVolatility, 1)}%`}</span></div>
        <div><label>PE(TTM)</label><span>{num(item.peTtm)}</span></div>
        <div>
          <label>PE 分位</label>
          <span className={item.pePercentile === null ? 'stockpick-missing' : ''}>
            {item.pePercentile === null ? item.percentileStatus : `${num(item.pePercentile, 1)}%`}
          </span>
        </div>
      </div>

      {item.valuationTradeDate && (
        <p className="stockpick-note">估值分位数据日：{item.valuationTradeDate}（{item.percentileMethod}）</p>
      )}
      <List title="说明与缺口" items={item.notes} tone="risk" />
    </article>
  )
}

export default function StockPick() {
  const { user } = useAuth()
  const canSee = user?.accountType === 'DEMO' || user?.role === 'ADMIN'

  const [form, setForm] = useState<Form>(DEFAULT_FORM)
  const [advanced, setAdvanced] = useState(false)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<ScanResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 限流单独一份状态：它的处置与「取数失败」完全不同——不能自动重试，
  // 每重试一次都是在把这个 IP 往更深的封禁里推。
  const [rateLimited, setRateLimited] = useState<string | null>(null)
  const [elapsed, setElapsed] = useState<number | null>(null)
  const startedAt = useRef(0)

  const run = useCallback(async (payload: Record<string, unknown>) => {
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
  }, [])

  // 首次进页面自动用默认条件跑一次。
  // 这里刻意**不**把 rateLimited 放进依赖：run 一开头就会清掉它，
  // 放进去会变成「设限流 → 重跑 → 清限流 → 再跑」的死循环。
  // 挂载时它本来就是 null，只有 canSee 变化才需要重跑。
  useEffect(() => {
    if (canSee) run(buildPayload(DEFAULT_FORM))
  }, [canSee, run])

  /** 排除行业的选择项来自上一次结果——比让人凭空输入行业名有用。 */
  const industryOptions = useMemo(() => {
    if (!result) return []
    const set = new Set<string>()
    ;[...result.steadyStocks, ...result.growthStocks].forEach(s => set.add(s.row.industry || '行业不可确认'))
    return [...set].sort()
  }, [result])

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
          <button type="button" className="stockpick-run" disabled={loading} onClick={() => run(buildPayload(form))}>
            {loading ? '筛选中…' : '开始筛选'}
          </button>
          <button type="button" className="stockpick-reset" disabled={loading}
            onClick={() => { setForm(DEFAULT_FORM); run(buildPayload(DEFAULT_FORM)) }}>
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
            <button type="button" className="stockpick-run" disabled={loading}
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
                <span>宽基 7 只 · 破产与下市风险低于个股</span>
              </div>
              <div className="stockpick-grid">
                {result.indexFunds.map(f => <IndexFundCard key={f.code} item={f} />)}
              </div>
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