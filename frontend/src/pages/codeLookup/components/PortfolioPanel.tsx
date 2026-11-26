import { useEffect, useState, type FormEvent } from 'react'
import api from '../../../utils/api'
import { num, signedPct } from '../format'
import { applyFill, compareReturns, GROUPS, groupAmounts, isGoldFund, readPortfolio, suggestedEntry,
  type AssetGroup, type Portfolio } from '../portfolio'
import type { CoreView, PricePoint, SeriesView } from '../types'

function money(value: number) {
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) + ' 元'
}

function displayTranches(value: number, budget: number) {
  return budget > 0 ? num(value / budget * 10, 2) + ' 成' : '待设额度'
}

const CODE = /^\d{6}$/

export default function PortfolioPanel({ userId, core }: { userId: number; core: CoreView | null }) {
  const storageKey = `briefmind_etf_portfolio_v1_${userId}`
  const [portfolio, setPortfolio] = useState(() => readPortfolio(storageKey))
  const [planRevision, setPlanRevision] = useState(0)
  const [message, setMessage] = useState('')
  const [problem, setProblem] = useState('')

  useEffect(() => {
    function sync(event: StorageEvent) {
      if (event.key === storageKey || event.key === null) {
        setPortfolio(readPortfolio(storageKey))
        setPlanRevision(value => value + 1)
      }
    }
    window.addEventListener('storage', sync)
    return () => window.removeEventListener('storage', sync)
  }, [storageKey])

  function persist(update: (current: Portfolio) => Portfolio, success: string, settings = false) {
    setProblem('')
    setMessage('')
    try {
      const next = update(readPortfolio(storageKey))
      localStorage.setItem(storageKey, JSON.stringify(next))
      setPortfolio(next)
      if (settings) setPlanRevision(value => value + 1)
      setMessage(success)
      return true
    } catch (error) {
      setProblem(error instanceof Error ? error.message : '当前浏览器无法保存仓位')
      return false
    }
  }

  function savePlan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    persist(current => {
      const capital = Number(data.get('capital'))
      if (!Number.isFinite(capital) || capital <= 0 || !Number.isSafeInteger(Math.round(capital * 100))) {
        throw new Error('请输入有效的计划总资金')
      }
      const allocations = { ...current.allocations }
      const codes = new Set<string>()
      let total = 0
      for (const { key, label } of GROUPS) {
        const weight = Number(data.get(`${key}-weight`))
        const floor = Number(data.get(`${key}-floor`))
        const benchmark = String(data.get(`${key}-benchmark`) ?? '').trim()
        if (!(weight >= 0 && weight <= 100 && floor >= 1 && floor <= 10)) {
          throw new Error(`${label}的计划比例须在 0% 至 100%，底仓须在 1 至 10 成之间`)
        }
        if (benchmark && (!CODE.test(benchmark) || codes.has(benchmark))) {
          throw new Error(`${label}的代表 ETF 须是未被其他类别使用的六位代码`)
        }
        if (benchmark) codes.add(benchmark)
        total += Math.round(weight * 100)
        allocations[key] = { weight, floor, benchmark }
      }
      if (total > 10000) throw new Error('各类资产计划比例合计不能超过 100%')
      return { ...current, capital, allocations }
    }, '组合计划已保存', true)
  }

  const code = core?.code.match(/\d{6}$/)?.[0]
  return (
    <section className="cl-subcard cl-portfolio" aria-label="ETF 组合与仓位">
      <header><h3>ETF 组合与仓位</h3><span>仓位按各类计划额度计算</span></header>
      {core?.assetType === 'ETF' && code && (
        <CurrentEtf key={code} core={core} code={code} portfolio={portfolio} persist={persist} />
      )}
      {problem && <p className="cl-portfolio-error" role="alert">{problem}</p>}
      {message && <p role="status">{message}</p>}
      <details open={portfolio.capital === 0 || undefined}>
        <summary>组合计划与实际持仓</summary>
        <form key={planRevision} className="cl-portfolio-form" onSubmit={savePlan}>
          <label>计划总资金（元）
            <input name="capital" type="number" min="0.01" step="0.01" required defaultValue={portfolio.capital || ''} />
          </label>
          <div className="cl-table-wrap">
            <table className="cl-table">
              <thead><tr><th>类别</th><th>占总资金 %</th><th>底仓成数</th><th>代表 ETF</th><th>已用 / 计划（元）</th></tr></thead>
              <tbody>{GROUPS.map(({ key, label }) => {
                const plan = portfolio.allocations[key]
                const amounts = groupAmounts(portfolio, key)
                return <tr key={key}>
                  <th scope="row">{label}</th>
                  <td><input aria-label={label + '计划比例'} name={`${key}-weight`} type="number" min="0" max="100" step="0.01" required defaultValue={plan.weight} /></td>
                  <td><input aria-label={label + '底仓成数'} name={`${key}-floor`} type="number" min="1" max="10" step="0.1" required defaultValue={plan.floor} /></td>
                  <td><input aria-label={label + '代表 ETF'} name={`${key}-benchmark`} inputMode="numeric" maxLength={6} defaultValue={plan.benchmark} /></td>
                  <td>{num(amounts.used)} / {num(amounts.budget)}</td>
                </tr>
              })}</tbody>
            </table>
          </div>
          <div><button className="cl-btn cl-btn-primary" type="submit">保存组合计划</button></div>
        </form>
        {portfolio.holdings.length > 0 && (
          <ul className="cl-portfolio-holdings">
            {portfolio.holdings.map(item => <li key={item.code}>
              {item.name}（{item.code}） · {GROUPS.find(group => group.key === item.group)?.label}
              {' · '}{item.shares.toLocaleString('zh-CN')} 份 · 持仓成本 {money(item.cost)}
            </li>)}
          </ul>
        )}
        <p className="cl-note">已用额度按剩余持仓的移动平均成本计算。计划和实际持仓保存在当前浏览器的账户下。</p>
      </details>
      <CorrelationPanel key={GROUPS.map(({ key }) => portfolio.allocations[key].benchmark).join('-')} portfolio={portfolio} />
    </section>
  )
}

