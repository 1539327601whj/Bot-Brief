import { useEffect, useState } from 'react'
import type { DimensionView } from '../types'
import { dimensionStatusText, num, ratioPct, scoreTone } from '../format'

interface Props {
  dimensions: DimensionView[]
}

/**
 * 六维评分块。**分数、权重、覆盖率、状态同时显示**：
 * 只给一个分数会让人以为六维等权，而权重与覆盖率恰恰决定这个分数能不能信。
 */
export default function ScoreTiles({ dimensions }: Props) {
  const [selected, setSelected] = useState<DimensionView | null>(null)

  return (
    <>
      <section className="cl-tiles" aria-label="六维评分">
        {dimensions.map(d => {
          const na = d.status === 'N/A' || d.score === null
          return (
            <button
              type="button"
              key={d.key}
              className={`cl-tile cl-tone-${scoreTone(d.score)}${na ? ' cl-tile-na' : ''}`}
              onClick={() => setSelected(d)}
            >
              <header>
                <h4>{d.label}</h4>
                <span>权重 {d.weight}</span>
              </header>
              <div className="cl-tile-score">{na ? '—' : num(d.score, 0)}</div>
              <footer>
                <span>{dimensionStatusText(d.status)}</span>
                <span>覆盖 {ratioPct(d.coverage)}</span>
              </footer>
              {d.reasons.length > 0 && <p className="cl-tile-reason">{d.reasons[0]}</p>}
            </button>
          )
        })}
      </section>
      <EvidenceDrawer dimension={selected} onClose={() => setSelected(null)} />
    </>
  )
}

function EvidenceDrawer({ dimension, onClose }: { dimension: DimensionView | null; onClose: () => void }) {
  useEffect(() => {
    if (!dimension) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [dimension, onClose])

  if (!dimension) return null

  return (
    <div className="cl-drawer-mask" role="dialog" aria-modal="true" aria-label={`${dimension.label}评分依据`}
      onClick={onClose}>
      <aside className="cl-drawer" onClick={event => event.stopPropagation()}>
        <header>
          <div>
            <h3>{dimension.label}</h3>
            <p>
              得分 {dimension.score === null ? '未评分' : num(dimension.score, 1)}
              {' · '}权重 {dimension.weight}
              {' · '}覆盖率 {ratioPct(dimension.coverage)}
              {' · '}{dimensionStatusText(dimension.status)}
            </p>
          </div>
          <button type="button" className="cl-drawer-close" onClick={onClose} aria-label="关闭">×</button>
        </header>

        {dimension.metrics.length === 0 ? (
          <p className="cl-muted">这一维没有可展示的子指标。</p>
        ) : (
          <ul className="cl-metric-list">
            {dimension.metrics.map(metric => (
              <li key={metric.key}>
                <div className="cl-metric-head">
                  <b>{metric.label}</b>
                  <span>{metric.display ?? '未获取'}</span>
                </div>
                <div className="cl-metric-bar">
                  <div style={{ width: `${Math.max(0, Math.min(100, Number(metric.score ?? 0)))}%` }} />
                </div>
                <div className="cl-metric-meta">
                  <span>子权重 {num(metric.weight, 2)}</span>
                  <span>得分 {metric.score === null ? '未评分' : num(metric.score, 0)}</span>
                </div>
                {metric.basis && <p className="cl-metric-basis">{metric.basis}</p>}
                {metric.evidenceId && <code className="cl-evidence-id">{metric.evidenceId}</code>}
              </li>
            ))}
          </ul>
        )}

        {dimension.reasons.length > 0 && (
          <div className="cl-drawer-reasons">
            <h4>这一维的判定</h4>
            <ul>{dimension.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>
          </div>
        )}

        <p className="cl-drawer-foot">
          每个指标都带 evidence ID。区间、情景与大模型解读只能引用这些 ID，
          模型不会给出页面上没有的数字。
        </p>
      </aside>
    </div>
  )
}
