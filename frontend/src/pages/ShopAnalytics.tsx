import { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import {
  confirmShopCsv,
  downloadShopCsvTemplate,
  fetchShopAiReport,
  fetchShopAiReportHistory,
  fetchShopOverview,
  createShopStore,
  fetchShopStores,
  generateShopAiReport,
  generateShopDemoData,
  previewShopCsv,
  type ShopActivitySuggestion,
  type ShopAiReport,
  type ShopAiReportPage,
  type ShopImportPreview,
  type ShopImportType,
  type ShopOverview,
  type ShopProductRank,
  type ShopReplenishmentSuggestion,
  type ShopStore,
} from '../api/shopAnalytics'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoShopReportPage, demoShopStores, getDemoShopOverview } from '../demo/fixtures'
import './ShopAnalytics.css'

const platformLabels: Record<string, string> = {
  manual: '手动/模拟', taobao: '淘宝', jd: '京东', douyin: '抖店', wechat_shop: '视频号小店', pdd: '拼多多', kuaishou: '快手小店',
}
const platformOptions = [
  { value: 'taobao', label: '淘宝' },
  { value: 'jd', label: '京东' },
  { value: 'douyin', label: '抖店' },
  { value: 'wechat_shop', label: '视频号小店' },
  { value: 'pdd', label: '拼多多' },
  { value: 'kuaishou', label: '快手小店' },
  { value: 'manual', label: '手动/模拟' },
]
const importLabels: Record<ShopImportType, string> = {
  PRODUCT: '商品基础数据', STORE_DAILY: '店铺每日经营数据', PRODUCT_DAILY: '商品每日销售与库存',
}

function money(value?: number) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function rate(value?: number) {
  const num = Number(value || 0)
  return `${num > 0 ? '+' : ''}${num.toFixed(2)}%`
}
function errorText(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } }; message?: string }
  return candidate?.response?.data?.message || candidate?.message || fallback
}

