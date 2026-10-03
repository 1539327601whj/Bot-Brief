import { useCallback, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import api from '../utils/api'
import MarketMarkdown from '../components/MarketMarkdown'
import './MarketWatch.css'
import './StockPick.css'
import './CodeLookup.css'

/**
 * 后端 `CodeLookupDTO` 的镜像。**字段名一一对应，改一处要同时改两边。**
 *
 * `baseline == null ⟺ status != null` 是后端保证的不变式，所以这里没有任何
 * 「值为 null 就印个破折号」的兜底：值为 null 就印 `status` 那句话，
 * 标签照留——用户要能看到「五年」这一档存在、只是这次取不到。
 */
interface LookbackCell {
  label: string
  baseline: number | null
  baselineDate: string | null
  change: number | null
  status: string | null
}

interface QuoteView {
  provider: string
  providerLabel: string
  name: string | null
  price: number | null
  pctChange: number | null
  peTtm: number | null
  pb: number | null
  industry: string | null
  totalMarketCap: number | null
}

interface PositionView {
  available: boolean
  pricePercentile: number | null
  drawdownFromHigh: number | null
  vsMa250: number | null
  barCount: number | null
  lastTradeDate: string | null
  notes: string[]
}

interface ValuationView {
  available: boolean
  source: string | null
  sourceLabel: string | null
  percentileMethod: string | null
  peTtm: number | null
  pePercentile: number | null
  tradeDate: string | null
  historyLength: number | null
  historyFrom: string | null
  historyTo: string | null
  lookbacks: LookbackCell[]
  notes: string[]
}

type Kind = 'INDEX_FUND' | 'INDEX' | 'OFF_POOL_INDEX' | 'STOCK' | 'FUND'

interface LookupResult {
  code: string
  resolvedCode: string
  name: string | null
  kind: Kind
  kindLabel: string
  quote: QuoteView | null
  currentPrice: number | null
  currentPriceDate: string | null
  priceLookbacks: LookbackCell[]
  position: PositionView | null
  valuation: ValuationView
  notes: string[]
  snapshotAt: string
  fromCache: boolean
}

/**
 * 代码的合法形态。**与后端 `CodeLookupService.CODE_SHAPE` 是同一个正则**：
 * 这里放宽到字母数字是因为 `NDX`、`H30533`、`SZ399006` 都是合法输入，
 * 卡成 `/^\d{6}$/` 会把这些真的能查的代码挡在门外——用户看到的会是「我明明有这只」。
 */
const CODE_SHAPE = /^[0-9A-Za-z]{1,12}$/

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
 * 变化怎么写。**逐字对齐 ETF 日报的 `fmt_compact_pct_change`**：
 * 涨 `↑ +0.52%`、跌 `↓ -0.52%`、零 `0.00%`。
 *
 * 分位那一行的差值其实是**百分点差**（51 比 50 高 1 点），日报同样带 `%` 后缀——
 * 这里跟日报保持一致，因为这一行就是用户拿去和日报逐格对照的。真正不许做的是
 * 把百分点差**算成**相对涨幅（那会把 1 个点说成 2%），那件事在后端，
 * 见 `LookbackCalculator.ChangeStyle`。
 */
function changeText(v: number | null): string | null {
  if (v === null || v === undefined) return null
  const n = Number(v)
  if (Math.abs(n) < 0.005) return '0.00%'
  return n > 0 ? `↑ +${n.toFixed(2)}%` : `↓ ${n.toFixed(2)}%`
}

/**
 * 拼成日报那一行：`今 51｜昨 50 ↑ +0.52%｜周 51 ↑ +0.08%｜…｜十年 90 ↓ -39.48%`。
 *
 * **取不到的档位不在这里出现**，而是在下面单独列一条（见 `MissingCells`）。
 * 理由是可读性而不是回避：那些 `status` 是一整句「为什么没有」（「该指数口径源（蛋卷）
 * 只提供当日值…」），塞进这一行会把 8 档挤成一团乱麻，反而看不出哪几档有数。
 * 但**标签与原因都不会被丢掉**——只是换了行。日报那边是把缺的档整格丢掉，
 * 这里刻意不照做。
 */
function joinLookbacks(todayText: string, cells: LookbackCell[], format: (v: number) => string): string {
  const parts = [`今 ${todayText || '不可确认'}`]
  for (const c of cells) {
    if (c.baseline === null || c.baseline === undefined) continue
    const change = changeText(c.change)
    const value = format(c.baseline)
    parts.push(change ? `${c.label} ${value} ${change}` : `${c.label} ${value}`)
  }
  return parts.join('｜')
}

/** 缺档单独列一条：标签 + 那一档自己的原因。八档里缺了哪几档、为什么，一眼可见。 */
function MissingCells({ cells }: { cells: LookbackCell[] }) {
  const missing = cells.filter(c => c.baseline === null || c.baseline === undefined)
  if (missing.length === 0) return null
  return (
    <ul className="codelookup-missing">
      {missing.map(c => (
        <li key={c.label}>
          <b>{c.label}</b>
          <span>{c.status || '未确认（后端未给出原因，请反馈）'}</span>
        </li>
      ))}
    </ul>
  )
}

/**
 * 各档基线取的是哪一天。**章程 §8 要求 5 年 / 10 年格能回答「用的是哪一日」**——
 * 而这一行正好也是「十年」到底有没有真的够到十年前那一天的证据：日频序列能取到当日，
 * 周频观测只能取到当日或之前最近的那一次。
 */
function BaselineDates({ cells }: { cells: LookbackCell[] }) {
  const dated = cells.filter(c => c.baselineDate)
  if (dated.length === 0) return null
  return (
    <p className="codelookup-baselines">
      <label>各档基线取自</label>
      {dated.map(c => `${c.label} ${c.baselineDate}`).join(' · ')}
    </p>
  )
}

/** 一格数字。**有值印值，无值印原因**——空白的读法太多了，会让人以为那是 0。 */
function Cell({ label, value, status, format }: {
  label: string
  value: number | null | undefined
  status?: string | null
  format: (v: number) => string
}) {
  if (value === null || value === undefined) {
    return (
      <div className="stockpick-cell-wide">
        <label>{label}</label>
        <span className="stockpick-missing">{status || '未确认（本次没取到）'}</span>
      </div>
    )
  }
  return (
    <div>
      <label>{label}</label>
      <span>{format(Number(value))}</span>
    </div>
  )
}

export default function CodeLookup() {
  const { user } = useAuth()
  // 与「市场观察」「低估精选」同一可见范围。真正生效的那道判定在服务端
  // （CodeLookupController 里），前端这里只决定画什么。
  const canSee = user?.accountType === 'DEMO' || user?.role === 'ADMIN'

  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<LookupResult | null>(null)
  const [queried, setQueried] = useState('')
  const [error, setError] = useState('')
  // 三种「没查到结果」分开存：它们的下一步完全不同。
  // 404 = 换一个代码；429 = 等一会儿，且**绝不自动重试**；其余 = 等一会儿再试同一个代码。
  const [notFound, setNotFound] = useState('')
  const [rateLimited, setRateLimited] = useState('')

  const codeReady = CODE_SHAPE.test(input.trim())

  const lookup = useCallback(async (raw: string) => {
    const code = raw.trim()
    if (!CODE_SHAPE.test(code)) return
    setLoading(true)
    setError('')
    setNotFound('')
    setRateLimited('')
    setResult(null)
    setQueried(code)
    try {
      const res = await api.get(`/code-lookup?code=${encodeURIComponent(code)}`)
      const status = res.data?.code
      if (status === 200 && res.data.data) {
        setResult(res.data.data as LookupResult)
      } else if (status === 429) {
        // 限流**不进 error**：它不是故障，处置方式也完全不同（等，而不是改代码）
        setRateLimited(res.data?.message || '行情源正在限流，请稍后再试')
      } else if (status === 404) {
        setNotFound(res.data?.message || `查不到代码 ${code}`)
      } else {
        setError(res.data?.message || '查询未返回结果')
      }
    } catch (e: unknown) {
      const err = e as { response?: { data?: { code?: number; message?: string } } }
      const status = err.response?.data?.code
      const message = err.response?.data?.message
      if (status === 429) setRateLimited(message || '行情源正在限流，请稍后再试')
      else if (status === 404) setNotFound(message || `查不到代码 ${code}`)
      else setError(message || '查询请求失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }, [])

  if (!canSee) {
    return (
      <div className="market-watch-page">
        <header className="market-watch-hero">
          <div>
            <div className="market-watch-kicker">Code Lookup</div>
            <h2>代码查询</h2>
            <p>代码查询仅管理员和 Demo 可见。普通用户只看自己订阅的简报。</p>
          </div>
        </header>
      </div>
    )
  }

  const priceToday = result
    ? (result.quote?.price !== null && result.quote?.price !== undefined
      ? num(result.quote.price, 3)
      : (result.currentPrice !== null && result.currentPrice !== undefined
        ? num(result.currentPrice, 3)
        : '不可确认'))
    : ''

  const peToday = result && result.valuation.pePercentile !== null
    ? num(result.valuation.pePercentile, 0)
    : '不可确认'

  return (
    <div className="market-watch-page">
      <header className="market-watch-hero">
        <div>
          <div className="market-watch-kicker">Code Lookup</div>
          <h2>代码查询</h2>
          <p>
            输入一个代码，实时取它的<b>价格、PE 与 PE 分位</b>，回看距离到<b>十年</b>。
            版式与 ETF 日报一致（<code>今 51｜昨 50 ↑ +0.52%｜…｜十年 90 ↓ -39.48%</code>），
            但这是<b>按需查询</b>：不订阅、不生成日报、结果不落库，查完即走。
          </p>
          <p className="stockpick-rule-note">
            可查：池内指数的 ETF 代码（<code>510300</code>）、指数代码（<code>000300</code>、
            <code>SZ399006</code>、<code>NDX</code>）、6 位 A 股代码（<code>300274</code>）、
            中证官网的指数代码（<code>930740</code>）。
          </p>
        </div>
        <div className="market-watch-risk-badge">风险提示优先</div>
      </header>

      <section className="stockpick-panel">
        <div className="stockpick-panel-row">
          <label>
            <span>代码</span>
            <input
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && codeReady && !loading) lookup(input) }}
              placeholder="510300 / 000300 / 300274 / NDX"
              maxLength={12}
              autoComplete="off"
              spellCheck={false}
            />
          </label>
        </div>
        <div className="stockpick-actions">
          <button
            type="button"
            className="stockpick-run"
            disabled={loading || !codeReady}
            onClick={() => lookup(input)}
          >
            {loading ? '查询中…' : '查询'}
          </button>
          <button
            type="button"
            className="stockpick-reset"
            disabled={loading}
            onClick={() => { setInput(''); setResult(null); setQueried(''); setError(''); setNotFound(''); setRateLimited('') }}
          >
            清空
          </button>
          <span className="stockpick-timing">
            {loading && '正在取行情、长档日线与估值历史，通常 1–3 秒。'}
            {!loading && result && `数据时间 ${result.snapshotAt}${result.fromCache ? '（进程内缓存）' : ''}`}
          </span>
        </div>
      </section>

      {rateLimited && (
        <section className="stockpick-throttled">
          <h3>行情源正在限流</h3>
          <p>{rateLimited}</p>
          <p className="stockpick-note">
            这不是网络故障，也不用改任何设置：东财按 IP 计时封一阵。
            <b>重试越频繁封得越久</b>，所以这里不会自动重试；东财在冷却期内一次都不会再被打。
            等上面说的时间过去，点下面的按钮即可。
          </p>
          <div className="stockpick-actions">
            <button type="button" className="stockpick-run" disabled={loading}
              onClick={() => lookup(input)}>
              {loading ? '正在重试…' : '再试一次'}
            </button>
          </div>
        </section>
      )}

      {notFound && (
        <section className="stockpick-error codelookup-notfound">
          <h3>查不到这个代码</h3>
          <p>{notFound}</p>
          <p className="stockpick-note">
            与「源取不到数」不同：这是数据源<b>明确回答</b>了「没有这个代码」，
            所以改代码才有用，等一会儿再试同一个还是会得到这一句。
          </p>
        </section>
      )}

      {error && (
        <section className="stockpick-error">
          <h3>本次没有取到数据</h3>
          <p>{error}</p>
          <p className="stockpick-note">
            这里显示错误而不是一张空表，是因为「取数失败」和「这个标的没有 PE」是两件事。
            等一会儿可以再试<b>同一个</b>代码。
          </p>
        </section>
      )}

      {!loading && !result && !error && !notFound && !rateLimited && (
        <section className="stockpick-idle">
          <h3>还没有查询</h3>
          <p>
            填好代码后点「查询」或直接回车。进页面不会自动查——
            每次查询都要真的外呼一次行情源与估值源，东财按 IP 限流，反复进出就查只会让限流来得更快。
          </p>
          <p className="stockpick-note">
            {input.trim() && !codeReady
              ? '这个代码的写法不认识：只能是 1–12 位字母或数字（如 510300、SZ399006、NDX）。'
              : '同一个代码 5 分钟内再查会用进程内缓存，不会重复外呼。'}
          </p>
        </section>
      )}

      {result && (
        <>
          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>
                {result.name || '名称未取到'}
                <span className="stockpick-code">{result.code}</span>
              </h3>
              <span>{result.kindLabel}</span>
            </div>
            <div className="codelookup-meta">
              {/* 判成哪一类要显出来：000905 这类数字既是深市股票也是中证500，
                  走错路会拿到一只完全无关的标的，而页面上只看到一个名字。
                  类别已经在标题行右侧写着，这里不重复一遍，只补更细的取数信息。 */}
              <span>实际查询代码 <b>{result.resolvedCode}</b></span>
              {result.currentPriceDate && <span>价格日线截止 {result.currentPriceDate}</span>}
              {result.fromCache && <span className="stockpick-badge qual-pending">进程内缓存</span>}
            </div>
          </section>

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>价格回看</h3>
              <span>
                {result.quote?.providerLabel
                  ? `现价来自 ${result.quote.providerLabel}`
                  : '本次没有取现价'}
              </span>
            </div>
            <MarketMarkdown>{`- ${joinLookbacks(priceToday, result.priceLookbacks, v => num(v, 3))}`}</MarketMarkdown>
            <BaselineDates cells={result.priceLookbacks} />
            <MissingCells cells={result.priceLookbacks} />
            <p className="stockpick-note">
              涨跌幅是相对涨幅（今 ÷ 基线 − 1）；日线为前复权，否则除权日会显示假跌。
            </p>
          </section>

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>PE 分位回看</h3>
              <span>
                {result.valuation.available
                  ? `${result.valuation.sourceLabel || result.valuation.source || '来源未标'} · 口径 ${result.valuation.percentileMethod || '未标'}`
                  : '本次没有取到估值'}
              </span>
            </div>
            <MarketMarkdown>{`- ${joinLookbacks(peToday, result.valuation.lookbacks, v => num(v, 0))}`}</MarketMarkdown>
            <BaselineDates cells={result.valuation.lookbacks} />
            <MissingCells cells={result.valuation.lookbacks} />
            <p className="stockpick-note">
              分位是<b>百分点差</b>（51 比 50 高 1 个点），不是相对涨幅；
              带 <code>%</code> 后缀是为与 ETF 日报逐格对齐。
            </p>
            {/*
              估值的 notes **无论 available 与否都要显示**。它们不只是失败原因：
              「估值历史里 143 天没有 PE（亏损或未披露），未计入窗口」这句说的是
              **分母被改过**——少掉的那几天直接改变分位的分母，而整条分位曲线只会偏一点点，
              页面上永远看不出来（章程 §6）。只在缺数据时才显示，恰好把这种最该说的话藏起来。
            */}
            {result.valuation.notes.length > 0 && (
              <ul className="codelookup-notes">
                {result.valuation.notes.map(n => <li key={n}><span>{n}</span></li>)}
              </ul>
            )}
          </section>

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>数字明细</h3>
              <span>每一格都带出处；取不到的写着为什么</span>
            </div>

            {result.quote ? (
              <div className="stockpick-numbers">
                <Cell label="最新价" value={result.quote.price} format={v => num(v, 3)} />
                <Cell label="当日" value={result.quote.pctChange} format={signed} />
                <Cell label="报价 PE(TTM)" value={result.quote.peTtm}
                  status={`${result.quote?.providerLabel || '行情源'} 未提供该项（指数与 ETF 通常就没有 PE）`}
                  format={v => num(v, 2)} />
                <Cell label="市净率" value={result.quote.pb}
                  status={`${result.quote?.providerLabel || '行情源'} 未提供该项`}
                  format={v => num(v, 2)} />
                {/*
                  行业与总市值只写「哪个源没给」，**不写为什么没给**：真实原因可能是
                  指数/ETF 本来就没有行业、也可能这家源这次没带上，页面区分不了。
                  编一个听起来合理的解释，比留白更坏——它会把用户引向错误的下一个动作。
                */}
                <Cell label="行业" value={result.quote.industry ? 1 : null}
                  status={`${result.quote?.providerLabel || '行情源'} 未提供该项`}
                  format={() => result.quote?.industry || '—'} />
                <Cell label="总市值" value={result.quote.totalMarketCap}
                  status={`${result.quote?.providerLabel || '行情源'} 未提供该项`}
                  format={yi} />
              </div>
            ) : (
              <p className="stockpick-gap">
                本次没有取现价与涨跌幅，原因见下方「说明与缺口」——价格八档与估值照常。
              </p>
            )}

            {/*
              两个 PE 必须分开写。行情接口那个（f115）只有当日一个值；分位用的是另一个源的
              **历史序列**，两份快照同一天可能不等。并排显示且各带标签，
              「同一只标的两个 PE」才是一句看得懂的话，而不是一个看起来像 bug 的东西。
            */}
            <div className="stockpick-numbers codelookup-valuation-numbers">
              <Cell label="估值 PE(TTM)（分位所用口径）" value={result.valuation.peTtm}
                status={result.valuation.available ? null : '本次未取到估值'}
                format={v => num(v, 2)} />
              <Cell label="PE 分位" value={result.valuation.pePercentile}
                status={result.valuation.available ? null : '本次未取到估值'}
                format={v => `${num(v, 2)}%`} />
              <Cell label="估值数据日" value={result.valuation.tradeDate ? 1 : null}
                status="估值数据日未标"
                format={() => result.valuation.tradeDate || '—'} />
              <Cell label="分位实际窗口" value={result.valuation.historyLength}
                status="未标历史条数"
                format={v => `${v} 条`} />
            </div>
            <p className="stockpick-note">
              两个 PE <b>不是同一份快照</b>：<code>报价 PE</code> 是行情接口的当日值（没有历史），
              <code>估值 PE</code> 是能算分位的那条历史序列的当日值。分位用的是后者。
            </p>
          </section>

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>来源与口径</h3>
              <span>每个来源只说自己那一段，口径不混用</span>
            </div>
            <div className="stockpick-provenance">
              <div>
                <label>价格</label>
                <span>
                  {result.quote?.providerLabel ?? '本次没有取现价'}
                  {result.currentPriceDate && ` · 日线截止 ${result.currentPriceDate}`}
                </span>
              </div>
              <div>
                <label>价格位置</label>
                <span>
                  {result.position?.available
                    ? `${result.position.barCount ?? '?'} 根日线 · 截止 ${result.position.lastTradeDate ?? '未标'}`
                    : '未取到日线'}
                </span>
              </div>
              <div>
                <label>估值口径</label>
                <span>
                  {result.valuation.sourceLabel || result.valuation.source || '本次没有取到估值'}
                  {result.valuation.percentileMethod && ` · ${result.valuation.percentileMethod}`}
                  {result.valuation.historyFrom && result.valuation.historyTo
                    && ` · 历史 ${result.valuation.historyFrom} ~ ${result.valuation.historyTo}`}
                </span>
              </div>
            </div>
            {result.valuation.historyLength !== null && result.valuation.historyFrom && (
              <p className="stockpick-note">
                分位窗口是 <b>min(10 年, 该序列实际可用的长度)</b>
                {result.valuation.historyLength < 2500
                  ? '——这条序列不足 10 年，所以「十年」那一档的窗口其实更短，上面已经标出来了。'
                  : '——这条序列够 10 年。'}
              </p>
            )}
          </section>

          {result.position?.available && (
            <section className="market-watch-section">
              <div className="market-watch-section-header">
                <h3>价格位置</h3>
                <span>前复权，非估值口径</span>
              </div>
              <div className="stockpick-numbers">
                <Cell label="一年价格分位" value={result.position.pricePercentile} format={v => `${num(v, 1)}%`} />
                <Cell label="距一年最高点" value={result.position.drawdownFromHigh} format={v => `-${num(v, 1)}%`} />
                <Cell label="对 MA250" value={result.position.vsMa250} format={signed} />
                <Cell label="日线根数" value={result.position.barCount} format={v => `${v} 根`} />
              </div>
            </section>
          )}

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>说明与缺口</h3>
              <span>本次用了哪个源、哪些格子为什么是空的</span>
            </div>
            {result.notes.length > 0 ? (
              <ul className="codelookup-notes">
                {result.notes.map(n => <li key={n}><span>{n}</span></li>)}
              </ul>
            ) : (
              <p className="stockpick-note">本次没有降级，也没有缺口。</p>
            )}
            {result.position && result.position.notes.length > 0 && (
              <ul className="codelookup-notes">
                {result.position.notes.map(n => <li key={n}><span>{n}</span></li>)}
              </ul>
            )}
          </section>

          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>风险提示</h3>
              <span>这一页只回答「数字是多少、从哪来」，不回答「该不该买」</span>
            </div>
            <ul className="stockpick-manual">
              <li>不同来源的 PE 分位口径不同（实测中证自算与蛋卷能差 22%–75%），<b>不可横向比较</b>；
                这一页一次只给一个标的，所以每条都写着用的是哪个口径。</li>
              <li>PE 分位低只表示相对自己历史便宜，不表示未来不会更便宜。</li>
              <li>个股的 PE 历史以估值分析源能提供的深度为限；不足 10 年时「十年」那一档会写明历史不足，
                不会拿序列里最老的一天顶上。</li>
              <li>缺数据就是缺数据：本页不用 0、也不用别的口径的数去补空着的格子。</li>
            </ul>
            <p className="stockpick-disclaimer">
              本页是公开数据的即时查询结果，仅供研究参考，不构成投资建议或买卖依据。
            </p>
          </section>
        </>
      )}

      {queried && !loading && !result && !error && !notFound && !rateLimited && (
        <section className="market-watch-empty stockpick-empty">
          代码 {queried} 没有返回结果。请重试一次。
        </section>
      )}
    </div>
  )
}