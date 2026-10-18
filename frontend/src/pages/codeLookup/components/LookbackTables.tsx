import type { CoreView, LookbackView } from '../types'
import { num, signedPct } from '../format'

interface Props {
  core: CoreView
}

/**
 * 旧页的八档回放**原样保留**：五年/十年是「五年前那一天 vs 今天」「十年前那一天 vs 今天」，
 * 不是区间涨幅。基线取目标日当天或之前最近的交易日；取不到时保留档位、写明原因。
 */
export default function LookbackTables({ core }: Props) {
  const priceNow = core.quote?.price ?? core.technical?.price ?? null
  const pePercentile = core.valuation?.pePercentile ?? null

  return (
    <div className="cl-lookbacks">
      <LookbackTable
        title="价格回放"
        caption={`今 ${num(priceNow, 3)} · 前复权 · 相对涨幅（今 ÷ 基线 − 1）`}
        cells={core.priceLookbacks}
        digits={3}
        changeKind="percent"
      />
      <LookbackTable
        title="PE 分位回放"
        caption={`今 ${pePercentile === null ? '未取到' : `${num(pePercentile, 2)}%`} · 变化是百分点差，不是相对涨幅`}
        cells={core.peLookbacks}
        digits={2}
        changeKind="points"
        emptyText="ETF / 指数的估值历史在「估值」标签页，不在这里拿个股 PE 口径顶替。"
      />
    </div>
  )
}

function LookbackTable({ title, caption, cells, digits, changeKind, emptyText }: {
  title: string
  caption: string
  cells: LookbackView[]
  digits: number
  changeKind: 'percent' | 'points'
  emptyText?: string
}) {
  if (cells.length === 0) {
    return (
      <section className="cl-lookback-card">
        <header><h4>{title}</h4><span>{caption}</span></header>
        <p className="cl-empty">{emptyText || '本次没有可用的回放数据。'}</p>
      </section>
    )
  }

  return (
    <section className="cl-lookback-card">
      <header><h4>{title}</h4><span>{caption}</span></header>
      <div className="cl-table-wrap">
        <table className="cl-table">
          <thead>
            <tr>
              <th>档位</th>
              <th>基线日期</th>
              <th>基线值</th>
              <th>变化</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            {cells.map(cell => {
              const missing = cell.baseline === null || cell.baseline === undefined
              return (
                <tr key={cell.label} className={missing ? 'is-missing' : undefined}>
                  <td><b>{cell.label}</b></td>
                  <td>{cell.baselineDate || '—'}</td>
                  <td>{missing ? '—' : num(cell.baseline, digits)}{changeKind === 'points' && !missing ? '%' : ''}</td>
                  <td className={changeClass(cell.change)}>
                    {missing || cell.change === null
                      ? '—'
                      : changeKind === 'points'
                        ? `${Number(cell.change) > 0 ? '+' : ''}${num(cell.change, 2)} 点`
                        : signedPct(cell.change)}
                  </td>
                  <td className="cl-table-status">{missing ? (cell.status || '未确认（后端未给出原因）') : ''}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function changeClass(change: number | null): string | undefined {
  if (change === null || change === undefined) return undefined
  if (Math.abs(Number(change)) < 0.005) return undefined
  return Number(change) > 0 ? 'cl-up' : 'cl-down'
}