function MetricCard({ label, value, hint, tone }: { label: string; value: string | number; hint?: string; tone?: string }) {
  return <div className={`shop-metric-card ${tone || ''}`}><span className="shop-metric-label">{label}</span><strong className="shop-metric-value">{value}</strong>{hint && <span className="shop-metric-hint">{hint}</span>}</div>
}
function TrendTag({ trend, slow }: { trend: string; slow?: boolean }) {
  const text = slow
    ? trend === 'up' ? '低销·回升' : trend === 'down' ? '低销·下降' : '低销·稳定'
    : trend === 'up' ? '上升' : trend === 'down' ? '下降' : '稳定'
  return <span className={`shop-trend-tag ${trend}`}>{text}</span>
}
function ProductList({ title, items, empty, slow }: { title: string; items: ShopProductRank[]; empty: string; slow?: boolean }) {
  return <div className="shop-panel"><h2>{title}</h2>{items.length === 0 ? <div className="shop-empty-small">{empty}</div> : <div className="shop-product-list">{items.map(item => <div className="shop-product-item" key={item.productId}><div><div className="shop-product-name">{item.productName}</div><div className="shop-product-meta">销量 {item.quantitySold} 件 · 销售额 {money(item.salesAmount)} · 库存 {item.stock}</div>{item.reason && <div className="shop-product-reason">{item.reason}</div>}</div><TrendTag trend={item.trend} slow={slow} /></div>)}</div>}</div>
}
function SalesTrend({ overview }: { overview: ShopOverview }) {
  const maxSales = Math.max(...overview.salesTrend.map(item => Number(item.salesAmount || 0)), 1)
  return <div className="shop-panel shop-panel-wide"><h2>销售趋势</h2><div className="shop-trend-chart">{overview.salesTrend.map(item => <div className="shop-trend-column" key={item.date} title={`${item.date} ${money(item.salesAmount)} / ${item.orderCount} 单`}><div className="shop-trend-bar-wrap"><div className="shop-trend-bar" style={{ height: `${Math.max(3, Number(item.salesAmount || 0) / maxSales * 100)}%` }} /></div><span>{item.date.slice(5)}</span></div>)}</div></div>
}
function CustomerProfile({ overview }: { overview: ShopOverview }) {
  const { customers } = overview
  return <div className="shop-panel"><h2>客户画像</h2><div className="shop-customer-stats"><div><strong>{customers.newCustomerCount}</strong><span>新客</span></div><div><strong>{customers.repeatCustomerCount}</strong><span>复购用户</span></div><div><strong>{customers.highValueCustomerCount}</strong><span>高价值客户</span></div></div><div className="shop-ratio-row"><span>女性 {Number(customers.femaleRatio || 0).toFixed(0)}%</span><div><i style={{ width: `${customers.femaleRatio || 0}%` }} /></div><span>男性 {Number(customers.maleRatio || 0).toFixed(0)}%</span></div><div className="shop-profile-grid"><div><h3>地域 Top</h3>{customers.topRegions.map(item => <p key={item.name}>{item.name}<span>{item.value}%</span></p>)}</div><div><h3>年龄分布</h3>{customers.ageDistribution.map(item => <p key={item.name}>{item.name}<span>{item.value}%</span></p>)}</div></div></div>
}
function ReplenishmentTable({ items }: { items: ShopReplenishmentSuggestion[] }) {
  return <div className="shop-panel shop-panel-wide"><h2>补货建议</h2>{items.length === 0 ? <div className="shop-empty-small">暂无紧急补货建议</div> : <div className="shop-table-wrap"><table className="shop-table"><thead><tr><th>商品</th><th>库存</th><th>近7日日均</th><th>可售天数</th><th>建议补货</th><th>优先级</th></tr></thead><tbody>{items.map(item => <tr key={item.productId}><td>{item.productName}</td><td>{item.stock}</td><td>{Number(item.avgDailySales).toFixed(2)}</td><td>{Number(item.estimatedDaysLeft).toFixed(2)}</td><td>{item.suggestedReplenishment}</td><td><span className={`shop-priority ${item.priority}`}>{item.priority === 'high' ? '高' : item.priority === 'medium' ? '中' : '低'}</span></td></tr>)}</tbody></table></div>}</div>
}
function ActivitySuggestions({ items }: { items: ShopActivitySuggestion[] }) {
  return <div className="shop-panel"><h2>活动建议</h2>{items.length === 0 ? <div className="shop-empty-small">暂无活动建议</div> : <div className="shop-suggestion-list">{items.map(item => <div className={`shop-suggestion ${item.priority}`} key={`${item.type}-${item.title}`}><strong>{item.title}</strong><p>{item.description}</p></div>)}</div>}</div>
}

