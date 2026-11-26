import type { CoreView, DecisionAxisView, DecisionView } from '../types'
import { num, signedPct } from '../format'

interface Props {
  core: CoreView
  portfolioManaged?: boolean
}

type Tone = 'good' | 'ok' | 'warn' | 'bad' | 'muted'

/** 位置：低是机会、高是风险；中性不是「没结论」。 */
function positionTone(key: string): Tone {
  switch (key) {
    case 'LOW': return 'good'
    case 'NEUTRAL': return 'ok'
    case 'HIGH': return 'warn'
    default: return 'muted'
  }
}

/** 时机/行动：只在证据足够时给出 build，其余一律按「等」处理。 */
function timingTone(key: string): Tone {
  switch (key) {
    case 'ACCUMULATE':
    case 'BUILD_GRADUALLY': return 'good'
    case 'HOLD':
    case 'HOLD_OBSERVE': return 'ok'
    case 'WAIT':
    case 'WAIT_STABILIZE':
    case 'WAIT_PULLBACK': return 'warn'
    case 'AVOID': return 'bad'
    default: return 'muted'
  }
}

function horizonTone(key: string): Tone {
  switch (key) {
    case 'LONG': return 'good'
    case 'SHORT': return 'ok'
    case 'BOTH': return 'ok'
    default: return 'muted'
  }
}

/** 长期趋势：向上才是用户要的；走平/向下要显式标风险。 */
function longTermTone(key: string): Tone {
  switch (key) {
    case 'UP': return 'good'
    case 'MIXED': return 'warn'
    case 'FLAT_OR_DOWN': return 'bad'
    default: return 'muted'
  }
}

function availableAxes(decision: DecisionView): DecisionAxisView[] {
  return (decision.position?.axes || []).filter(axis => axis.status === 'OK')
}

/**
 * 首屏决策卡：先回答「现在什么位置、是不是好时机、该怎么做、看长还是看短」。
 *
 * 这里只展示后端规则算出的封闭标签，不在前端二次推导或改写成更「好听」的说法。
 * 四块中任何一块是 `INSUFFICIENT`，就照实显示「数据不足」。
 */
export default function DecisionCard({ core, portfolioManaged }: Props) {
  const decision = core.decision
  if (!decision) return null

  const { position, timing, action, horizon } = decision
  const axes = availableAxes(decision)
  const positionToneValue = positionTone(position.key)
  const timingToneValue = timingTone(timing.key)
  const actionToneValue = timingTone(action.key)
  const horizonToneValue = horizonTone(horizon.key)
  const scoreText = horizon.longScore !== null && horizon.shortScore !== null
    ? `长期 ${num(horizon.longScore, 0)} · 短期 ${num(horizon.shortScore, 0)}`
    : '暂时无法比较'

  return (
    <section className="cl-decision" aria-label="规则决策">
      <header className="cl-decision-head">
        <div>
          <div className="cl-kicker">{portfolioManaged ? '价格位置与趋势' : '先看结论 · 规则计算'}</div>
          <h3>
            {core.name || '名称未取到'}
            <span className="cl-code">{core.code}</span>
          </h3>
        </div>
        {!portfolioManaged && <p className="cl-decision-summary">{decision.summary}</p>}
      </header>

      <div className={portfolioManaged ? 'cl-decision-grid cl-portfolio-market-grid' : 'cl-decision-grid'}>
        <div className={`cl-decision-cell cl-tone-${positionToneValue}`}>
          <label>位置</label>
          <b>{position.label}</b>
          <p>
            {axes.length > 0
              ? axes.map(axis => `${axis.label} ${axis.display}`).join(' · ')
              : position.reasons[0]?.text || '可用轴不足'}
          </p>
        </div>

        {!portfolioManaged && <><div className={`cl-decision-cell cl-tone-${timingToneValue}`}>
          <label>时机</label>
          <b>{timing.label}</b>
          <p>{timing.triggers[0] || (timing.key === 'INSUFFICIENT' ? '先补齐数据' : '按当前证据等待')}</p>
        </div>

        <div className={`cl-decision-cell cl-tone-${actionToneValue}`}>
          <label>行动</label>
          <b>{action.label}</b>
          <p>{action.tranchePlan[0] || '先补齐关键数据，再做判断'}</p>
        </div></>}

        <div className={`cl-decision-cell cl-tone-${horizonToneValue}`}>
          <label>周期</label>
          <b>{horizon.label}</b>
          <p>{scoreText}</p>
        </div>
      </div>

      {decision.longTerm && (
        <div className={`cl-longterm cl-tone-${longTermTone(decision.longTerm.key)}`}>
          <div>
            <label>本标的长期趋势</label>
            <b>{decision.longTerm.label}</b>
          </div>
          <p>{decision.longTerm.summary}</p>
        </div>
      )}

      {axes.length > 0 && (
        <div className="cl-decision-axes" aria-label="位置轴明细">
          {axes.map(axis => (
            <span key={axis.key} className="cl-chip">
              {axis.label} {axis.display}
            </span>
          ))}
        </div>
      )}

      {decision.outlook && (
        <div className="cl-outlook" aria-label="历史条件收益">
          <h4>
            历史条件收益
            {decision.outlook.currentPercentile !== null && ` · 当前价格分位 ${num(decision.outlook.currentPercentile, 1)}%`}
            （描述性统计，不是预测）
          </h4>
          {decision.outlook.available ? (
            <>
              <div className="cl-outlook-rows">
                {decision.outlook.horizons.map(h => (
                  <div key={h.key} className="cl-outlook-row">
                    <b>{h.label}</b>
                    <span>样本 {h.sampleCount} 个</span>
                    <span>中位 <em>{signedPct(h.medianReturnPct, 1)}</em></span>
                    <span>
                      四分位 {signedPct(h.p25ReturnPct, 1)} ~ {signedPct(h.p75ReturnPct, 1)}
                    </span>
                    <span>上涨占比 {num(h.winRatePct, 1)}%</span>
                  </div>
                ))}
              </div>
              <p className="cl-muted">{decision.outlook.note}</p>
            </>
          ) : (
            <p className="cl-muted">{decision.outlook.status || '历史样本不足，暂不给历史收益分布。'}</p>
          )}
        </div>
      )}

      <div className="cl-decision-body">
        <div>
          <h4>主要依据</h4>
          {decision.reasons.length > 0 ? (
            <ul>{decision.reasons.slice(0, 8).map(reason => <li key={reason.text}>{reason.text}</li>)}</ul>
          ) : (
            <p className="cl-muted">没有可列出的依据；这不等于风险为零。</p>
          )}
        </div>
        <div>
          <h4>主要风险</h4>
          {decision.risks.length > 0 ? (
            <ul>{decision.risks.slice(0, 8).map(risk => <li key={risk.text}>{risk.text}</li>)}</ul>
          ) : (
            <p className="cl-muted">本次没有触发额外风险条目；这不代表没有风险。</p>
          )}
        </div>
        <div>
          <h4>什么会改变结论</h4>
          {decision.watchItems.length > 0 ? (
            <ul>{decision.watchItems.slice(0, 8).map(item => <li key={item}>{item}</li>)}</ul>
          ) : (
            <p className="cl-muted">暂无观察条件。</p>
          )}
        </div>
      </div>

      <p className="cl-disclaimer">{decision.disclaimer}</p>
    </section>
  )
}
