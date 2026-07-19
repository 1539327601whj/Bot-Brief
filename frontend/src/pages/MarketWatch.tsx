import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import './MarketWatch.css'

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  content?: string
  createdAt: string
}

interface WatchCardConfig {
  edition: 'market_watch_morning' | 'market_watch_evening'
  title: string
  subtitle: string
  icon: string
}

const watchCards: WatchCardConfig[] = [
  { edition: 'market_watch_morning', title: '早间观察', subtitle: '盘中早段行情、估值位置和今日关注点', icon: '早' },
  { edition: 'market_watch_evening', title: '晚间观察', subtitle: '全天表现复盘、波动提醒和明日观察点', icon: '晚' },
]

const featureCards = [
  { title: '我的自选 ETF', desc: '跟踪自选 ETF 的价格、涨跌幅和估值位置。', icon: 'ETF' },
  { title: '我的自选股票', desc: '展示公开行情指标下的观察候选和主要风险。', icon: '股' },
  { title: '估值提醒', desc: '关注 PE 分位、估值偏高或偏低等变化。', icon: '估' },
  { title: '波动提醒', desc: '提示短期涨跌幅、成交额和量比异常变化。', icon: '波' },
  { title: '行业热度分析', desc: '基于候选和公开指标观察行业热度，不做配置建议。', icon: '热' },
  { title: '风险提示', desc: '明确数据限制、波动风险和非投资建议边界。', icon: '险' },
]

function LatestWatchCard({ config }: { config: WatchCardConfig }) {
  const [report, setReport] = useState<Report | null>(null)
  const [loading, setLoading] = useState(true)
  const [expanded, setExpanded] = useState(false)

  useEffect(() => {
    api.get('/reports/latest', { params: { edition: config.edition } })
      .then(res => {
        if (res.data?.code === 200 && res.data.data) setReport(res.data.data)
      })
      .catch(() => {/* empty state */})
      .finally(() => setLoading(false))
  }, [config.edition])

  const reportDate = report ? dayjs(report.createdAt).format('YYYY-MM-DD') : ''
  const isToday = reportDate === dayjs().format('YYYY-MM-DD')

  return (
    <section className="market-watch-report-card">
      <div className="market-watch-card-header">
        <div>
          <div className="market-watch-card-title">
            <span>{config.icon}</span>
            <span>{config.title}</span>
          </div>
          <p>{config.subtitle}</p>
        </div>
        {report && <Link to={`/report/${report.id}`} className="market-watch-detail-link">详情 →</Link>}
      </div>

      {loading ? (
        <div className="market-watch-empty">加载中…</div>
      ) : !report ? (
        <div className="market-watch-empty">暂无{config.title}报告</div>
      ) : (
        <>
          <h3>{report.title}</h3>
          {!isToday && (
            <div className="market-watch-stale">当前展示最近一期：{reportDate}</div>
          )}
          <div className={`market-watch-markdown ${expanded ? 'expanded' : ''}`}>
            <ReactMarkdown>{expanded ? (report.content || report.summary) : report.summary}</ReactMarkdown>
          </div>
          <button className="market-watch-toggle" onClick={() => setExpanded(v => !v)}>
            {expanded ? '收起' : '展开阅读'}
          </button>
        </>
      )}
    </section>
  )
}

export default function MarketWatch() {
  return (
    <div className="market-watch-page">
      <header className="market-watch-hero">
        <div>
          <div className="market-watch-kicker">Market Intelligence</div>
          <h2>市场观察</h2>
          <p>基于公开行情数据生成观察、分析、提醒和风险提示，不构成投资建议、证券推荐或买卖依据。</p>
        </div>
        <div className="market-watch-risk-badge">风险提示优先</div>
      </header>

      <div className="market-watch-grid">
        {watchCards.map(card => <LatestWatchCard key={card.edition} config={card} />)}
      </div>

      <section className="market-watch-section">
        <div className="market-watch-section-header">
          <h3>观察能力</h3>
          <span>一期为报告展示，二期可扩展为用户级配置</span>
        </div>
        <div className="market-watch-feature-grid">
          {featureCards.map(card => (
            <div key={card.title} className="market-watch-feature-card">
              <div className="market-watch-feature-icon">{card.icon}</div>
              <h4>{card.title}</h4>
              <p>{card.desc}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}