function CsvImportModal({ storeId, onClose, onImported }: { storeId: number; onClose: () => void; onImported: () => Promise<void> }) {
  const [type, setType] = useState<ShopImportType>('PRODUCT')
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ShopImportPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const resetFile = () => { setFile(null); setPreview(null); setError('') }
  const handlePreview = async () => {
    if (!file) return setError('请选择 CSV 文件')
    setBusy(true); setError('')
    try {
      const res = await previewShopCsv(storeId, type, file)
      if (res?.code !== 200) throw new Error(res?.message || '预览失败')
      setPreview(res.data)
    } catch (e) { setError(errorText(e, 'CSV 预览失败')) } finally { setBusy(false) }
  }
  const handleConfirm = async () => {
    if (!file || !preview || preview.errors.length > 0) return
    setBusy(true); setError('')
    try {
      const res = await confirmShopCsv(storeId, type, preview.fileHash, file)
      if (res?.code !== 200) throw new Error(res?.message || '导入失败')
      await onImported(); onClose()
    } catch (e) { setError(errorText(e, 'CSV 导入失败')) } finally { setBusy(false) }
  }
  const headers = preview?.previewRows[0] ? Object.keys(preview.previewRows[0]) : []
  return <div className="shop-modal-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}><div className="shop-modal"><div className="shop-section-header"><h2>导入真实数据</h2><button className="shop-btn-secondary" onClick={onClose}>关闭</button></div><p className="shop-modal-help">仅支持 UTF-8 CSV。建议按“商品基础数据 → 店铺每日数据 → 商品每日数据”的顺序导入。</p><div className="shop-import-controls"><select value={type} onChange={e => { setType(e.target.value as ShopImportType); resetFile() }}>{Object.entries(importLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><button className="shop-btn-secondary" onClick={() => downloadShopCsvTemplate(type)}>下载模板</button><input type="file" accept=".csv,text/csv" onChange={e => { setFile(e.target.files?.[0] || null); setPreview(null); setError('') }} /><button disabled={busy || !file} onClick={handlePreview}>{busy ? '处理中...' : '预览校验'}</button></div>{error && <div className="shop-message error">{error}</div>}{preview && <><div className="shop-import-summary"><span>总行数：{preview.totalRows}</span><span>有效：{preview.validRows}</span><span className={preview.errors.length ? 'has-error' : ''}>错误：{preview.errors.length}</span></div>{preview.errors.length > 0 && <div className="shop-import-errors">{preview.errors.slice(0, 50).map((item, index) => <div key={`${item.row}-${item.field}-${index}`}>第 {item.row} 行 · {item.field}：{item.message}</div>)}</div>}{preview.previewRows.length > 0 && <div className="shop-table-wrap"><table className="shop-table"><thead><tr>{headers.map(header => <th key={header}>{header}</th>)}</tr></thead><tbody>{preview.previewRows.map((row, index) => <tr key={index}>{headers.map(header => <td key={header}>{row[header]}</td>)}</tr>)}</tbody></table></div>}<div className="shop-modal-actions"><button disabled={busy || preview.errors.length > 0 || preview.validRows === 0} onClick={handleConfirm}>确认导入并覆盖重复数据</button></div></>}</div></div>
}

function EmptyAnalysis({
  storeName, platformLabel, multiStore, storeId, isDemo, acting, onImport, onDemo,
}: {
  storeName: string
  platformLabel: string
  multiStore: boolean
  storeId?: number
  isDemo: boolean
  acting: boolean
  onImport: () => void
  onDemo: () => void
}) {
  return (
    <div className="shop-empty-state">
      <h2>{storeName} 还没有经营数据</h2>
      <p>
        {multiStore
          ? `当前看的是「${storeName}」（${platformLabel}）。每家店的数据分开计算，切换店铺不会混在一起。导入 UTF-8 CSV，或先生成演示数据看完整流程。`
          : '这家店还没有可分析的销售日。导入 UTF-8 CSV，或先生成演示数据看完整流程。'}
      </p>
      {!isDemo && storeId && (
        <div className="shop-empty-actions">
          <button type="button" onClick={onImport}>导入真实数据</button>
          <button type="button" className="shop-btn-secondary" disabled={acting} onClick={onDemo}>生成演示数据</button>
        </div>
      )}
    </div>
  )
}

