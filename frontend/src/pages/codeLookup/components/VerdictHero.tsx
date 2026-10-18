import type { CoreView } from '../types'
import {
  assetTypeLabel,
  confidenceText,
  num,
  ratioPct,
  verdictTone,
} from '../format'
import BuyZoneCard from './BuyZoneCard'

interface Props {
  core: CoreView
}

/** 首屏结论。**没有分数时只显示「数据不足」与原因，绝不用 0 分顶替。** */
export default function VerdictHero({ core }: Props) {
  const { verdict, buyZone } = core
  const tone = verdictTone(verdict.labelKey)
  const withheld = verdict.score === null
  const score = verdict.score === null ? null : Number(verdict.score)

  return (
    <section className="cl-hero">
      <div className="cl-hero-id">
        <div>
          <div className="cl-kicker">Code Lookup · 规则结论</div>
          <h2>
            {core.name || '名称未取到'}
            <span className="cl-code">{core.code}</span>
          </h2>
          <div className="cl-meta">
            <span>{assetTypeLabel(core.assetType)}</span>
            {core.market && <span>{core.market}</span>}
            {core.industry && <span>{core.industry}</span>}
            {core.trackingIndexCode && <span>跟踪 {core.trackingIndexCode}</span>}
            <span>快照 {core.snapshotAt?.replace('T', ' ').slice(0, 16) || '未标'}</span>
            {core.fromCache && <span className="cl-chip">进程内缓存</span>}
          </div>
        </div>
        <div className={`cl-verdict-tone cl-tone-${tone}`}>
          {withheld ? '数据不足' : verdict.label}
        </div>
      </div>

      <div className="cl-hero-main">
        <div className={`cl-gauge cl-tone-${tone}`} aria-hidden={withheld}>
          <div
            className="cl-gauge-ring"
            style={{
              background: withheld
                ? 'conic-gradient(var(--border-strong) 0 100%)'
                : `conic-gradient(var(--tone) ${Math.max(0, Math.min(100, score || 0))}%, var(--border) 0)`,
            }}
          >
            <div className="cl-gauge-inner">
              <strong>{withheld ? '—' : num(score, 0)}</strong>
              <span>综合分</span>
            </div>
          </div>
          <p className="cl-gauge-caption">
            {withheld
              ? '暂不评级'
              : verdict.scoreLow !== null && verdict.scoreHigh !== null
                ? `区间 ${num(verdict.scoreLow, 0)}–${num(verdict.scoreHigh, 0)}`
                : '区间未给出'}
          </p>
        </div>

        <div className="cl-hero-facts">
          {withheld ? (
            <p className="cl-withheld">
              <b>数据不足，暂不评级。</b>
              {verdict.withheldReason || '关键数据缺失，无法给出有依据的数值结论。'}
            </p>
          ) : (
            <div className="cl-fact-grid">
              <div><label>数据覆盖率</label><b>{ratioPct(verdict.coverage)}</b></div>
              <div><label>置信度</label><b>{ratioPct(verdict.confidence)} · {confidenceText(verdict.confidence)}</b></div>
              <div>
                <label>事件调整</label>
                <b className={verdict.eventAdjustment && verdict.eventAdjustment !== 0 ? 'cl-em' : ''}>
                  {verdict.eventAdjustment === null || verdict.eventAdjustment === undefined
                    ? '—'
                    : `${Number(verdict.eventAdjustment) > 0 ? '+' : ''}${num(verdict.eventAdjustment, 0)}`}
                </b>
              </div>
              <div><label>规则版本</label><b className="cl-version">{core.rulesVersion}</b></div>
            </div>
          )}

          {verdict.hardFlags.length > 0 && (
            <div className="cl-hard-flags">
              <label>硬约束命中</label>
              <div>
                {verdict.hardFlags.map(flag => <span key={flag} className="cl-chip cl-chip-warn">{flag}</span>)}
              </div>
            </div>
          )}

          <div className="cl-reason-columns">
            <div>
              <h4>主要依据</h4>
              {verdict.reasons.length > 0 ? (
                <ul>{verdict.reasons.map(r => <li key={r.text}>{r.text}</li>)}</ul>
              ) : (
                <p className="cl-muted">本次没有可列出的正向依据。</p>
              )}
            </div>
            <div>
              <h4>主要风险</h4>
              {verdict.risks.length > 0 ? (
                <ul>{verdict.risks.map(r => <li key={r.text}>{r.text}</li>)}</ul>
              ) : (
                <p className="cl-muted">本次没有触发额外的风险条目；这不代表没有风险。</p>
              )}
            </div>
          </div>
        </div>
      </div>

      <BuyZoneCard zone={buyZone} code={core.code} />
    </section>
  )
}
