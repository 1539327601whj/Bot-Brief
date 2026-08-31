import { Link } from 'react-router-dom'
import './BusinessPages.css'

export default function CreatorTools() {
  return (
    <div className="business-page">
      <div className="business-hero">
        <div className="business-kicker">Creator Growth</div>
        <h2>🎬 短视频分析</h2>
        <p>先登记抖音、小红书、快手或 B 站账号，再录入作品。分析、选题和改稿都在内容增长里，不会登录你的平台账号。</p>
        <Link to="/content-growth" className="business-go">去登记短视频账号</Link>
      </div>

      <div className="business-grid">
        <div className="business-card featured">
          <div className="business-card-icon">📊</div>
          <h3>短视频账号分析</h3>
          <p>汇总你手工登记的播放、点赞、评论、收藏、涨粉和作品表现。</p>
          <ul className="business-list">
            <li>账号关键指标</li>
            <li>作品表现排行</li>
            <li>低于预期作品提醒</li>
          </ul>
          <Link to="/content-growth" className="business-go">去内容增长查看</Link>
        </div>
        <div className="business-card">
          <div className="business-card-icon">🔥</div>
          <h3>爆款与竞品</h3>
          <p>用已录入的作品做爆款分析，并记下重点同行账号。自动追踪还没做。</p>
          <ul className="business-list">
            <li>爆款原因分析</li>
            <li>竞品账号备忘</li>
            <li>自动追踪后续开放</li>
          </ul>
          <Link to="/content-growth" className="business-go">去内容增长使用</Link>
        </div>
        <div className="business-card">
          <div className="business-card-icon">💡</div>
          <h3>选题与脚本优化</h3>
          <p>结合账号定位和作品数据，生成选题方向、标题和脚本建议。</p>
          <ul className="business-list">
            <li>明日选题建议</li>
            <li>标题优化</li>
            <li>短视频脚本大纲</li>
          </ul>
          <Link to="/content-growth" className="business-go">去生成选题</Link>
        </div>
      </div>
    </div>
  )
}
