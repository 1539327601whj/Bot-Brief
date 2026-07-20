import api from '../utils/api'

export interface ShopStore {
  id: number
  platform: string
  storeName: string
  enabled: boolean
}

export interface NameValue {
  name: string
  value: number
}

export interface ShopTodayMetrics {
  salesAmount: number
  orderCount: number
  buyerCount: number
  averageOrderValue: number
  salesChangeRate: number
  orderChangeRate: number
  repeatCustomerCount: number
}

export interface ShopProductRank {
  productId: number
  productName: string
  salesAmount: number
  orderCount: number
  quantitySold: number
  stock: number
  trend: 'up' | 'down' | 'stable'
  trendRate: number
  dataDays: number
  avgDailySales: number
  reason?: string
}

export interface ShopCustomerProfile {
  newCustomerCount: number
  repeatCustomerCount: number
  highValueCustomerCount: number
  avgCustomerValue: number
  femaleRatio: number
  maleRatio: number
  topRegions: NameValue[]
  ageDistribution: NameValue[]
}

export interface ShopSalesTrend {
  date: string
  salesAmount: number
  orderCount: number
  buyerCount: number
}

export interface ShopReplenishmentSuggestion {
  productId: number
  productName: string
  stock: number
  avgDailySales: number
  estimatedDaysLeft: number
  suggestedReplenishment: number
  priority: 'high' | 'medium' | 'low'
  reason: string
}

export interface ShopActivitySuggestion {
  type: string
  title: string
  description: string
  priority: 'high' | 'medium' | 'low'
}

export interface ShopAiReport {
  id: number
  reportDate: string
  title: string
  summary: string
  content: string
  riskLevel: string
  generatedBy: string
  createdAt: string
}

export interface ShopOverview {
  analysisDate: string
  requestedRange: number
  effectiveDays: number
  today: ShopTodayMetrics
  hotProducts: ShopProductRank[]
  slowProducts: ShopProductRank[]
  customers: ShopCustomerProfile
  salesTrend: ShopSalesTrend[]
  replenishmentSuggestions: ShopReplenishmentSuggestion[]
  activitySuggestions: ShopActivitySuggestion[]
  aiReport?: ShopAiReport | null
}

export async function fetchShopStores() {
  const res = await api.get('/shop/stores')
  return res.data
}

export async function createShopStore(payload: Partial<ShopStore>) {
  const res = await api.post('/shop/stores', payload)
  return res.data
}

export async function fetchShopOverview(storeId?: number, range = 7) {
  const res = await api.get('/shop/analytics/overview', { params: { storeId, range } })
  return res.data
}

export async function generateShopDemoData(storeId?: number) {
  const res = await api.post('/shop/analytics/demo-data', null, { params: { storeId } })
  return res.data
}

export type ShopImportType = 'PRODUCT' | 'STORE_DAILY' | 'PRODUCT_DAILY'

export interface ShopImportError {
  row: number
  field: string
  message: string
}

export interface ShopImportPreview {
  type: ShopImportType
  fileHash: string
  totalRows: number
  validRows: number
  errors: ShopImportError[]
  previewRows: Record<string, string>[]
}

export interface ShopAiReportPage {
  records: ShopAiReport[]
  total: number
  pages: number
  current: number
  size: number
}

export async function generateShopAiReport(storeId?: number) {
  const res = await api.post('/shop/analytics/ai-report/generate', null, { params: { storeId } })
  return res.data
}

export async function previewShopCsv(storeId: number, type: ShopImportType, file: File) {
  const form = new FormData()
  form.append('file', file)
  const res = await api.post('/shop/import/preview', form, { params: { storeId, type } })
  return res.data
}

export async function confirmShopCsv(storeId: number, type: ShopImportType, fileHash: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  const res = await api.post('/shop/import/confirm', form, { params: { storeId, type, fileHash } })
  return res.data
}

export async function downloadShopCsvTemplate(type: ShopImportType) {
  const res = await api.get(`/shop/import/templates/${type}`, { responseType: 'blob' })
  const url = URL.createObjectURL(res.data)
  const link = document.createElement('a')
  link.href = url
  link.download = `shop_${type.toLowerCase()}_template.csv`
  link.click()
  URL.revokeObjectURL(url)
}

export async function fetchShopAiReportHistory(storeId: number, page = 1, size = 10) {
  const res = await api.get('/shop/analytics/ai-report/history', { params: { storeId, page, size } })
  return res.data
}

export async function fetchShopAiReport(storeId: number, reportId: number) {
  const res = await api.get(`/shop/analytics/ai-report/${reportId}`, { params: { storeId } })
  return res.data
}
