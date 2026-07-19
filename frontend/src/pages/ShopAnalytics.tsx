import { useEffect, useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import {
  fetchShopStores,
  fetchShopOverview,
  generateShopAiReport,
  generateShopDemoData,
  type ShopActivitySuggestion,
  type ShopOverview,
  type ShopProductRank,
  type ShopReplenishmentSuggestion,
  type ShopStore,
} from '../api/shopAnalytics'
import './ShopAnalytics.css'

const platformLabels: Record<string, string> = {
  manual: '手动/模拟',
  taobao: '淘宝',
  douyin: '抖店',
  wechat_shop: '视频号小店',
  pdd: '拼多多',
  kuaishou: '快手小店',
}

function money(value?: number) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function rate(value?: number) {
  const num = Number(value || 0)
  return `${num > 0 ? '+' : ''}${num.toFixed(2)}%`
}

function MetricCard({ label, value, hint, tone }: { label: string; value: string | number; hint?: string; tone?: string }) {
  return (
    <div className={`shop-metric-card ${tone || ''}`}>
      <span className="shop-metric-label">{label}</span>
      <strong className="shop-metric-value">{value}</strong>
      {hint && <span className="shop-metric-hint">{hint}</span>}
    </div>
  )
}

function TrendTag({ trend }: { trend: string }) {
  const text = trend === 'up' ? '上升' : trend === 'down' ? '下降' : '稳定'
  return <span className={`shop-trend-tag ${trend}`}>{text}</span>
}

function ProductList({ title, items, empty }: { title: string; items: ShopProductRank[]; empty: string }) {
  return (
    <div className="shop-panel">
      <h2>{title}</h2>
      {items.length === 0 ? (
        <div className="shop-empty-small">{empty}</div>
      ) : (
        <div className="shop-product-list">
          {items.map(item => (
            <div className="shop-product-item" key={item.productId}>
              <div>
                <div className="shop-product-name">{item.productName}</div>
                <div className="shop-product-meta">
                  销量 {item.quantitySold} 件 · 销售额 {money(item.salesAmount)} · 库存 {item.stock}
                </div>
                {item.reason && <div className="shop-product-reason">{item.reason}</div>}
              </div>
              <TrendTag trend={item.trend} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function SalesTrend({ overview }: { overview: ShopOverview }) {
  const maxSales = Math.max(...overview.salesTrend.map(item => Number(item.salesAmount || 0)), 1)
  return (
    <div className="shop-panel shop-panel-wide">
      <h2>销售趋势</h2>
      {overview.salesTrend.length === 0 ? (
        <div className="shop-empty-small">暂无销售趋势数据</div>
      ) : (
        <div className="shop-trend-chart">
          {overview.salesTrend.map(item => {
            const height = Math.max(8, Number(item.salesAmount || 0) / maxSales * 100)
            return (
              <div className="shop-trend-column" key={item.date} title={`${item.date} ${money(item.salesAmount)} / ${item.orderCount} 单`}>
                <div className="shop-trend-bar-wrap">
                  <div className="shop-trend-bar" style={{ height: `${height}%` }} />
                </div>
                <span>{item.date.slice(5)}</span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function CustomerProfile({ overview }: { overview: ShopOverview }) {
  const { customers } = overview
  return (
    <div className="shop-panel">
      <h2>客户画像</h2>
      <div className="shop-customer-stats">
        <div><strong>{customers.newCustomerCount}</strong><span>新客</span></div>
        <div><strong>{customers.repeatCustomerCount}</strong><span>复购用户</span></div>
        <div><strong>{customers.highValueCustomerCount}</strong><span>高价值客户</span></div>
      </div>
      <div className="shop-ratio-row">
        <span>女性 {Number(customers.femaleRatio || 0).toFixed(0)}%</span>
        <div><i style={{ width: `${customers.femaleRatio || 0}%` }} /></div>
        <span>男性 {Number(customers.maleRatio || 0).toFixed(0)}%</span>
      </div>
      <div className="shop-profile-grid">
        <div>
          <h3>地域 Top</h3>
          {customers.topRegions.map(item => <p key={item.name}>{item.name}<span>{item.value}%</span></p>)}
        </div>
        <div>
          <h3>年龄分布</h3>
          {customers.ageDistribution.map(item => <p key={item.name}>{item.name}<span>{item.value}%</span></p>)}
        </div>
      </div>
    </div>
  )
}

function ReplenishmentTable({ items }: { items: ShopReplenishmentSuggestion[] }) {
  return (
    <div className="shop-panel shop-panel-wide">
      <h2>补货建议</h2>
      {items.length === 0 ? (
        <div className="shop-empty-small">暂无紧急补货建议</div>
      ) : (
        <div className="shop-table-wrap">
          <table className="shop-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>库存</th>
                <th>日均销量</th>
                <th>可售天数</th>
                <th>建议补货</th>
                <th>优先级</th>
              </tr>
            </thead>
            <tbody>
              {items.map(item => (
                <tr key={item.productId}>
                  <td>{item.productName}</td>
                  <td>{item.stock}</td>
                  <td>{Number(item.avgDailySales).toFixed(2)}</td>
                  <td>{Number(item.estimatedDaysLeft).toFixed(2)}</td>
                  <td>{item.suggestedReplenishment}</td>
                  <td><span className={`shop-priority ${item.priority}`}>{item.priority === 'high' ? '高' : item.priority === 'medium' ? '中' : '低'}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function ActivitySuggestions({ items }: { items: ShopActivitySuggestion[] }) {
  return (
    <div className="shop-panel">
      <h2>活动建议</h2>
      {items.length === 0 ? (
        <div className="shop-empty-small">暂无活动建议</div>
      ) : (
        <div className="shop-suggestion-list">
          {items.map(item => (
            <div className={`shop-suggestion ${item.priority}`} key={`${item.type}-${item.title}`}>
              <strong>{item.title}</strong>
              <p>{item.description}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default function ShopAnalytics() {
  const [stores, setStores] = useState<ShopStore[]>([])
  const [storeId, setStoreId] = useState<number | undefined>()
  const [range, setRange] = useState(7)
  const [overview, setOverview] = useState<ShopOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [acting, setActing] = useState(false)
  const [message, setMessage] = useState('')

  const selectedStore = useMemo(() => stores.find(store => store.id === storeId), [stores, storeId])

  const loadStores = async () => {
    const res = await fetchShopStores()
    if (res?.code === 200) {
      setStores(res.data || [])
      if (!storeId && res.data?.length > 0) setStoreId(res.data[0].id)
    }
  }

  const loadOverview = async (nextStoreId = storeId, nextRange = range) => {
    setLoading(true)
    try {
      const res = await fetchShopOverview(nextStoreId, nextRange)
      if (res?.code === 200) setOverview(res.data)
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadStores().catch(() => setLoading(false))
  }, [])

  useEffect(() => {
    loadOverview(storeId, range)
  }, [storeId, range])

  const handleDemoData = async () => {
    setActing(true)
    setMessage('')
    try {
      const res = await generateShopDemoData(storeId)
      if (res?.code === 200) {
        setMessage('模拟数据已生成')
        await loadStores()
        await loadOverview(storeId, range)
      }
    } finally {
      setActing(false)
    }
  }

  const handleAiReport = async () => {
    setActing(true)
    setMessage('')
    try {
      const res = await generateShopAiReport(storeId)
      if (res?.code === 200) {
        setMessage('AI 经营日报已生成')
        await loadOverview(storeId, range)
      }
    } finally {
      setActing(false)
    }
  }

  if (loading && !overview) return <div className="loading">加载中...</div>

  return (
    <div className="shop-analytics-page">
      <div className="shop-hero">
        <div>
          <h1>店铺分析</h1>
          <p>适用于淘宝、抖店、视频号小店、拼多多、快手小店用户，先用模拟经营数据跑通分析闭环。</p>
          <div className="shop-platform-tags">
            <span>淘宝</span><span>抖店</span><span>视频号小店</span><span>拼多多</span><span>快手小店</span>
          </div>
        </div>
        <div className="shop-actions">
          <select value={storeId || ''} onChange={e => setStoreId(Number(e.target.value) || undefined)}>
            {stores.length === 0 ? <option value="">默认店铺</option> : stores.map(store => (
              <option key={store.id} value={store.id}>{store.storeName} · {platformLabels[store.platform] || store.platform}</option>
            ))}
          </select>
          <select value={range} onChange={e => setRange(Number(e.target.value))}>
            <option value={7}>近 7 日</option>
            <option value={30}>近 30 日</option>
          </select>
          <button disabled={acting} onClick={handleDemoData}>生成模拟数据</button>
          <button disabled={acting || !overview} onClick={handleAiReport}>生成 AI 经营日报</button>
        </div>
      </div>

      {message && <div className="shop-message">{message}</div>}

      {!overview ? (
        <div className="shop-empty-state">
          <h2>暂无店铺分析数据</h2>
          <p>点击“生成模拟数据”后，可查看今日经营、商品排行、客户画像、销售趋势和补货建议。</p>
          <button disabled={acting} onClick={handleDemoData}>生成模拟数据</button>
        </div>
      ) : (
        <>
          <div className="shop-store-line">
            当前店铺：<strong>{selectedStore?.storeName || '默认店铺'}</strong>
            <span>{platformLabels[selectedStore?.platform || 'manual']}</span>
          </div>

          <div className="shop-metrics-grid">
            <MetricCard label="今日销售额" value={money(overview.today.salesAmount)} hint={`较昨日 ${rate(overview.today.salesChangeRate)}`} tone="green" />
            <MetricCard label="今日订单数" value={`${overview.today.orderCount} 单`} hint={`较昨日 ${rate(overview.today.orderChangeRate)}`} tone="purple" />
            <MetricCard label="客单价" value={money(overview.today.averageOrderValue)} hint={`${overview.today.buyerCount} 位买家`} />
            <MetricCard label="复购用户" value={`${overview.today.repeatCustomerCount} 人`} hint={`高价值客户 ${overview.customers.highValueCustomerCount} 人`} />
          </div>

          <div className="shop-grid-two">
            <ProductList title="爆款商品" items={overview.hotProducts} empty="暂无爆款商品" />
            <ProductList title="滞销商品" items={overview.slowProducts} empty="暂无明显滞销商品" />
          </div>

          <div className="shop-grid-two">
            <CustomerProfile overview={overview} />
            <ActivitySuggestions items={overview.activitySuggestions} />
          </div>

          <SalesTrend overview={overview} />
          <ReplenishmentTable items={overview.replenishmentSuggestions} />

          <div className="shop-panel shop-panel-wide shop-ai-report">
            <div className="shop-section-header">
              <h2>AI 经营日报</h2>
              <button disabled={acting} onClick={handleAiReport}>重新生成</button>
            </div>
            {overview.aiReport?.content ? (
              <ReactMarkdown>{overview.aiReport.content}</ReactMarkdown>
            ) : (
              <div className="shop-empty-small">暂无经营日报，点击“生成 AI 经营日报”后查看。</div>
            )}
          </div>
        </>
      )}
    </div>
  )
}
