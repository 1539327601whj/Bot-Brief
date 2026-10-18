import type { ScenarioView } from '../types'

interface Props {
  scenarios: ScenarioView[]
  withheld: boolean
}

const TONE: Record<string, string> = {
  bull: 'good',
  base: 'ok',
  bear: 'bad',
}

/**
 * 三张条件推演卡。**不显示概率、不给目标价、不算隐含涨幅**——
 * 这些字段后端本来就不生成，页面也不自己算。
 */
export default function ScenarioCards({ scenarios, withheld }: Props) {
  if (scenarios.length === 0) {
    return (
      <p className="cl-empty">
        {withheld
          ? '数据不足时不做情景推演：没有可引用的评分与估值条件。'
          : '本次没有生成情景卡。'}
      </p>
    )
  }

  return (
    <div className="cl-scenarios">
      {scenarios.map(scenario => {
        const tone = TONE[scenario.key] || 'ok'
        return (
          <article key={scenario.key} className={`cl-scenario cl-tone-${tone}${scenario.triggered ? ' is-triggered' : ''}`}>
            <header>
              <h4>{scenario.label}</h4>
              <span className={scenario.triggered ? 'cl-scenario-hit' : 'cl-scenario-idle'}>
                {scenario.triggered ? '当前触发' : '未触发'}
              </span>
            </header>
            <p className="cl-scenario-condition">{scenario.condition}</p>
            <p className="cl-scenario-text">{scenario.narrative}</p>
            <div className="cl-scenario-invalidation">
              <label>失效条件</label>
              <span>{scenario.invalidation}</span>
            </div>
            {scenario.evidenceIds.length > 0 && (
              <div className="cl-evidence-row">
                {scenario.evidenceIds.map(id => <code key={id}>{id}</code>)}
              </div>
            )}
            <p className="cl-scenario-disclaimer">{scenario.disclaimer}</p>
          </article>
        )
      })}
    </div>
  )
}
