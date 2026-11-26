import type {
  CoreView,
  DetailView,
  DimensionView,
  FundProfileView,
  IndexProfileView,
  IndustryView,
  MoneyFlowPoint,
  PricePoint,
  SeriesView,
  ValuationPoint,
} from '../types'
import {
  dateOnly,
  dateTime,
  domainStatusText,
  eventCategoryLabel,
  num,
  polarityTone,
  ratioPct,
  sectionNote,
  signedPct,
  yi,
} from '../format'
import { domainLabel } from './DataQualityBanner'
import { eventReaction } from '../portfolio'
import { FinancialTrendChart, IndustryDonut, MoneyFlowChart, ValuationChart } from './Charts'

function SectionStatus({ status, note }: { status: string; note: string | null }) {
  return (
    <p className={`cl-section-status cl-status-${status.toLowerCase()}`}>
      <b>{domainStatusText(status)}</b>
      {note ? ` · ${note}` : ''}
    </p>
  )
}

function EmptySection({ status, note, text }: { status: string; note: string | null; text?: string }) {
  return (
    <>
      <SectionStatus status={status} note={note} />
      <p className="cl-empty">{text || sectionNote(note, status)}</p>
    </>
  )
}

// ---------------------------------------------------------------------
// 财务
// ---------------------------------------------------------------------