function CreateStoreModal({ onClose, onCreated }: { onClose: () => void; onCreated: (store: ShopStore) => Promise<void> }) {
  const [platform, setPlatform] = useState('jd')
  const [storeName, setStoreName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const handleCreate = async () => {
    const name = storeName.trim()
    if (!name) return setError('请填写店铺名称')
    setBusy(true); setError('')
    try {
      const res = await createShopStore({ platform, storeName: name })
      if (res?.code !== 200) throw new Error(res?.message || '创建店铺失败')
      await onCreated(res.data)
      onClose()
    } catch (e) { setError(errorText(e, '创建店铺失败')) } finally { setBusy(false) }
  }
  return (
    <div className="shop-modal-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div className="shop-modal">
        <div className="shop-section-header">
          <h2>添加店铺</h2>
          <button className="shop-btn-secondary" onClick={onClose}>关闭</button>
        </div>
        <p className="shop-modal-help">平台只是分类，不会去连京东或淘宝后台。数据仍靠 CSV 导入或演示数据。</p>
        <div className="shop-create-fields">
          <label>
            <span>平台</span>
            <select value={platform} onChange={e => setPlatform(e.target.value)}>
              {platformOptions.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <label>
            <span>店铺名称</span>
            <input value={storeName} maxLength={100} placeholder="例如：京东旗舰店" onChange={e => setStoreName(e.target.value)} />
          </label>
        </div>
        {error && <div className="shop-message error">{error}</div>}
        <div className="shop-modal-actions">
          <button disabled={busy} onClick={handleCreate}>{busy ? '创建中...' : '创建店铺'}</button>
        </div>
      </div>
    </div>
  )
}

function ReportHistory({ storeId, latest, onLatestChange, isDemo }: { storeId: number; latest?: ShopAiReport | null; onLatestChange: (report: ShopAiReport) => void; isDemo: boolean }) {
  const [pageData, setPageData] = useState<ShopAiReportPage | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const requestId = useRef(0)
  const load = async () => {
    if (isDemo) {
      setPageData(demoShopReportPage)
      setLoading(false)
      return
    }
    const currentRequest = ++requestId.current
    setLoading(true); setError('')
    try {
      const res = await fetchShopAiReportHistory(storeId, page)
      if (currentRequest !== requestId.current) return
      if (res?.code !== 200) throw new Error(res?.message || '获取日报历史失败')
      setPageData(res.data)
    } catch (e) {
      if (currentRequest === requestId.current) setError(errorText(e, '获取日报历史失败'))
    } finally {
      if (currentRequest === requestId.current) setLoading(false)
    }
  }
  useEffect(() => { setPage(1) }, [storeId])
  useEffect(() => { load() }, [storeId, page, latest?.id, isDemo])
  const select = async (id: number) => {
    if (isDemo) {
      const report = demoShopReportPage.records.find(item => item.id === id)
      if (report) onLatestChange(report)
      return
    }
    try {
      const res = await fetchShopAiReport(storeId, id)
      if (res?.code === 200) onLatestChange(res.data)
    } catch (e) { setError(errorText(e, '获取日报详情失败')) }
  }
  return <div className="shop-report-history"><h3>历史日报</h3>{error && <div className="shop-message error">{error} <button onClick={load}>重试</button></div>}{loading ? <div className="shop-empty-small">加载历史中...</div> : pageData?.records.length ? <><div className="shop-history-list">{pageData.records.map(report => <button className={latest?.id === report.id ? 'active' : ''} key={report.id} onClick={() => select(report.id)}><strong>{report.reportDate}</strong><span>{report.summary}</span></button>)}</div><div className="shop-history-pagination"><button disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</button><span>{pageData.current} / {Math.max(pageData.pages, 1)}</span><button disabled={page >= pageData.pages} onClick={() => setPage(value => value + 1)}>下一页</button></div></> : <div className="shop-empty-small">暂无历史日报</div>}</div>
}

export default function ShopAnalytics() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [stores, setStores] = useState<ShopStore[]>([])
  const [storeId, setStoreId] = useState<number | undefined>()
  const [range, setRange] = useState(7)
  const [overview, setOverview] = useState<ShopOverview | null>(null)
  const [selectedReport, setSelectedReport] = useState<ShopAiReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [acting, setActing] = useState(false)
  const [notice, setNotice] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [showImport, setShowImport] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const overviewRequestId = useRef(0)
  const selectedStore = useMemo(() => stores.find(store => store.id === storeId), [stores, storeId])

  const loadStores = async () => {
    if (isDemo) {
      setStores(demoShopStores)
      setStoreId(current => current || demoShopStores[0].id)
      return
    }
    try {
      const res = await fetchShopStores()
      if (res?.code !== 200) throw new Error(res?.message || '加载店铺失败')
      const nextStores = res.data || []
      setStores(nextStores)
      if (!storeId && nextStores.length > 0) setStoreId(nextStores[0].id)
    } catch (e) { setNotice({ type: 'error', text: errorText(e, '加载店铺失败') }) }
  }
  const loadOverview = async (nextStoreId = storeId, nextRange = range) => {
    if (isDemo) {
      const data = getDemoShopOverview()
      setOverview(data)
      setSelectedReport(data.aiReport || null)
      setLoading(false)
      return
    }
    const currentRequest = ++overviewRequestId.current
    setLoading(true)
    try {
      const res = await fetchShopOverview(nextStoreId, nextRange)
      if (currentRequest !== overviewRequestId.current) return
      if (res?.code !== 200) throw new Error(res?.message || '加载分析数据失败')
      setOverview(res.data); setSelectedReport(res.data?.aiReport || null)
    } catch (e) {
      if (currentRequest === overviewRequestId.current) {
        setOverview(null); setNotice({ type: 'error', text: errorText(e, '加载分析数据失败') })
      }
    } finally {
      if (currentRequest === overviewRequestId.current) setLoading(false)
    }
  }
  useEffect(() => { loadStores() }, [isDemo])
  useEffect(() => {
    if (!storeId) return
    setOverview(null); setSelectedReport(null); setShowImport(false); loadOverview(storeId, range)
  }, [storeId, range, isDemo])

  const handleDemoData = async () => {
    if (isDemo) return
    setActing(true); setNotice(null)
    try {
      const res = await generateShopDemoData(storeId)
      if (res?.code !== 200) throw new Error(res?.message || '生成模拟数据失败')
      await loadStores(); await loadOverview(storeId, range)
      setNotice({ type: 'success', text: '演示模拟数据已生成' })
    } catch (e) { setNotice({ type: 'error', text: errorText(e, '生成模拟数据失败') }) } finally { setActing(false) }
  }
  const handleAiReport = async () => {
    if (isDemo) return
    setActing(true); setNotice(null)
    try {
      const res = await generateShopAiReport(storeId)
      if (res?.code !== 200) throw new Error(res?.message || '生成经营日报失败')
      setSelectedReport(res.data); await loadOverview(storeId, range)
      setNotice({ type: 'success', text: '经营日报已生成' })
    } catch (e) { setNotice({ type: 'error', text: errorText(e, '生成经营日报失败') }) } finally { setActing(false) }
  }
  const handleImported = async () => {
    if (isDemo) return
    await loadOverview(storeId, range)
    setNotice({ type: 'success', text: '真实数据导入成功，分析结果已刷新' })
  }
  const handleCreated = async (store: ShopStore) => {
    await loadStores()
    setStoreId(store.id)
    setNotice({ type: 'success', text: `已添加${platformLabels[store.platform] || ''}店铺` })
  }
  const hasSalesData = (overview?.effectiveDays ?? 0) > 0
  const currentStoreName = selectedStore?.storeName || '我的店铺'
  const currentPlatformLabel = platformLabels[selectedStore?.platform || 'manual'] || selectedStore?.platform || '手动/模拟'
  const isToday = hasSalesData && overview?.analysisDate === new Date().toLocaleDateString('sv-SE')
  const metricPrefix = isToday ? '今日' : '最新'

  if (loading && !overview) return <div className="loading">加载中...</div>
  return <div className="shop-analytics-page">
    {isDemo && <DemoNotice />}
    <div className="shop-hero"><div><h1>店铺分析</h1><p>导入真实经营数据，分析销售、商品、客户和库存；也可生成演示数据体验功能。</p><div className="shop-platform-tags">{platformOptions.filter(item => item.value !== 'manual').map(item => <span key={item.value}>{item.label}</span>)}</div></div><div className="shop-actions"><select value={storeId || ''} onChange={e => setStoreId(Number(e.target.value) || undefined)}>{stores.length === 0 ? <option value="">加载店铺中...</option> : stores.map(store => <option key={store.id} value={store.id}>{store.storeName} · {platformLabels[store.platform] || store.platform}</option>)}</select><select value={range} disabled={isDemo} onChange={e => setRange(Number(e.target.value))}><option value={7}>近 7 日</option><option value={30}>近 30 日</option></select><button className="shop-btn-secondary" disabled={isDemo} onClick={() => { if (!isDemo) setShowCreate(true) }}>添加店铺</button><button disabled={isDemo || !storeId} onClick={() => { if (!isDemo) setShowImport(true) }}>导入真实数据</button><button className="shop-btn-secondary" disabled={isDemo || acting} onClick={handleDemoData}>生成演示数据</button><button disabled={isDemo || acting || !hasSalesData} onClick={handleAiReport}>生成经营日报</button></div></div>
    {notice && <div className={`shop-message ${notice.type}`}>{notice.text}{notice.type === 'error' && <button onClick={() => loadOverview(storeId, range)}>重试</button>}</div>}
    {!overview || !hasSalesData ? <EmptyAnalysis storeName={currentStoreName} platformLabel={currentPlatformLabel} multiStore={stores.length > 1} storeId={storeId} isDemo={!!isDemo} acting={acting} onImport={() => setShowImport(true)} onDemo={handleDemoData} /> : <><div className="shop-store-line">当前店铺：<strong>{currentStoreName}</strong><span>{platformLabels[selectedStore?.platform || 'manual']}</span><em>分析日期：{overview.analysisDate} · 近 {overview.requestedRange} 日 · {overview.effectiveDays} 个有效数据日</em></div>{!isToday && <div className="shop-data-date-warning">今天暂无经营汇总，当前展示最近数据日 {overview.analysisDate}，不会将历史数据冒充今日。</div>}<div className="shop-metrics-grid"><MetricCard label={`${metricPrefix}销售额`} value={money(overview.today.salesAmount)} hint={`较前一日 ${rate(overview.today.salesChangeRate)}`} tone="green" /><MetricCard label={`${metricPrefix}订单数`} value={`${overview.today.orderCount} 单`} hint={`较前一日 ${rate(overview.today.orderChangeRate)}`} tone="purple" /><MetricCard label="客单价" value={money(overview.today.averageOrderValue)} hint={`${overview.today.buyerCount} 位买家`} /><MetricCard label="复购用户" value={`${overview.today.repeatCustomerCount} 人`} hint={`高价值客户 ${overview.customers.highValueCustomerCount} 人`} /></div><div className="shop-grid-two"><ProductList title={`爆款商品 · 近 ${overview.requestedRange} 日`} items={overview.hotProducts} empty="暂无爆款商品" /><ProductList title={`滞销商品 · 近 ${overview.requestedRange} 日`} items={overview.slowProducts} empty="暂无明显滞销商品" slow /></div><div className="shop-grid-two"><CustomerProfile overview={overview} /><ActivitySuggestions items={overview.activitySuggestions} /></div><SalesTrend overview={overview} /><ReplenishmentTable items={overview.replenishmentSuggestions} /><div className="shop-panel shop-panel-wide shop-ai-report"><div className="shop-section-header"><h2>经营日报</h2><button disabled={isDemo || acting} onClick={handleAiReport}>重新生成</button></div><div className="shop-report-layout"><div className="shop-report-content">{selectedReport?.content ? <ReactMarkdown>{selectedReport.content}</ReactMarkdown> : <div className="shop-empty-small">暂无经营日报，点击生成后查看。</div>}</div>{storeId && <ReportHistory storeId={storeId} latest={selectedReport} onLatestChange={setSelectedReport} isDemo={isDemo} />}</div></div></>}
    {!isDemo && showImport && storeId && <CsvImportModal storeId={storeId} onClose={() => setShowImport(false)} onImported={handleImported} />}
    {!isDemo && showCreate && <CreateStoreModal onClose={() => setShowCreate(false)} onCreated={handleCreated} />}
  </div>
}
