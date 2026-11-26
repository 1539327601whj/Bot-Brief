import type { CoreView } from '../types'
import { num, signedPct } from '../format'

export default function EtfResearchCard({ core }: { core: CoreView }) {
  const research = core.etfResearch
  if (!research) return null
  if (research.gold) {
    const macro = research.gold.macro
    const treasury = macro?.realTreasury
    return (
      <section className="cl-subcard">
        <h3>黄金 ETF 的位置和宏观对照</h3>
        <p>{research.gold.lowPrice === null ? '日线不足或过旧'
          : research.gold.lowPrice ? '已进入近一年价格区间低位' : '近一年区间低位未触发'}</p>
        <p>20 日涨跌 {signedPct(core.technical?.momentum20d)} · 60 日涨跌 {signedPct(core.technical?.momentum60d)}</p>
        {macro ? (
          <div className="cl-numbers">
            <div><label>黄金 ETF 近 20 个交易日</label><b>{signedPct(macro.etfReturnPct)}</b><p>{macro.etfFrom} 至 {macro.etfTo}</p></div>
            <div><label>美国 10 年期实际收益率</label><b>{treasury ? `${num(treasury.basisPoints, 0)} 基点` : '—'}</b><p>{treasury && `${treasury.from} 至 ${treasury.to}`}</p></div>
            <div><label>广义美元</label><b>{signedPct(macro.dollar?.returnPct)}</b><p>{macro.dollar && `${macro.dollar.from} 至 ${macro.dollar.to}`}</p></div>
            <div><label>美元兑人民币</label><b>{signedPct(macro.usdCny?.returnPct)}</b><p>{macro.usdCny && `${macro.usdCny.from} 至 ${macro.usdCny.to}`}</p></div>
          </div>
        ) : <p className="cl-note">宏观对照需要近期同源收盘日线。</p>}
        <h4>后续观察条件</h4>
        <ul>
          <li>企稳观察。价格站上 20 日均线、20 日涨跌回到非负时，重新评估低位进场。</li>
          <li>弱势延续。若价格再度跌破近 20 日低点，同时美元和实际利率走高，继续观察下行风险。</li>
          <li>宏观方向分化。对照美元兑人民币变化与基金成交价，观察人民币计价黄金的实际表现。</li>
        </ul>
        <p className="cl-note">宏观数据来自 FRED。实际利率、美元和汇率的变化用于核对黄金 ETF 的走势。</p>
      </section>
    )
  }
  const { earnings, volume, usRates, benchmark } = research
  const earningsLabel = earnings.state === 'SUPPORTED' ? '成分股样本盈利有支撑'
    : earnings.state === 'WEAK_OR_MIXED' ? '成分股盈利表现分化或走弱' : '成分股盈利待确认'
  const priceLabel = research.lowPrice === null ? '日线不足或过旧'
    : research.lowPrice ? '触发近一年区间低位条件' : '近一年区间低位条件未触发'
  const amount = volume?.amount ?? null
  const turnover = volume?.turnoverRate ?? null

  return (
    <section className="cl-decision" aria-label="ETF 价格和盈利证据">
      <header className="cl-decision-head">
        <h3>ETF 价格和盈利证据</h3>
        <p className="cl-decision-summary">{priceLabel} · {earningsLabel}</p>
      </header>
      <p className="cl-note">区间低位按近一年收盘价区间下方 30% 且距高点至少 10% 判断。</p>
      <div className="cl-decision-grid">
        <div className="cl-decision-cell cl-tone-muted">
          <label>成分股营收同比</label>
          <b>{signedPct(earnings.revenueYoy, 1)}</b>
          <p>{earnings.reportDate || earnings.note}</p>
        </div>
        <div className="cl-decision-cell cl-tone-muted">
          <label>成分股归母净利润同比</label>
          <b>{signedPct(earnings.profitYoy, 1)}</b>
          <p>{earnings.sampleWeight !== null
            ? `${earnings.companies} 只 · 覆盖指数权重 ${num(earnings.sampleWeight, 1)}%`
            : '暂无可计算的权重覆盖'}</p>
        </div>
        <div className="cl-decision-cell cl-tone-muted">
          <label>收盘成交量 / 此前 20 日均量</label>
          <b>{volume?.volumeVsPrevious20 == null ? '—' : `${num(volume.volumeVsPrevious20)} 倍`}</b>
          <p>{volume ? `${volume.date} · 收盘涨跌 ${signedPct(volume.closeChange)} · ${volume.source ?? '来源未知'}`
            : '需要近期收盘日线'}</p>
        </div>
        <div className="cl-decision-cell cl-tone-muted">
          <label>日线成交额 / 换手率</label>
          <b>{amount === null ? '—' : `${num(amount / 100000000)} 亿`} / {turnover === null ? '—' : `${num(turnover)}%`}</b>
          <p>{core.quote?.turnoverRate != null && turnover === null
            ? `行情换手率 ${num(core.quote.turnoverRate)}% · ${core.quote.asOf ?? '日期未知'}`
            : '按收盘日线口径'}</p>
        </div>
      </div>
      <p className="cl-note">{earnings.note}{earnings.holdingsDate && `。指数权重日期 ${earnings.holdingsDate}`}。</p>
      <p className="cl-note">{earnings.growth
        ? `同一成分股样本三年年化营收 ${signedPct(earnings.growth.revenueCagr, 1)}、净利润 ${signedPct(earnings.growth.profitCagr, 1)}。覆盖指数权重 ${num(earnings.growth.sampleWeight, 1)}%，报告期 ${earnings.growth.from} 至 ${earnings.growth.to}。`
        : '三年经营趋势待确认，需有同一组成分股的可比财报。'}</p>
      {core.technical && (
        <p className="cl-note">20 日涨跌 {signedPct(core.technical.momentum20d)} · 60 日涨跌 {signedPct(core.technical.momentum60d)}。持续下跌时，单日放量仍需后续价格确认。</p>
      )}
      {benchmark && (
        <p className="cl-note">
          {benchmark.from} 至 {benchmark.to}，本 ETF 涨跌 {signedPct(benchmark.etfReturnPct)}，
          同期{benchmark.label}（{benchmark.code}）{signedPct(benchmark.benchmarkReturnPct)}，
          收益差 {benchmark.relativePctPoints > 0 ? '+' : ''}{num(benchmark.relativePctPoints)} 个百分点。
          {benchmark.etfReturnPct < 0 && benchmark.benchmarkReturnPct < 0
            ? '两者同期下跌，可继续核对市场共振与行业特有因素。'
            : benchmark.etfReturnPct < 0 && benchmark.relativePctPoints < 0
              ? '该 ETF 逆基准下跌，可优先复核行业和基金自身因素。'
              : ''}
        </p>
      )}
      {usRates && (
        <div className="cl-decision-grid">
          <div className="cl-decision-cell cl-tone-muted">
            <label>ETF 近 20 个交易日涨跌</label>
            <b>{signedPct(usRates.etfReturnPct)}</b>
            <p>{usRates.etfFrom} 至 {usRates.etfTo}</p>
          </div>
          <div className="cl-decision-cell cl-tone-muted">
            <label>联邦基金目标区间上限变化</label>
            <b>{usRates.policy ? `${num(usRates.policy.basisPoints, 0)} 基点` : '—'}</b>
            <p>{usRates.policy ? `${usRates.policy.from} 至 ${usRates.policy.to}` : '缺少有效观测'}</p>
          </div>
          <div className="cl-decision-cell cl-tone-muted">
            <label>美国 10 年期国债收益率变化</label>
            <b>{usRates.treasury ? `${num(usRates.treasury.basisPoints, 0)} 基点` : '—'}</b>
            <p>{usRates.treasury ? `${usRates.treasury.from} 至 ${usRates.treasury.to}` : '缺少有效观测'}</p>
          </div>
        </div>
      )}
      <p className="cl-note">{usRates ? '美国利率来自 FRED。利率与 ETF 同期变化只提供归因线索，仍需核对成分股、汇率和溢价。'
        : '下跌归因待验证。判断利率、情绪或盈利变化的影响，需要对照事件日期、同类指数和财报。'}</p>
    </section>
  )
}