export function FinancialTab({ detail }: { detail: DetailView | null }) {
  if (!detail) return <p className="cl-empty">明细还在加载。</p>
  const section = detail.financials
  if (section.status !== 'OK' && section.data.length === 0) {
    return (
      <EmptySection status={section.status} note={section.note}
        text={section.note || '没有财务报表数据。后台可能仍在同步，或该标的类型不适用。'} />
    )
  }

  const rows = [...section.data].sort((a, b) => b.reportDate.localeCompare(a.reportDate))

  return (
    <div className="cl-stack">
      <SectionStatus status={section.status} note={section.note} />
      <FinancialTrendChart reports={section.data} />
      <div className="cl-table-wrap">
        <table className="cl-table cl-table-dense">
          <thead>
            <tr>
              <th>报告期</th>
              <th>营收</th>
              <th>营收同比</th>
              <th>净利</th>
              <th>净利同比</th>
              <th>ROE</th>
              <th>毛利率</th>
              <th>经营现金流</th>
              <th>资产负债率</th>
              <th>来源</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(row => (
              <tr key={`${row.reportDate}-${row.reportType}`}>
                <td><b>{row.reportDate}</b>{row.reportType ? ` · ${row.reportType}` : ''}</td>
                <td>{yi(row.revenue)}</td>
                <td className={tone(row.revenueYoy)}>{signedPct(row.revenueYoy)}</td>
                <td>{yi(row.netProfit)}</td>
                <td className={tone(row.netProfitYoy)}>{signedPct(row.netProfitYoy)}</td>
                <td>{row.roe === null ? '—' : `${num(row.roe, 2)}%`}</td>
                <td>{row.grossMargin === null ? '—' : `${num(row.grossMargin, 2)}%`}</td>
                <td>{yi(row.ocf)}</td>
                <td>{debtRatio(row.totalLiabilities, row.totalAssets)}</td>
                <td>{row.source || '未标'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="cl-note">
        财务数据按<b>报告期</b>展示，不按公告日排序；判断历史结论时应以当时已公告的财报为准。
        空着的格子表示该源没有提供，不是 0。
      </p>
    </div>
  )
}

function debtRatio(liabilities: number | null, assets: number | null): string {
  if (liabilities === null || assets === null || Number(assets) === 0) return '—'
  return `${(Number(liabilities) / Number(assets) * 100).toFixed(2)}%`
}

function tone(value: number | null): string | undefined {
  if (value === null) return undefined
  return Number(value) > 0 ? 'cl-up' : Number(value) < 0 ? 'cl-down' : undefined
}

// ---------------------------------------------------------------------
// 估值
// ---------------------------------------------------------------------

export function ValuationTab({ core, points, loading, note, range, onRange }: {
  core: CoreView
  points: ValuationPoint[]
  loading: boolean
  note: string | null
  range: string
  onRange: (range: string) => void
}) {
  const v = core.valuation
  return (
    <div className="cl-stack">
      <ValuationChart points={points} loading={loading} note={note} range={range} onRange={onRange} />
      {!v ? (
        <p className="cl-empty">本次没有估值视图。</p>
      ) : (
        <>
          <div className="cl-numbers">
            <NumberCell label="PE(TTM)" value={v.peTtm === null ? '—' : num(v.peTtm, 2)} />
            <NumberCell label="PE 分位" value={v.pePercentile === null ? '—' : `${num(v.pePercentile, 2)}%`} />
            <NumberCell label="PB(MRQ)" value={v.pbMrq === null ? '—' : num(v.pbMrq, 2)} />
            <NumberCell label="PB 分位" value={v.pbPercentile === null ? '—' : `${num(v.pbPercentile, 2)}%`} />
            <NumberCell label="股息率" value={v.dividendYield === null ? '—' : `${num(v.dividendYield, 2)}%`} />
            <NumberCell label="历史点数" value={v.pePoints ? `${v.pePoints} 点` : '—'} />
            <NumberCell label="数据日" value={v.asOf || '—'} />
            <NumberCell label="来源" value={v.sourceLabel || v.source || '未标'} />
          </div>
          <p className="cl-note">
            分位窗口：<b>{v.peWindowFrom || '起点未标'} 至今</b>。
            {v.source === 'eastmoney-valueanalysis' || (v.sourceLabel || '').includes('东财')
              ? '东财估值分析源的历史从 2018 年起，因此这里不是完整的十年分位。'
              : ''}
            {v.method ? ` 方法：${v.method}。` : ''}
            {' '}PE 分位低只表示相对自己的历史便宜，不表示未来不会更便宜。
          </p>
        </>
      )}
      {core.buyZone && (
        <div className="cl-subcard">
          <h4>参考区间的估值依据</h4>
          {core.buyZone.basis.length === 0 ? (
            <p className="cl-muted">本次没有给出区间依据。</p>
          ) : (
            <ul>{core.buyZone.basis.map(item => <li key={item.text}>{item.text}</li>)}</ul>
          )}
          {core.buyZone.notes.length > 0 && (
            <ul className="cl-note-list">{core.buyZone.notes.map(noteText => <li key={noteText}>{noteText}</li>)}</ul>
          )}
        </div>
      )}
    </div>
  )
}

function NumberCell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <label>{label}</label>
      <b>{value}</b>
    </div>
  )
}

// ---------------------------------------------------------------------
// 技术资金
// ---------------------------------------------------------------------

export function TechnicalTab({ core, moneyflow, moneyflowLoading, moneyflowNote }: {
  core: CoreView
  moneyflow: MoneyFlowPoint[]
  moneyflowLoading: boolean
  moneyflowNote: string | null
}) {
  const t = core.technical
  return (
    <div className="cl-stack">
      {!t || t.barCount === 0 ? (
        <p className="cl-empty">本次没有可用的日线，技术指标无法计算。</p>
      ) : (
        <div className="cl-numbers">
          <NumberCell label="日线根数" value={`${t.barCount} 根`} />
          <NumberCell label="最后交易日" value={t.lastDate || '—'} />
          <NumberCell label="MA20" value={num(t.ma20, 3)} />
          <NumberCell label="MA60" value={num(t.ma60, 3)} />
          <NumberCell label="MA120" value={num(t.ma120, 3)} />
          <NumberCell label="MA250" value={num(t.ma250, 3)} />
          <NumberCell label="ATR14" value={num(t.atr14, 3)} />
          <NumberCell label="1 年价格分位" value={t.pricePercentile1y === null ? '—' : `${num(t.pricePercentile1y, 1)}%`} />
          <NumberCell label="20 日动量" value={signedPct(t.momentum20d)} />
          <NumberCell label="60 日动量" value={signedPct(t.momentum60d)} />
          <NumberCell label="1 年最高" value={num(t.high1y, 3)} />
          <NumberCell label="1 年最低" value={num(t.low1y, 3)} />
          <NumberCell label="20 日低点" value={num(t.low20, 3)} />
          <NumberCell label="60 日低点" value={num(t.low60, 3)} />
          <NumberCell label="120 日低点" value={num(t.low120, 3)} />
          <NumberCell label="含 OHLC" value={t.hasOhlc ? '是' : '否（只有收盘价）'} />
        </div>
      )}
      <MoneyFlowChart points={moneyflow} loading={moneyflowLoading} note={moneyflowNote} />
      <p className="cl-note">
        技术指标只描述价格状态，不构成方向判断；均线与支撑位是参考买入区间的输入之一。
      </p>
    </div>
  )
}

// ---------------------------------------------------------------------
// 新闻政策
// ---------------------------------------------------------------------

export function EventsTab({ core, detail, price }: { core: CoreView; detail: DetailView | null; price: PricePoint[] }) {
  if (!detail) return <p className="cl-empty">明细还在加载。</p>
  const section = detail.events
  const name = `${core.name ?? ''} ${core.industry ?? ''} ${core.trackingIndexCode ?? ''}`
  const topics: Record<string, RegExp> = {
    '科技ETF': /科技|半导体|芯片|人工智能|纳指|NDX/,
    '创业板': /创业板/,
    '沪深300': /沪深\s*300|SH000300/,
    '红利ETF': /红利|股息/,
    '黄金ETF': /黄金|上海金/,
  }
  const events = section.data.map(event => {
    const topic = event.eventType?.startsWith('ETF:') ? event.eventType.slice(4) : null
    const direct = event.instrumentCode != null && event.instrumentCode.endsWith(`:${detail.code}`)
    const related = direct || (topic != null && (topic === '美联储议息' || topics[topic]?.test(name)))
    return { event, topic, related, direct }
  }).sort((first, second) => Number(second.direct) - Number(first.direct)
    || Number(second.related) - Number(first.related))
  return (
    <div className="cl-stack">
      <SectionStatus status={section.status} note={section.note} />
      <p className="cl-note">事件反应以前一个收盘价为基准，比较发布后首个完整交易日和前三个完整交易日。日内发布或发布时间不明确时，从下一交易日算起。</p>
      {section.data.length === 0 ? (
        <p className="cl-empty">
          {section.status === 'OK'
            ? '源正常返回，这段时间内没有可列出的条目。'
            : sectionNote(section.note, section.status)}
        </p>
      ) : (
        <ul className="cl-events">
          {events.map(({ event, topic, related }, index) => {
            const reaction = related ? eventReaction(event, price) : null
            return (
            <li key={`${event.url || event.title}-${index}`}>
              <div className="cl-event-head">
                <span className="cl-chip">{eventCategoryLabel(event.category)}</span>
                {event.polarity && (
                  <span className={`cl-chip cl-tone-${polarityTone(event.polarity)}`}>
                    {event.polarity}
                  </span>
                )}
                {event.importance && <span className="cl-chip">{event.importance}</span>}
                {topic && <span className="cl-chip">关注 {topic}</span>}
                <span className="cl-event-date">{dateTime(event.publishedAt)}</span>
              </div>
              {event.url ? (
                <a href={event.url} target="_blank" rel="noopener noreferrer">{event.title}</a>
              ) : (
                <b>{event.title}</b>
              )}
              {event.summary && <p>{event.summary}</p>}
              {reaction ? <p className="cl-event-source">
                本标的同期反应 · {reaction.firstDate} {signedPct(reaction.first)}
                {reaction.thirdDate ? ` · 截至 ${reaction.thirdDate} 累计 ${signedPct(reaction.third)}` : ' · 前三日样本尚未齐'}
              </p> : related ? <p className="cl-event-source">暂无可对齐的收盘价。</p>
                : <p className="cl-event-source">组合背景资讯。</p>}
              <span className="cl-event-source">{event.source || '来源未标'}{event.eventType && !topic ? ` · ${event.eventType}` : ''}</span>
            </li>
            )
          })}
        </ul>
      )}
      <p className="cl-note">
        行情变化用于核对新闻与市场反应的时间顺序。若要判断下跌原因，还需看同期基准和后续财报。
      </p>
    </div>
  )
}

// ---------------------------------------------------------------------
// 同行 / 指数画像
// ---------------------------------------------------------------------

export function PeersTab({ core, detail, industry }: {
  core: CoreView
  detail: DetailView | null
  industry: IndustryView[]
}) {
  const peers = core.dimensions.find(dimension => dimension.key === 'peers')
  return (
    <div className="cl-stack">
      {peers ? <DimensionDetail dimension={peers} /> : (
        <p className="cl-empty">该标的类型没有同行对比维度。</p>
      )}
      <IndustryDonut items={industry} />
      {detail?.indexProfile.data && <IndexProfileCard profile={detail.indexProfile.data} status={detail.indexProfile.status} note={detail.indexProfile.note} />}
      {!detail?.indexProfile.data && detail && (
        <EmptySection status={detail.indexProfile.status} note={detail.indexProfile.note}
          text={core.assetType === 'STOCK' ? '个股没有指数画像。' : '本地还没有指数画像（成分数 / 前十大权重 / 指数 PE）。'} />
      )}
      <p className="cl-note">
        同行对比按<b>同行业市值靠前的样本</b>计算；样本不足 8 只时该维度判为不适用，
        页面不会用更小的样本硬算一个分位。
      </p>
    </div>
  )
}

function DimensionDetail({ dimension }: { dimension: DimensionView }) {
  return (
    <div className="cl-subcard">
      <header>
        <h4>{dimension.label}</h4>
        <span>得分 {dimension.score === null ? '未评分' : num(dimension.score, 1)} · 权重 {dimension.weight} · 覆盖 {ratioPct(dimension.coverage)}</span>
      </header>
      {dimension.metrics.length === 0 ? (
        <p className="cl-muted">这一维没有可展示的子指标。</p>
      ) : (
        <ul className="cl-metric-list">
          {dimension.metrics.map(metric => (
            <li key={metric.key}>
              <div className="cl-metric-head"><b>{metric.label}</b><span>{metric.display ?? '未获取'}</span></div>
              {metric.basis && <p className="cl-metric-basis">{metric.basis}</p>}
            </li>
          ))}
        </ul>
      )}
      {dimension.reasons.length > 0 && <ul>{dimension.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>}
    </div>
  )
}

function IndexProfileCard({ profile, status, note }: {
  profile: IndexProfileView
  status: string
  note: string | null
}) {
  return (
    <div className="cl-subcard">
      <header><h4>指数画像 · {profile.name || profile.indexCode}</h4><span>{profile.asOf || '日期未标'}</span></header>
      <SectionStatus status={status} note={note} />
      <div className="cl-numbers">
        <NumberCell label="指数代码" value={profile.indexCode} />
        <NumberCell label="成分数量" value={profile.constituentCount === null ? '—' : `${profile.constituentCount} 只`} />
        <NumberCell label="前十大权重" value={profile.top10Weight === null ? '—' : `${num(profile.top10Weight, 2)}%`} />
        <NumberCell label="指数 PE" value={num(profile.peTtm, 2)} />
        <NumberCell label="股息率" value={profile.dividendYield === null ? '—' : `${num(profile.dividendYield, 2)}%`} />
        <NumberCell label="总市值" value={yi(profile.totalMarketCap)} />
        <NumberCell label="来源" value={profile.source || '未标'} />
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------
// 基金档案（ETF）
// ---------------------------------------------------------------------

/**
 * ETF 的基金层面事实。所有行都是「有值才渲染」：档案页缺哪一项就不显示哪一行，
 * 不放一排「—」占位——那会让「源没有」看起来像「这一项是空的」。
 */
export function FundProfileCard({ section }: {
  section: { data: FundProfileView | null; status: string; note: string | null; asOf: string | null }
}) {
  const p = section.data
  if (!p) return null

  const rows: Array<[string, string | null]> = [
    ['基金全称', p.fullName],
    ['基金类型', p.fundType],
    ['管理人', p.company],
    ['托管人', p.custodian],
    ['基金经理', p.manager],
    ['成立日期', p.inceptionDate ? dateOnly(p.inceptionDate) : null],
    ['成立规模', p.inceptionScale],
    ['最新规模', p.aum === null ? null
      : `${yi(p.aum)}${p.aumAsOf ? `（截止 ${dateOnly(p.aumAsOf)}）` : ''}`],
    ['管理费率', p.managementFeePct === null ? null : `${num(p.managementFeePct, 2)}%`],
    ['托管费率', p.custodyFeePct === null ? null : `${num(p.custodyFeePct, 2)}%`],
    ['跟踪标的', p.trackingTarget],
    ['业绩比较基准', p.benchmark],
    ['年化跟踪误差', p.trackingErrorPct === null ? null
      : `${num(p.trackingErrorPct, 2)}%`
      + (p.peerTrackingErrorPct === null ? '' : `（同类平均 ${num(p.peerTrackingErrorPct, 2)}%）`)
      + (p.trackingErrorAsOf ? `，截止 ${dateOnly(p.trackingErrorAsOf)}` : '')],
  ]
  const visible = rows.filter(([, value]) => value !== null && value !== '')

  return (
    <div className="cl-subcard cl-fund-profile">
      <header>
        <h4>基金档案 · {p.name || p.code}</h4>
        <span>{p.source || '天天基金'}</span>
      </header>
      <SectionStatus status={section.status} note={section.note} />
      {visible.length === 0 ? (
        <p className="cl-empty">{sectionNote(section.note, section.status)}</p>
      ) : (
        <dl>
          {visible.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
      <p className="cl-note">
        「最新规模」是基金<b>净资产</b>（季报口径），与行情页按价格估算的总市值不是同一个数。
        跟踪误差越小，基金越贴近它跟踪的指数。
      </p>
    </div>
  )
}

// ---------------------------------------------------------------------
// 口径
// ---------------------------------------------------------------------

export function ProvenanceTab({ core, series, detail }: {
  core: CoreView
  series: Record<string, SeriesView>
  detail: DetailView | null
}) {
  const domains = Object.entries(core.data)
  const missing = domains.filter(([, view]) => view.status === 'EMPTY' || view.status === 'FAILED' || view.status === 'NOT_APPLICABLE')
  return (
    <div className="cl-stack">
      <div className="cl-subcard">
        <h4>数据快照</h4>
        <div className="cl-numbers">
          <NumberCell label="快照 ID" value={core.snapshotId} />
          <NumberCell label="快照时间" value={dateTime(core.snapshotAt)} />
          <NumberCell label="规则版本" value={core.rulesVersion} />
          <NumberCell label="标的类型" value={core.assetType} />
          <NumberCell label="市场" value={core.market || '—'} />
          <NumberCell label="跟踪指数" value={core.trackingIndexCode || '—'} />
        </div>
      </div>

      <div className="cl-subcard">
        <h4>各数据域状态</h4>
        <div className="cl-table-wrap">
          <table className="cl-table">
            <thead><tr><th>数据域</th><th>状态</th><th>数据日</th><th>说明</th></tr></thead>
            <tbody>
              {domains.map(([key, view]) => (
                <tr key={key}>
                  <td>{domainLabel(key)}</td>
                  <td className={`cl-status-${view.status.toLowerCase()}`}>{domainStatusText(view.status)}</td>
                  <td>{view.asOf || '—'}</td>
                  <td className="cl-table-status">{view.note || ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {core.notes.length > 0 && (
        <div className="cl-subcard">
          <h4>本次说明</h4>
          <ul>{core.notes.map(note => <li key={note}>{note}</li>)}</ul>
        </div>
      )}

      {detail && detail.notes.length > 0 && (
        <div className="cl-subcard">
          <h4>明细说明</h4>
          <ul>{detail.notes.map(note => <li key={note}>{note}</li>)}</ul>
        </div>
      )}

      <div className="cl-subcard">
        <h4>已加载的图表序列</h4>
        {Object.keys(series).length === 0 ? <p className="cl-muted">还没有打开需要图表的标签页。</p> : (
          <ul>
            {Object.entries(series).map(([key, value]) => (
              <li key={key}>
                {key.split(':')[1]} · {value.range} · {value.interval}
                {value.note ? ` · ${value.note}` : ''}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="cl-subcard">
        <h4>免费源不提供的项</h4>
        <ul>
          <li>分析师一致预期与目标价、机构持仓、北向资金日度数据、龙虎榜、L2/逐笔成交。</li>
          <li>完整新闻正文；本页只保留标题、来源与链接。</li>
          <li>指数全量成分股明细。中证官网可确认的是前十大权重与行业权重。</li>
        </ul>
        <p className="cl-note">这些项在页面上不会用 0、也不会用猜测补上。</p>
      </div>

      {missing.length > 0 && (
        <div className="cl-subcard">
          <h4>缺什么、为什么</h4>
          <ul>
            {missing.map(([key, view]) => (
              <li key={key}><b>{domainLabel(key)}</b>：{view.note || domainStatusText(view.status)}</li>
            ))}
          </ul>
        </div>
      )}

      <p className="cl-disclaimer">{core.disclaimer}</p>
    </div>
  )
}

// ---------------------------------------------------------------------
// 概览里的关键数字
// ---------------------------------------------------------------------

export function KeyNumbers({ core }: { core: CoreView }) {
  const q = core.quote
  return (
    <div className="cl-numbers cl-key-numbers">
      <NumberCell label="最新价" value={q?.price === null || q?.price === undefined ? '—' : num(q.price, 3)} />
      <NumberCell label="当日" value={signedPct(q?.pctChange)} />
      <NumberCell label="成交额" value={yi(q?.amount)} />
      <NumberCell label="换手率" value={q?.turnoverRate === null || q?.turnoverRate === undefined ? '—' : `${num(q.turnoverRate, 2)}%`} />
      <NumberCell label="总市值" value={yi(q?.marketCap)} />
      <NumberCell label="报价 PE" value={num(q?.peTtm, 2)} />
      <NumberCell label="PB" value={num(q?.pb, 2)} />
      <NumberCell label="股息率" value={q?.dividendYield === null || q?.dividendYield === undefined ? '—' : `${num(q.dividendYield, 2)}%`} />
      <NumberCell label="估值数据日" value={dateOnly(core.valuation?.asOf)} />
      <NumberCell label="行情时间" value={dateTime(q?.asOf)} />
    </div>
  )
}
