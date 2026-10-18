import type { BriefView } from '../types'
import type { LookupFailure } from '../useCodeLookup'
import { dateTime } from '../format'

interface Props {
  brief: BriefView | null
  loading: boolean
  failure: LookupFailure | null
  onGenerate: () => void
  disabled: boolean
}

/**
 * 大模型解读。**手动触发、证据绑定**：模型只能引用页面已有的 evidence ID，
 * 不能改判定、不能给目标价。校验不过时后端回退规则文案，这里明确标出「回退」。
 */
export default function AiBriefPanel({ brief, loading, failure, onGenerate, disabled }: Props) {
  return (
    <div className="cl-ai">
      <header>
        <div>
          <h4>大模型解读</h4>
          <span>可选。模型只负责改写已算好的事实，不参与判定。</span>
        </div>
        <button type="button" className="cl-btn" disabled={loading || disabled} onClick={onGenerate}>
          {loading ? '生成中…' : brief ? '重新生成' : '生成解读'}
        </button>
      </header>

      {failure && (
        <p className="cl-error">
          {failure.kind === 'rate-limited'
            ? `行情源限流：${failure.message}。本页不会自动重试。`
            : failure.message}
        </p>
      )}

      {!brief && !loading && !failure && (
        <p className="cl-muted">
          点击后才会调用模型。同一份快照与同一版规则重复点击会命中缓存，
          不会重复调用；未配置模型时也会如实说明，不影响上面的规则结论。
        </p>
      )}

      {brief && (
        <div className="cl-ai-body">
          <div className="cl-ai-meta">
            <b>{brief.title}</b>
            <span>
              {brief.available
                ? `${brief.cached ? '缓存命中' : '本次生成'} · ${dateTime(brief.generatedAt)}`
                : brief.fallback
                  ? '模型输出未通过校验，以下是规则文案'
                  : '本次未生成'}
            </span>
          </div>

          {brief.reason && <p className="cl-ai-reason">{brief.reason}</p>}
          {brief.note && <p className="cl-note">{brief.note}</p>}

          {brief.sections.length > 0 && (
            <div className="cl-ai-sections">
              {brief.sections.map((section, index) => (
                <section key={`${section.heading}-${index}`}>
                  <h5>{section.heading}</h5>
                  <p>{section.text}</p>
                  {section.evidenceIds.length > 0 && (
                    <div className="cl-evidence-row">
                      {section.evidenceIds.map(id => <code key={id}>{id}</code>)}
                    </div>
                  )}
                </section>
              ))}
            </div>
          )}

          {brief.snapshotChanged && (
            <p className="cl-error">
              解读基于较早的一份快照；页面数据已更新，请重新生成后再对照。
            </p>
          )}

          <p className="cl-note">
            版本：规则 {brief.rulesVersion} · 提示词 {brief.promptVersion}。
            这一段文字不是新的判断依据；规则结论与参考区间不依赖模型。
          </p>
          <p className="cl-disclaimer">{brief.disclaimer}</p>
        </div>
      )}
    </div>
  )
}