function CurrentEtf({ core, code, portfolio, persist }: {
  core: CoreView; code: string; portfolio: Portfolio
  persist: (update: (current: Portfolio) => Portfolio, success: string) => boolean
}) {
  const holding = portfolio.holdings.find(item => item.code === code)
  const inferred = isGoldFund(core) ? 'gold'
    : GROUPS.find(({ key }) => portfolio.allocations[key].benchmark === code)?.key
  const [choice, setChoice] = useState<AssetGroup | ''>('')
  const group = holding?.group || choice || inferred
  const amounts = group ? groupAmounts(portfolio, group) : null
  const configured = !!amounts && amounts.budget > 0
  const reference = group ? suggestedEntry(core, group, !!amounts && amounts.floor > 0
    && amounts.used >= amounts.floor) : null
  const buy = amounts && reference ? Math.floor(Math.min(amounts.budget * reference.tranches / 10,
    amounts.remaining, amounts.cash) * 100) / 100 : 0
  const highPosition = core.etfResearch?.gold
    ? (core.technical?.pricePercentile1y ?? 0) >= 80
    : core.decision?.position.key === 'HIGH'
  const reduce = highPosition && (core.technical?.momentum20d ?? 0) < 0
    && !core.verdict.hardFlags?.includes('PRICE_STALE')
    && holding && amounts && amounts.budget > 0
    ? Math.min(holding.cost, amounts.wave, amounts.budget / 10) : 0

  function record(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!group) return
    const form = event.currentTarget
    const data = new FormData(form)
    const sell = data.get('side') === 'sell'
    if (persist(current => applyFill(current, { code, name: core.name || code, group,
      side: sell ? 'sell' : 'buy', shares: Number(data.get('shares')), price: Number(data.get('price')),
    }), '已按实际成交更新持仓')) form.reset()
  }

  return (
    <div className="cl-portfolio-current">
      <label>本 ETF 的资产类别
        <select value={group || ''} disabled={!!holding} onChange={event => setChoice(event.target.value as AssetGroup)}>
          <option value="">选择类别</option>
          {GROUPS.map(({ key, label }) => <option key={key} value={key}>{label}</option>)}
        </select>
      </label>
      <div className="cl-portfolio-reference">
        <h4>进场与仓位参考</h4>
        {reference && amounts ? (
          <>
            <b>{!configured ? (reference.tranches > 0 ? '具备分批进场条件' : '观望') : buy > 0
              ? (holding ? '可评估分批加仓' : '可评估分批进场') : '观望 · 新增 0 成'}</b>
            <p>{reference.reason}{configured && reference.tranches > 0 && buy === 0 ? '。可用计划额度已用完' : '。'}</p>
            {!configured && <p>保存该类计划额度后可计算单笔金额。规则参考上限 {reference.tranches} 成。</p>}
            {configured && <div className="cl-numbers">
              <div><label>本次新增参考</label><b>{displayTranches(buy, amounts.budget)}</b><p>{money(buy)} · 总资金 {num(buy / portfolio.capital * 100)}%</p></div>
              <div><label>按参考买入后</label><b>{displayTranches(amounts.used + buy, amounts.budget)}</b><p>剩余 {displayTranches(Math.max(0, amounts.remaining - buy), amounts.budget)}</p></div>
              <div><label>计划底仓 / 已持波段</label><b>{displayTranches(amounts.floor, amounts.budget)} / {displayTranches(amounts.wave, amounts.budget)}</b><p>按该类合计持仓成本</p></div>
            </div>}
            {configured && amounts.used > amounts.budget && <p>该类实际持仓成本已超过计划额度。</p>}
            {reduce > 0 && amounts && <p>位置偏高且短期走弱，可评估减少波段仓。按持仓成本计，本次参考上限 {displayTranches(reduce, amounts.budget)}，约 {money(reduce)}。</p>}
          </>
        ) : <p>请选择该 ETF 的资产类别，以计算参考仓位。</p>}
      </div>
      <details>
        <summary>录入实际成交</summary>
        <p>{holding ? `当前录入 ${holding.shares.toLocaleString('zh-CN')} 份，剩余持仓成本 ${money(holding.cost)}` : '当前没有录入这只 ETF 的持仓。'}</p>
        <form className="cl-portfolio-fill" onSubmit={record}>
          <label>方向<select name="side"><option value="buy">买入</option><option value="sell" disabled={!holding}>卖出</option></select></label>
          <label>成交份额<input name="shares" type="number" min="1" step="1" required /></label>
          <label>实际成交价（元）<input name="price" type="number" min="0.0001" step="any" required /></label>
          <button className="cl-btn" type="submit" disabled={!group}>保存实际成交</button>
        </form>
      </details>
    </div>
  )
}

