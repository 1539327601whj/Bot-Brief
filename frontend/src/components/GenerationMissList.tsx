import { missKey, missStamp, progressTone, type TopicProgressItem } from '../utils/pushDisplay'
import './GenerationMissList.css'

export default function GenerationMissList({
  items,
  title = '未生成的订阅',
}: {
  items: TopicProgressItem[]
  title?: string
}) {
  if (items.length === 0) return null
  return (
    <div className="generation-miss-block">
      {title ? <div className="generation-miss-title">{title}</div> : null}
      <div className="generation-miss-list">
        {items.map(item => (
          <div key={missKey(item)} className={`topic-progress ${progressTone(item.status)}`}>
            <span>{missStamp(item)} · {item.topic}</span>
            <strong>{item.label || '未生成'}</strong>
            <small>{item.message}</small>
          </div>
        ))}
      </div>
    </div>
  )
}
