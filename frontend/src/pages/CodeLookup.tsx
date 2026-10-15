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

/**
 * 基本面与动量快照。**与 `quote` 同源、同一次外呼**——这些字段本来就随行情接口回来了，
 * 只是以前没端出来。所以这一块多出来不花任何一次外呼。
 *
 * `available` 是「这次到底有没有拿到任何一项」，不是「这家公司好不好」：
 * ETF 与指数本来就没有这组财报口径的字段，腾讯/新浪那两个兜底源更是一个都不给。
 * 两种「空」的原因不同，`notes` 里写着是哪一种。
 */
interface FundamentalsView {
  available: boolean
  provider: string | null
  providerLabel: string | null
  roe: number | null
  revenueGrowth: number | null
  profitGrowth: number | null
  grossMargin: number | null
  debtRatio: number | null
  dividendYield: number | null
  turnoverRate: number | null
  amount: number | null
  floatMarketCap: number | null
  change60d: number | null
  ytdChange: number | null
  bps: number | null
  listDate: string | null
  notes: string[]
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
  fundamentals: FundamentalsView
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

/**
 * 这条 PE 序列有没有 **10 年的跨度**。
 *
 * **不能按条数判**：蛋卷那支给的是**周频**快照（约 52 条/年），516 条就有 10 年。
 * 原先这里写的是 `historyLength < 2500`——那是个日频阈值（2500 ≈ 10 年交易日），
 * 接上周频序列之后会把一条十足的十年序列说成「不足 10 年」，正好说反。
 * 按日期跨度判对所有频率都成立。
 */
function spansTenYears(from: string | null, to: string | null): boolean | null {
  if (!from || !to) return null
  const start = new Date(`${from}T00:00:00Z`)
  const end = new Date(`${to}T00:00:00Z`)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return null
  const tenYearsLater = new Date(start)
  tenYearsLater.setUTCFullYear(tenYearsLater.getUTCFullYear() + 10)
  return end.getTime() >= tenYearsLater.getTime()
}

/**
 * 市值 / 成交额那几个格子怎么写。**入参是「元」，这里换算成「亿」。**
 *
 * 后端给的是原始元值（`QuoteView.totalMarketCap`、`FundamentalsView.amount/floatMarketCap`，
 * 即东财的 `f20/f21/f6`），与「低估精选」页同一个口径——那一页的调用处写的是
 * `yi(v / 1e8)`。这边一开始漏了除法，页面上就把 `1200000000` 写成了
 * 「1200000000.00 亿」（那是 12 亿的成交额）。函数名里带上单位就是为了让这个坑
 * 只踩一次：**名字看着像在说「元」，就不该有人往里塞已经算好的亿。**
 */
function yiFromYuan(v: number | null | undefined) {
  if (v === null || v === undefined) return '—'
  return `${(Number(v) / 1e8).toFixed(2)} 亿`
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
 * 理由是可读性而不是回避：那些 `status` 是一整句「为什么没有」（例如价格那条
 * 「本次只有 N 根日线（最早 D），超出这个跨度的档位未确认」），塞进这一行会把 8 档
 * 挤成一团乱麻，反而看不出哪几档有数。
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

// ====================================================================
// 深度分析（GET /code-lookup/analysis 与 /code-lookup/analysis/brief）
// ====================================================================

/**
 * 判定态。**枚举名与后端 `AnalysisRules.Stance` 逐字对应**，不是各写一套中文——
 * 中文只在 `STANCE_LABEL` 那张表里出现一次，加减一态时编译器会在这里报错。
 */
type Stance = 'FAVORABLE' | 'NEUTRAL' | 'UNFAVORABLE' | 'INSUFFICIENT' | 'NOT_APPLICABLE'

/** 倾向词表，**封闭**（章程 §9.1）。后端不会给出这四个之外的值。 */
type TendencyKind = 'WATCH' | 'WAIT' | 'HIGH_RISK' | 'INSUFFICIENT'

type NewsCategory = 'NOTICE' | 'NEWS' | 'POLICY'

/** 一组新闻的状态。**`EMPTY` 与 `FAILED` 必须分开**：前者是正常结果，后者要去看源。 */
type NewsGroupState = 'OK' | 'EMPTY' | 'FAILED'

const STANCE_LABEL: Record<Stance, string> = {
  FAVORABLE: '偏正面',
  NEUTRAL: '中性',
  UNFAVORABLE: '偏负面',
  INSUFFICIENT: '未确认',
  NOT_APPLICABLE: '不适用',
}

const TENDENCY_LABEL: Record<TendencyKind, string> = {
  WATCH: '偏可关注',
  WAIT: '需等待',
  HIGH_RISK: '风险偏高',
  INSUFFICIENT: '数据不足（本次不给倾向）',
}

/** 一态一个颜色槽位。**只给槽位名，颜色在 CSS 里**——深浅两套主题共用同一套类名。 */
function stanceClass(stance: Stance): string {
  switch (stance) {
    case 'FAVORABLE': return 'fav'
    case 'NEUTRAL': return 'neutral'
    case 'UNFAVORABLE': return 'unfav'
    default: return 'insufficient'
  }
}

function tendencyClass(tendency: TendencyKind): string {
  switch (tendency) {
    case 'WATCH': return 'watch'
    case 'WAIT': return 'wait'
    case 'HIGH_RISK': return 'risk'
    default: return 'insufficient'
  }
}

interface AnalysisMetric {
  label: string
  value: number | null
  display: string | null
  stance: Stance
  basis: string
}

interface AnalysisDimension {
  key: string
  label: string
  stance: Stance
  metrics: AnalysisMetric[]
  reasons: string[]
}

interface CheckupItem { rule: string; title: string; hit: boolean; detail: string }

interface Checkup {
  applicable: boolean
  items: CheckupItem[]
  hardVetoes: string[]
  note: string | null
}

interface TendencyVerdict {
  tendency: TendencyKind
  netScore: number
  countedDimensions: number
  reasons: string[]
  excluded: string[]
}

interface NewsItemView {
  category: NewsCategory
  origin: string
  originLabel: string
  title: string
  source: string | null
  publishedOn: string
  url: string
  noticeCategory: string | null
}

interface NewsGroupView {
  category: NewsCategory
  label: string
  items: NewsItemView[]
  state: NewsGroupState
  note: string
}

interface AnalysisResult {
  code: string
  resolvedCode: string
  name: string | null
  kind: Kind
  kindLabel: string
  snapshotAt: string
  fromCache: boolean
  notes: string[]
  dimensions: AnalysisDimension[]
  checkup: Checkup
  tendency: TendencyVerdict
  pendingChecks: string[]
  news: NewsGroupView[]
  disclaimer: string
}

interface AnalysisBriefView {
  available: boolean
  title: string
  text: string
  note: string | null
}

interface BriefResult {
  code: string
  resolvedCode: string
  name: string | null
  snapshotAt: string
  fromCache: boolean
  brief: AnalysisBriefView
}

/**
 * 倾向标签。**这一块是整页唯一给出倾向的地方**，所以它自己要把三件事写全：
 * 倾向词本身、净分怎么来的、以及**哪几维没参判**。
 *
 * 最后那一条不能省：「指数没有财报」如果不显示成「未计入」，会被静默读成「没问题」——
 * 一个 0 分的默认值和一个「没参与打分」在页面上长得一模一样（章程 §6 第 4 条）。
 */
function TendencyBadge({ verdict }: { verdict: TendencyVerdict }) {
  return (
    <div className={`codelookup-tendency codelookup-tendency-${tendencyClass(verdict.tendency)}`}>
      <div className="codelookup-tendency-head">
        <label>倾向（本地规则算出，不是大模型给的）</label>
        <strong>{TENDENCY_LABEL[verdict.tendency]}</strong>
      </div>
      {/*
        这一行与下面 `reasons` 里那条「净分 N（偏正面 +1 / 中性 0 / 偏负面 −1…）」**分工**：
        这里只给两个数 + 那条**别处都没说的**计分纪律（未确认/不适用不加分也不减分），
        加减法由那条 reason 讲。两处都展开写，页面上会连着出现两遍一模一样的「净分 +3（…）」。
      */}
      <p className="codelookup-tendency-score">
        净分 <b>{verdict.netScore >= 0 ? `+${verdict.netScore}` : verdict.netScore}</b>
        （参判维度 {verdict.countedDimensions} 个；未确认与不适用<b>不加分也不减分</b>）
      </p>
      {verdict.reasons.length > 0 && (
        <ul>
          {verdict.reasons.map(r => <li key={r}>{r}</li>)}
        </ul>
      )}
      {verdict.excluded.length > 0 && (
        <p className="codelookup-tendency-excluded">
          <b>未计入本次倾向</b>
          {verdict.excluded.join('；')}
        </p>
      )}
    </div>
  )
}

/**
 * 维度判定卡。**判定是本地规则算的**，这里只负责把「值 / 判定 / 凭什么这么判」摆出来。
 *
 * `NOT_APPLICABLE` 的卡**整张不渲染**：那几维（例如指数没有财报）不进这一栏，
 * 它们的名字出现在上面「未计入本次倾向」那一行里。「不适用」渲染成一张卡，
 * 会让人以为那是一格「没取到」——两者的下一步动作完全不同。
 */
function VerdictCards({ dimensions }: { dimensions: AnalysisDimension[] }) {
  const cards = dimensions.filter(d => d.stance !== 'NOT_APPLICABLE')
  if (cards.length === 0) {
    return <p className="stockpick-gap">本次没有任何一维可以判定，倾向因此判为「数据不足」。</p>
  }
  return (
    <div className="codelookup-verdict-grid">
      {cards.map(d => (
        <article key={d.key} className={`codelookup-verdict codelookup-verdict-${stanceClass(d.stance)}`}>
          <header>
            <h4>{d.label}</h4>
            <span>{STANCE_LABEL[d.stance]}</span>
          </header>
          {/*
            「未确认」要明写「未计入」。这里只判 INSUFFICIENT 而不是照抄后端的
            `Stance.counts()`（那边是三态），是因为「不适用」的卡在上面被滤掉了——
            少的那一态由「未计入本次倾向」那一行承担，不会漏。
          */}
          {d.stance === 'INSUFFICIENT' && (
            <p className="codelookup-verdict-uncounted">未计入本次倾向</p>
          )}
          <ul className="codelookup-verdict-metrics">
            {d.metrics.map(m => (
              <li key={m.label}>
                <b>{m.label}</b>
                <span className="codelookup-metric-value">{m.display ?? '未取到'}</span>
                <span className="codelookup-metric-basis">{m.basis}</span>
              </li>
            ))}
          </ul>
          {d.reasons.length > 0 && (
            <ul className="codelookup-verdict-reasons">
              {d.reasons.map(r => <li key={r}>{r}</li>)}
            </ul>
          )}
        </article>
      ))}
    </div>
  )
}

/**
 * 排雷体检（章程 §3 的 V1–V12）。
 *
 * **这里逐条列全，不像喂给大模型的材料那样只列命中项。** 两处不一样是有意的：
 * 材料里列未命中的规则，模型会把它当成结论的一部分复述出来；而页面上，
 * 「这一条查了、没越线、实际值是 X」正是用户要看的——不然「没提」和「没查」分不出来。
 * 那套「0 命中也要说清它意味着什么」的话两边都写，因为它是这一栏唯一容易读错的地方。
 */
function CheckupList({ checkup }: { checkup: Checkup }) {
  if (!checkup.applicable) {
    return (
      <>
        <p className="stockpick-gap">这一类标的本次不跑排雷体检。</p>
        {checkup.note && <p className="stockpick-note">{checkup.note}</p>}
      </>
    )
  }
  const hits = checkup.items.filter(i => i.hit).length
  return (
    <>
      <p className="stockpick-note">
        共 {checkup.items.length} 条适用规则，命中 <b>{hits}</b> 条。
        用的是<b>绝对阈值</b>（章程 §3 的稳健档），不是池内相对分位。
      </p>
      <ul className="codelookup-checkup">
        {checkup.items.map(i => (
          <li key={i.rule} className={i.hit ? 'codelookup-checkup-hit' : undefined}>
            <b>{i.rule} {i.title}</b>
            <span className="codelookup-checkup-state">{i.hit ? '命中' : '未命中'}</span>
            <span className="codelookup-checkup-detail">{i.detail}</span>
          </li>
        ))}
      </ul>
      {hits === 0 && (
        <p className="stockpick-note">
          <b>没有命中项，这不代表没问题</b>——只代表这些门槛都没越过。
        </p>
      )}
      {checkup.hardVetoes.length > 0 && (
        <>
          <p className="stockpick-gap">
            硬否决（命中任一条，倾向直接判「风险偏高」）：
          </p>
          <ul className="codelookup-missing">
            {checkup.hardVetoes.map(v => <li key={v}><b>硬否决</b><span>{v}</span></li>)}
          </ul>
        </>
      )}
    </>
  )
}

/**
 * 三组新闻：公告 / 新闻 / 政策。**只输出「标题 + 来源 + 日期 + 链接」**（章程 §9.10）。
 *
 * 三张卡是三个独立失败域，所以**一张灰不影响另外两张**。`FAILED` 与 `EMPTY` 的
 * 视觉必须分开：「源挂了」要去查源，「最近没有公告」是正常结果。
 */
function NewsDigestList({ groups }: { groups: NewsGroupView[] }) {
  if (groups.length === 0) {
    return <p className="stockpick-gap">本次没有可列出的新闻来源。</p>
  }
  return (
    <div className="codelookup-news">
      {groups.map(g => (
        <div key={g.category}
          className={`codelookup-news-group${g.state === 'FAILED' ? ' codelookup-news-failed' : ''}`}>
          <header>
            <h4>{g.label}</h4>
            <span>
              {g.state === 'OK' ? `共 ${g.items.length} 条`
                : g.state === 'EMPTY' ? '本次没有条目' : '这一路没有取到'}
            </span>
          </header>
          {g.state === 'FAILED' ? (
            <p className="stockpick-gap">
              这一路源本次没有成功返回，卡片内容为空。<b>这与「最近没有」是两件事</b>，不要当成后者读。
            </p>
          ) : g.items.length === 0 ? (
            <p className="stockpick-gap">源正常返回了，只是这段时间内没有可列出的条目。</p>
          ) : (
            <ul>
              {g.items.map(item => (
                <li key={item.url}>
                  <span className="codelookup-news-date">{item.publishedOn}</span>
                  <a href={item.url} target="_blank" rel="noopener noreferrer">{item.title}</a>
                  <span className="codelookup-news-source">
                    {item.source || item.originLabel}
                    {item.noticeCategory && ` · ${item.noticeCategory}`}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {/* 每组各自的说明：源回了几条、丢掉多少、为什么。**必须显示**——
              没有它，「本次取到 2 条」说不清是源里就少，还是被清洗掉了。 */}
          {g.note && <p className="codelookup-news-note">{g.note}</p>}
        </div>
      ))}
    </div>
  )
}

/**
 * 大模型解读。**手动触发、单独一次外呼、单独一个端点**。
 *
 * 不可用时（没配 key、模型超时）这一块换成「未启用」和一句**可操作的**说明，
 * 上面的判定卡、体检、新闻、人工核实清单原样都在——用户仍然拿到全部价值。
 * 失败原因原文照显，因为「没配 key」和「调用超时」的下一步完全不同。
 */
function AiBrief({ brief, onGenerate, loading, error }: {
  brief: BriefResult | null
  onGenerate: () => void
  loading: boolean
  error: string
}) {
  return (
    <div className="codelookup-ai">
      <div className="stockpick-actions">
        <button type="button" className="stockpick-run" disabled={loading} onClick={onGenerate}>
          {loading ? '正在生成…' : brief ? '重新生成解读' : '生成深度解读（会调用大模型）'}
        </button>
        <span className="stockpick-timing">
          {loading
            ? '正在重新取新闻、公告与政策，再交给大模型，通常 5–20 秒。这一段不自动重试。'
            : brief
              ? '同一份快照重复点击走进程内缓存，不会重复计费'
              : '手动触发：非流式、要花一次模型调用，生成的文字存在进程内，不落库'}
        </span>
      </div>

      {error && (
        <p className="codelookup-ai-error">解读没有生成：{error}</p>
      )}

      {brief && (
        <>
          <div className="codelookup-ai-head">
            <h4>{brief.brief.title}</h4>
            <span>
              基于 {brief.snapshotAt} 那一份数字
              {brief.fromCache ? '（进程内缓存）' : ''}
            </span>
          </div>
          {brief.brief.note && <p className="stockpick-note">{brief.brief.note}</p>}
          {/*
            失败原因原文**不当成解读正文**排版：它长得像结论，而实际是一句报错。
            `available=false` 时后端已经把原因放在 text 里，这里用等宽的引用块显示，
            视觉上就与下面的解读正文分开。
          */}
          {brief.brief.available ? (
            <MarketMarkdown>{brief.brief.text}</MarketMarkdown>
          ) : (
            <p className="codelookup-ai-failure">{brief.brief.text}</p>
          )}
          <p className="stockpick-note">
            这一段文字是<b>大模型的复述与整理</b>，数字与判定都来自上面的规则结果；
            <b>它不许推翻规则判定，也不许给目标价、买卖或仓位建议</b>。
            它自己标了「推断：」的句子是推断，其余是可复核的事实。
          </p>
        </>
      )}
    </div>
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

  // 深度分析是**另一次点击**，三态与主查询分开存：主查询成功而分析失败是最常见的
  // 一种组合（分析要多走四路外呼），共用一个 error 会让主查询的结果跟着消失。
  const [analysisLoading, setAnalysisLoading] = useState(false)
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null)
  const [analysisError, setAnalysisError] = useState('')
  const [analysisRateLimited, setAnalysisRateLimited] = useState('')
  const [briefLoading, setBriefLoading] = useState(false)
  const [brief, setBrief] = useState<BriefResult | null>(null)
  const [briefError, setBriefError] = useState('')

  const codeReady = CODE_SHAPE.test(input.trim())

  /**
   * 分析结果与解读**必须跟着一次新查询一起清掉**。
   * 不清的话，换一个代码查完，下面还挂着上一只标的的倾向与新闻——
   * 而两块都写着代码与快照时刻，那种页面比空着更难发现错。
   */
  const resetAnalysis = useCallback(() => {
    setAnalysis(null)
    setAnalysisError('')
    setAnalysisRateLimited('')
    setBrief(null)
    setBriefError('')
  }, [])

  const lookup = useCallback(async (raw: string) => {
    const code = raw.trim()
    if (!CODE_SHAPE.test(code)) return
    setLoading(true)
    setError('')
    setNotFound('')
    setRateLimited('')
    setResult(null)
    resetAnalysis()
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
  }, [resetAnalysis])

  /**
   * 拉深度分析。**与主查询逐档相同的错误码**：400 改代码、404 换代码、
   * 429 等且**绝不自动重试**、503 等一会儿再试同一个代码。
   *
   * 429 与别的失败分开存：它不是故障，处置方式也不同（等，而不是改输入）。
   */
  const runAnalysis = useCallback(async (code: string) => {
    setAnalysisLoading(true)
    setAnalysisError('')
    setAnalysisRateLimited('')
    // 解读是建在**某一份**分析结果上的，重新分析时旧的解读必须一起清掉：
    // 留着它，页面会同时出现「新判定」和「基于旧判定的解读」，而两者都带时刻。
    setBrief(null)
    setBriefError('')
    try {
      const res = await api.get(`/code-lookup/analysis?code=${encodeURIComponent(code)}`)
      const status = res.data?.code
      if (status === 200 && res.data.data) {
        setAnalysis(res.data.data as AnalysisResult)
      } else if (status === 429) {
        setAnalysisRateLimited(res.data?.message || '行情源正在限流，请稍后再试')
      } else {
        setAnalysisError(res.data?.message || '分析未返回结果')
      }
    } catch (e: unknown) {
      const err = e as { response?: { data?: { code?: number; message?: string } } }
      const status = err.response?.data?.code
      const message = err.response?.data?.message
      if (status === 429) setAnalysisRateLimited(message || '行情源正在限流，请稍后再试')
      else setAnalysisError(message || '分析请求失败，请稍后再试')
    } finally {
      setAnalysisLoading(false)
    }
  }, [])

  /**
   * 生成大模型解读。**单独一个按钮、单独一个端点**（章程 §9.7）。
   *
   * 与另外两次请求不同的是：模型自己失败**不走错误码**，后端返回一句
   * `AI ` 开头的文案、装成 `available=false` 的正常结果。所以这里 `error`
   * 只承接主查询那一层的失败（代码写错、源不可达），「没配 key」会走 `brief.brief.available`。
   */
  const runBrief = useCallback(async (code: string) => {
    setBriefLoading(true)
    setBriefError('')
    try {
      const res = await api.get(`/code-lookup/analysis/brief?code=${encodeURIComponent(code)}`)
      const status = res.data?.code
      if (status === 200 && res.data.data) {
        setBrief(res.data.data as BriefResult)
      } else if (status === 429) {
        setBriefError(res.data?.message || '行情源正在限流，请稍后再试')
      } else {
        setBriefError(res.data?.message || '解读未返回结果')
      }
    } catch (e: unknown) {
      const err = e as { response?: { data?: { code?: number; message?: string } } }
      setBriefError(err.response?.data?.message || '解读请求失败，请稍后再试')
    } finally {
      setBriefLoading(false)
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

  // 基本面那一格里「空着」的说法。**只说哪个源没给，不说为什么**——页面区分不了
  // 「这只标的本来就没有这一项」和「这个源这次没带上」，编一个听起来合理的解释
  // 比留白更坏，它会把用户引向错误的下一个动作（与行业、市值那两格同一条纪律）。
  const fMissing = result?.fundamentals.available
    ? `${result.fundamentals.providerLabel || '行情源'} 未提供该项`
    : '本次没有取到基本面字段'

  return (
    <div className="market-watch-page">
      <header className="market-watch-hero">
        <div>
          <div className="market-watch-kicker">Code Lookup</div>
          <h2>代码查询</h2>
          <p>
            输入一个代码，实时取它的<b>价格、PE 与 PE 分位</b>，回看距离到<b>十年</b>；
            再点一次「深度分析」，把它按<b>估值 / 成长 / 盈利质量 / 财务健康 / 资金面 / 技术面 / 动量</b>
            摆成判定卡，附<b>公告、新闻、政策</b>三类头条与一份<b>人工核实清单</b>。
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
            onClick={() => {
              setInput(''); setResult(null); setQueried('')
              setError(''); setNotFound(''); setRateLimited('')
              resetAnalysis()
            }}
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
                  format={yiFromYuan} />
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
              <h3>基本面与动量</h3>
              <span>
                {result.fundamentals.available
                  ? `与行情同源（${result.fundamentals.providerLabel || result.fundamentals.provider || '来源未标'}）· 未额外外呼`
                  : '本次没有取到这一块'}
              </span>
            </div>

            {result.fundamentals.available ? (
              <>
                {/*
                  这一块**一次外呼都没多花**：ROE、营收同比、净利同比、毛利率、资产负债率、
                  股息率、换手率、成交额、流通市值、60 日涨跌、年初至今，本来就是行情接口
                  同一次响应里的字段（后端 QUOTE_FIELDS），以前只是没端出来。
                */}
                <div className="stockpick-numbers">
                  <Cell label="ROE(加权)" value={result.fundamentals.roe} status={fMissing} format={v => `${num(v, 2)}%`} />
                  <Cell label="毛利率" value={result.fundamentals.grossMargin} status={fMissing} format={v => `${num(v, 2)}%`} />
                  <Cell label="资产负债率" value={result.fundamentals.debtRatio} status={fMissing} format={v => `${num(v, 2)}%`} />
                  <Cell label="每股净资产" value={result.fundamentals.bps} status={fMissing} format={v => num(v, 2)} />
                  <Cell label="营收同比" value={result.fundamentals.revenueGrowth} status={fMissing} format={signed} />
                  <Cell label="净利同比" value={result.fundamentals.profitGrowth} status={fMissing} format={signed} />
                  <Cell label="股息率" value={result.fundamentals.dividendYield} status={fMissing} format={v => `${num(v, 2)}%`} />
                  <Cell label="换手率" value={result.fundamentals.turnoverRate} status={fMissing} format={v => `${num(v, 2)}%`} />
                  <Cell label="成交额" value={result.fundamentals.amount} status={fMissing} format={yiFromYuan} />
                  <Cell label="流通市值" value={result.fundamentals.floatMarketCap} status={fMissing} format={yiFromYuan} />
                  <Cell label="60 日涨跌" value={result.fundamentals.change60d} status={fMissing} format={signed} />
                  <Cell label="年初至今" value={result.fundamentals.ytdChange} status={fMissing} format={signed} />
                  <Cell label="上市日期" value={result.fundamentals.listDate ? 1 : null} status={fMissing}
                    format={() => result.fundamentals.listDate || '—'} />
                </div>
                <p className="stockpick-note">
                  营收同比与净利同比<b>不带所属报告期</b>——这个源不返回它，所以无法确认是季报还是年报，
                  <b>不能与年报口径的数字直接比</b>。资产负债率<b>对银行是存款负债比</b>，
                  与制造业不是同一个口径。60 日涨跌与年初至今只作背景：<b>「跌了」不是便宜的理由</b>。
                </p>
              </>
            ) : result.fundamentals.notes.length > 0 ? (
              <ul className="codelookup-notes">
                {result.fundamentals.notes.map(n => <li key={n}><span>{n}</span></li>)}
              </ul>
            ) : (
              <p className="stockpick-gap">
                本次没有取到基本面字段，且后端没有给出原因——请反馈。
              </p>
            )}
          </section>

          {/*
            深度分析。**手动点、单独一个端点**，不跟查询自动跑：
            它比主查询多走四路外呼（公告 / 新闻 / 政策 + 大模型），
            挂进主链就等于每次刷新判定卡都买一次，还会把首屏从 1–3 秒拖到十几秒。
          */}
          <section className="market-watch-section">
            <div className="market-watch-section-header">
              <h3>深度分析</h3>
              <span>
                {analysis
                  ? `判定基于 ${analysis.snapshotAt} 那一份数字${analysis.fromCache ? '（进程内缓存）' : ''}`
                  : '按需触发，不自动跑'}
              </span>
            </div>

            {!analysis && (
              <>
                <div className="stockpick-actions">
                  <button
                    type="button"
                    className="stockpick-run"
                    disabled={analysisLoading}
                    onClick={() => runAnalysis(result.code)}
                  >
                    {analysisLoading ? '正在分析…' : '开始深度分析'}
                  </button>
                  <span className="stockpick-timing">
                    {analysisLoading
                      ? '正在取公告、新闻与政策，通常 3–10 秒。这一段不自动重试。'
                      : '会额外外呼四路：东财公告、东财资讯、政策源各一次。每路只发一次，不翻页、不重搜。'}
                  </span>
                </div>
                <p className="stockpick-note">
                  数字与判定都在这台机器上算，取的是上面那一份<b>同一快照</b>的行情
                  （5 分钟内再查同一代码不会重新外呼行情源）。这一栏回答的是
                  <b>「按公开数据摆出来看，现在是什么状态」</b>，
                  不给目标价、不给买卖指令、不给仓位建议。
                </p>
              </>
            )}

            {analysisRateLimited && (
              <section className="stockpick-throttled">
                <h3>行情源正在限流，本次没有开始分析</h3>
                <p>{analysisRateLimited}</p>
                <p className="stockpick-note">
                  深度分析比主查询多花四到五次外呼的额度，所以<b>更不该连着点</b>。
                  等上面说的时间过去再点一次即可。
                </p>
                <div className="stockpick-actions">
                  <button type="button" className="stockpick-run" disabled={analysisLoading}
                    onClick={() => runAnalysis(result.code)}>
                    {analysisLoading ? '正在重试…' : '再试一次'}
                  </button>
                </div>
              </section>
            )}

            {analysisError && (
              <section className="stockpick-error">
                <h3>本次没有取到分析</h3>
                <p>{analysisError}</p>
                <p className="stockpick-note">
                  上面主查询的结果不受影响——分析是单独一次请求，
                  它失败不会让价格与分位也跟着消失。
                </p>
              </section>
            )}

            {analysis && (
              <>
                {/*
                  口径声明**必显**：这里的阈值判定是绝对口径，与「低估精选」的
                  池内相对分位不是一回事。不写清，用户会拿两页的数字互相印证，必错。
                */}
                {analysis.notes.length > 0 && (
                  <ul className="codelookup-notes">
                    {analysis.notes.map(n => <li key={n}><span>{n}</span></li>)}
                  </ul>
                )}

                <TendencyBadge verdict={analysis.tendency} />

                <VerdictCards dimensions={analysis.dimensions} />

                <h4 className="codelookup-subhead">排雷体检</h4>
                <CheckupList checkup={analysis.checkup} />

                <h4 className="codelookup-subhead">新闻面（近 30 天）</h4>
                <NewsDigestList groups={analysis.news} />
                <p className="stockpick-note">
                  这里只列<b>标题、来源、日期与链接</b>：不摘正文、不做情感分析、不判断影响。
                  一条新闻算利好还是利空，取决于你的判断，这里不替你下结论。
                </p>

                <h4 className="codelookup-subhead">需要你人工核实</h4>
                {analysis.pendingChecks.length > 0 ? (
                  <>
                    <p className="stockpick-note">
                      下面每一条都是<b>问题</b>，不是结论。公开数据回答不了它们，
                      而它们往往才是决定要不要动手的那几件事。
                    </p>
                    <ul className="codelookup-manual">
                      {analysis.pendingChecks.map(q => <li key={q}>{q}</li>)}
                    </ul>
                  </>
                ) : (
                  <p className="stockpick-gap">本次没有需要人工核实的条目。</p>
                )}

                <h4 className="codelookup-subhead">大模型解读</h4>
                <AiBrief
                  brief={brief}
                  loading={briefLoading}
                  error={briefError}
                  onGenerate={() => runBrief(result.code)}
                />

                <p className="stockpick-disclaimer">{analysis.disclaimer}</p>
              </>
            )}
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
            {result.valuation.historyLength !== null
              && spansTenYears(result.valuation.historyFrom, result.valuation.historyTo) !== null && (
              <p className="stockpick-note">
                分位窗口是 <b>min(10 年, 该序列实际可用的长度)</b>
                {spansTenYears(result.valuation.historyFrom, result.valuation.historyTo)
                  ? '——这条序列够 10 年。'
                  : '——这条序列不足 10 年，所以「十年」那一档的窗口其实更短，上面已经标出来了。'}
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
              <span>这一页只回答「数字是多少、现在是什么状态」，不回答「该不该买、买多少」</span>
            </div>
            <ul className="stockpick-manual">
              <li><b>「倾向」不是买卖建议。</b>只有「偏可关注 / 需等待 / 风险偏高 / 数据不足」
                四个词，没有目标价、没有仓位、没有时点。它由上面那些阈值<b>机械算出</b>，
                换一组阈值结论就会变——所以你该看的是<b>理由</b>，不是那个词。</li>
              <li>不同来源的 PE 分位口径不同（实测中证自算与蛋卷能差 22%–75%），<b>不可横向比较</b>；
                这一页一次只给一个标的，所以每条都写着用的是哪个口径。</li>
              <li>深度分析的阈值是<b>绝对口径</b>，与「低估精选」按当日池子算的<b>池内相对分位
                不是一回事</b>，两页的数字不可互相印证。</li>
              <li>PE 分位低只表示相对自己历史便宜，不表示未来不会更便宜。</li>
              <li>个股的 PE 历史以估值分析源能提供的深度为限；不足 10 年时「十年」那一档会写明历史不足，
                不会拿序列里最老的一天顶上。</li>
              <li><b>新闻只列标题，不做判断。</b>一条公告是利好还是利空，取决于它怎么落地——
                那件事这一页不做，也不让大模型替你做。政策类头条的「列举」不是推断，对政策的
                <b>影响判断</b>这一页不给。</li>
              <li>缺数据就是缺数据：本页不用 0、也不用别的口径的数去补空着的格子；
                「未确认」「不适用」的格子<b>既不加分也不减分</b>，只会写明「未计入」。</li>
            </ul>
            <p className="stockpick-disclaimer">
              本页是公开数据的即时查询结果，仅供研究参考，不构成投资建议、证券推荐或买卖依据。
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