function CorrelationPanel({ portfolio }: { portfolio: Portfolio }) {
  const [days, setDays] = useState(60)
  const [loading, setLoading] = useState(false)
  const [series, setSeries] = useState<Record<string, PricePoint[]> | null>(null)
  const [failed, setFailed] = useState<string[]>([])
  const chosen = GROUPS.flatMap(({ key, label }) => {
    const code = portfolio.allocations[key].benchmark
    return code ? [{ code, label, key }] : []
  })

  async function load() {
    setLoading(true)
    setSeries(null)
    const results = await Promise.allSettled(chosen.map(async ({ code }) => {
      const response = await api.get('/code-lookup/v2/series',
        { params: { code, kind: 'price', range: '1y', interval: '1d' } })
      const data = response.data?.data as SeriesView | undefined
      if (response.data?.code !== 200 || data?.interval !== '1d' || !data.price.length) throw new Error(code)
      return [code, data.price] as const
    }))
    setFailed(results.flatMap((item, index) => item.status === 'rejected' ? [chosen[index].label] : []))
    setSeries(Object.fromEntries(results.flatMap(item => item.status === 'fulfilled' ? [item.value] : [])))
    setLoading(false)
  }

  return (
    <details>
      <summary>资产联动</summary>
      <p className="cl-note">为两类或更多资产填入代表 ETF。按已收盘的同日期日收益计算，单独统计第二只下跌时两只同跌的次数。</p>
      <div className="cl-portfolio-fill">
        <label>交易日窗口<select value={days} onChange={event => setDays(Number(event.target.value))}>
          <option value={60}>60 日</option><option value={120}>120 日</option>
        </select></label>
        <button type="button" className="cl-btn" onClick={load} disabled={loading || chosen.length < 2}>
          {loading ? '正在读取日线' : '计算联动'}
        </button>
      </div>
      {failed.length > 0 && <p className="cl-note">这些代表 ETF 的本地日线暂不可用：{failed.join('、')}。</p>}
      {series && <div className="cl-table-wrap">
        <table className="cl-table">
          <thead><tr><th>资产对</th><th>相关系数</th><th>前者相对后者</th><th>同跌 / 后者跌</th><th>观察期</th></tr></thead>
          <tbody>{chosen.flatMap((first, index) => chosen.slice(index + 1).map(second => {
            const result = compareReturns(series[first.code] || [], series[second.code] || [], days)
            return <tr key={first.key + second.key}>
              <th scope="row">{first.label} / {second.label}</th>
              <td>{result ? num(result.correlation) : '样本不足'}</td>
              <td>{result ? `${num(result.relative)} 个百分点` : '—'}</td>
              <td>{result ? `${result.bothDown} / ${result.down}` : '—'}</td>
              <td>{result ? `${result.from} 至 ${result.to}` : '—'}</td>
            </tr>
          }))}</tbody>
        </table>
      </div>}
    </details>
  )
}
