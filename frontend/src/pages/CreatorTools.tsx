import './BusinessPages.css'

export default function CreatorTools() {
  return (
    <div className="business-page">
      <div className="business-hero">
        <div className="business-kicker">Creator Growth</div>
        <h2>🎬 短视频分析</h2>
        <p>面向创作者和运营人员，未来支持账号表现分析、爆款追踪、选题推荐和脚本优化。第一版先做产品入口和能力说明。</p>
      </div>

      <div className="business-grid">
        <div className="business-card featured">
          <div className="business-card-icon">📊</div>
          <h3>短视频账号分析</h3>
          <p>汇总播放量、点赞、评论、收藏、涨粉和作品表现，形成每日增长日报。</p>
          <ul className="business-list">
            <li>账号关键指标趋势</li>
            <li>作品表现排行</li>
            <li>低于预期作品提醒</li>
          </ul>
          <span className="coming-soon">即将上线</span>
        </div>
        <div className="business-card">
          <div className="business-card-icon">🔥</div>
          <h3>爆款视频追踪</h3>
          <p>追踪同领域热门视频，拆解内容主题、标题、封面和评论反馈。</p>
          <ul className="business-list">
            <li>爆款视频榜单</li>
            <li>热门主题聚类</li>
            <li>竞品账号监控</li>
          </ul>
          <span className="coming-soon">即将上线</span>
        </div>
        <div className="business-card">
          <div className="business-card-icon">💡</div>
          <h3>选题与脚本优化</h3>
          <p>结合近期热点和账号历史数据，推荐更适合拍摄的选题方向。</p>
          <ul className="business-list">
            <li>明日选题建议</li>
            <li>标题优化</li>
            <li>短视频脚本大纲</li>
          </ul>
          <span className="coming-soon">创作者版能力</span>
        </div>
      </div>
    </div>
  )
}
