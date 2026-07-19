import './BusinessPages.css'

const plans = [
  {
    name: '免费版',
    icon: '🌱',
    price: '0',
    unit: '元/月',
    desc: '适合先体验每日简报和基础市场观察的个人用户。',
    features: ['每日 AI 简报', '基础 ETF 观察', '少量订阅频道', '基础企业微信推送'],
  },
  {
    name: '个人 Pro',
    icon: '🚀',
    price: '19-39',
    unit: '元/月',
    desc: '适合希望自定义关注方向、追踪 ETF / 股票和历史数据的深度用户。',
    features: ['自定义关注关键词', 'ETF / 股票自选池', '每日 AI 总结', '企业微信 / 邮箱 / 飞书推送', '历史数据查询'],
    featured: true,
  },
  {
    name: '创作者版',
    icon: '🎬',
    price: '59-99',
    unit: '元/月',
    desc: '适合短视频博主、运营人员和 MCN 做内容增长分析。',
    features: ['短视频账号分析', '爆款视频追踪', '选题推荐', '脚本优化', '竞品监控'],
  },
  {
    name: '商家版',
    icon: '🛍️',
    price: '99-299',
    unit: '元/月',
    desc: '适合商家追踪店铺经营、商品趋势和客户需求。',
    features: ['店铺日报', '商品分析', '用户画像', '爆款预测', 'AI 经营建议'],
  },
]

export default function Pricing() {
  return (
    <div className="business-page">
      <div className="business-hero">
        <div className="business-kicker">Commercial Plans</div>
        <h2>💎 套餐权益</h2>
        <p>BriefMind 未来会从 AI 简报升级为 AI 数据决策工作台。第一版先展示套餐和权益，支付能力后续再接入。</p>
      </div>

      <div className="business-grid">
        {plans.map(plan => (
          <div key={plan.name} className={`business-card ${plan.featured ? 'featured' : ''}`}>
            <div className="business-card-icon">{plan.icon}</div>
            <span className="plan-badge">{plan.name}</span>
            <div className="plan-price">
              <strong>{plan.price}</strong>
              <span>{plan.unit}</span>
            </div>
            <p>{plan.desc}</p>
            <ul className="business-list">
              {plan.features.map(feature => <li key={feature}>{feature}</li>)}
            </ul>
            <span className="coming-soon">暂未开放支付，后续支持联系管理员开通</span>
          </div>
        ))}
      </div>

      <h3 className="business-section-title">团队版</h3>
      <div className="business-card">
        <div className="business-card-icon">🏢</div>
        <h3>按账号数收费</h3>
        <p>适合公司或团队统一管理多个成员、多个平台账号和专属推送渠道。</p>
        <ul className="business-list">
          <li>多账号管理</li>
          <li>多成员协作</li>
          <li>多平台数据汇总</li>
          <li>团队权限和专属推送渠道</li>
        </ul>
      </div>
    </div>
  )
}
