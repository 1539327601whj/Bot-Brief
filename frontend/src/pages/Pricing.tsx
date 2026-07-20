import './BusinessPages.css'

const plans = [
  {
    name: '免费版',
    icon: '🌱',
    price: '0',
    unit: '元/月',
    desc: '适合体验每日简报和基础市场观察的个人用户。',
    features: ['公共 AI 早晚报', '基础 ETF / A股观察', '每月 20 AI 积分', '3 个关注关键词', '保留 7 天历史记录'],
  },
  {
    name: '个人 Pro',
    icon: '🚀',
    price: '29',
    unit: '元/月',
    desc: '适合希望定制关注方向、追踪市场和自动接收日报的深度用户。',
    features: ['每月 300 AI 积分', '自定义兴趣日报', 'ETF / 股票自选池', '最多 5 个自动任务', '企业微信 / 邮箱 / 飞书推送', '完整历史数据查询'],
    featured: true,
  },
  {
    name: '创作者版',
    icon: '🎬',
    price: '99',
    unit: '元/月起',
    desc: '适合短视频博主、运营人员和 MCN 持续分析内容增长。',
    features: ['每月 1000 AI 积分', '绑定 1 个短视频账号', '每日账号数据报告', '爆款视频与竞品追踪', '选题、标题和脚本优化'],
  },
  {
    name: '商家版',
    icon: '🛍️',
    price: '199',
    unit: '元/月起',
    desc: '适合商家持续追踪店铺经营、商品表现和客户需求。',
    features: ['每月 2000 AI 积分', '绑定 1 个店铺', '每日经营与销量报告', '商品分析和客户画像', '补货、活动和经营建议'],
  },
]

export default function Pricing() {
  return (
    <div className="business-page">
      <div className="business-hero">
        <div className="business-kicker">Commercial Plans</div>
        <h2>💎 套餐权益</h2>
        <p>采用“订阅套餐 + 每月 AI 积分 + 超额积分包”计费。积分用于生成定制日报和深度分析，查看套餐内已有内容不重复扣费。</p>
      </div>

      <div className="business-grid pricing-plans">
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
            <span className="coming-soon">支付暂未开放，可联系管理员开通</span>
          </div>
        ))}
      </div>

      <h3 className="business-section-title">AI 积分说明</h3>
      <div className="business-grid two">
        <div className="business-card">
          <div className="business-card-icon">⚡</div>
          <h3>按任务消耗积分</h3>
          <p>积分只在生成新内容时扣除，实际消耗会根据任务的数据量和分析深度确定。</p>
          <ul className="business-list">
            <li>个人兴趣日报：约 2 积分</li>
            <li>ETF / A股深度分析：约 3 积分</li>
            <li>单条短视频分析：约 3–5 积分</li>
            <li>店铺经营日报：约 8–15 积分</li>
          </ul>
        </div>
        <div className="business-card">
          <div className="business-card-icon">➕</div>
          <h3>超额积分包</h3>
          <p>套餐积分不足时可以单独补充，不需要立即升级套餐。</p>
          <ul className="business-list">
            <li>100 积分：19 元</li>
            <li>300 积分：49 元</li>
            <li>1000 积分：129 元</li>
            <li>积分有效期与具体规则以上线说明为准</li>
          </ul>
        </div>
      </div>

      <h3 className="business-section-title">团队版</h3>
      <div className="business-card">
        <div className="business-card-icon">🏢</div>
        <span className="plan-badge">699 元/月起</span>
        <h3 className="team-plan-title">按成员和账号规模配置</h3>
        <p>适合公司或团队统一管理多个成员、多个平台账号和专属推送渠道。</p>
        <ul className="business-list">
          <li>多账号和多成员协作</li>
          <li>多平台数据汇总</li>
          <li>团队权限和专属推送渠道</li>
          <li>定制积分额度和开通方案</li>
        </ul>
        <span className="coming-soon">联系管理员定制开通</span>
      </div>
    </div>
  )
}
