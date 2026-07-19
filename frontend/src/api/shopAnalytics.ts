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

export async function generateShopAiReport(storeId?: number) {
  const res = await api.post('/shop/analytics/ai-report/generate', null, { params: { storeId } })
  return res.data
}
