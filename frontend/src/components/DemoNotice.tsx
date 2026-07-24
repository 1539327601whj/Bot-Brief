import './DemoNotice.css'

interface DemoNoticeProps {
  publicContent?: boolean
}

export default function DemoNotice({ publicContent = false }: DemoNoticeProps) {
  const text = publicContent
    ? '简报与市场内容来自公开报告，账户配置和操作状态为合成数据；所有修改、AI 调用和外部发送功能均不可用。'
    : '当前展示固定合成数据，仅供浏览；所有修改、AI 调用和外部发送功能均不可用。'

  return (
    <aside className="demo-notice" role="note" aria-label="公开 Demo 说明">
      <strong className="demo-notice__title">公开 Demo</strong>
      <span className="demo-notice__text">{text}</span>
    </aside>
  )
}
