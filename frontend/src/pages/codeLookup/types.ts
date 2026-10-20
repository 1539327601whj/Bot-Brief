/**
 * 后端 `CodeLookupV2DTO` 的镜像。字段名一一对应，改一处必须同时改两边。
 *
 * 这里刻意**不把可空字段收窄成非空**：断数据时后端返回 null，页面必须显示原因，
 * 而不是靠 `!` 或默认值把 null 变成 0。
 */

export type AssetType = 'STOCK' | 'ETF' | 'INDEX'

export interface DomainView {
  status: string
  note: string | null
  asOf: string | null
}

export interface QuoteView {
  provider: string | null
  providerLabel: string | null
  price: number | null
  pctChange: number | null
  amount: number | null
  turnoverRate: number | null
  marketCap: number | null
  peTtm: number | null
  pb: number | null
  dividendYield: number | null
  asOf: string | null
}

export interface ValuationView {
  source: string | null
  sourceLabel: string | null
  method: string | null
  peTtm: number | null
  pePercentile: number | null
  peWindowFrom: string | null
  pePoints: number
  pbMrq: number | null
  pbPercentile: number | null
  dividendYield: number | null
  historyLength: number | null
  asOf: string | null
  note: string | null
}

export interface TechnicalView {
  barCount: number
  lastDate: string | null
  price: number | null
  hasOhlc: boolean
  ma20: number | null
  ma60: number | null
  ma120: number | null
  ma250: number | null
  atr14: number | null
  low20: number | null
  low60: number | null
  low120: number | null
  high1y: number | null
  low1y: number | null
  pricePercentile1y: number | null
  momentum20d: number | null
  momentum60d: number | null
}

export interface ReasonView {
  text: string
  evidenceIds: string[]
}

export interface VerdictView {
  label: string
  labelKey: string
  score: number | null
  scoreLow: number | null
  scoreHigh: number | null
  coverage: number | null
  confidence: number | null
  eventAdjustment: number | null
  hardFlags: string[]
  reasons: ReasonView[]
  risks: ReasonView[]
  withheldReason: string | null
}

export interface MetricView {
  key: string
  label: string
  value: number | null
  display: string | null
  score: number | null
  weight: number
  basis: string | null
  evidenceId: string | null
}

export interface DimensionView {
  key: string
  label: string
  weight: number
  score: number | null
  coverage: number | null
  status: string
  metrics: MetricView[]
  reasons: string[]
}

export interface BuyZoneView {
  status: string
  low: number | null
  high: number | null
  stopLoss: number | null
  distanceToZonePct: number | null
  riskPct: number | null
  basis: ReasonView[]
  notes: string[]
}

export interface LookbackView {
  label: string
  baselineDate: string | null
  baseline: number | null
  change: number | null
  status: string | null
}

export interface CoreView {
  inputCode: string
  code: string
  name: string | null
  assetType: AssetType
  market: string | null
  industry: string | null
  sectorProfile: string | null
  trackingIndexCode: string | null
  snapshotId: string
  snapshotAt: string
  fromCache: boolean
  syncPending: boolean
  retryAfterMs: number
  quote: QuoteView | null
  valuation: ValuationView | null
  technical: TechnicalView | null
  verdict: VerdictView
  dimensions: DimensionView[]
  buyZone: BuyZoneView | null
  priceLookbacks: LookbackView[]
  peLookbacks: LookbackView[]
  data: Record<string, DomainView>
  notes: string[]
  rulesVersion: string
  disclaimer: string
}

export interface FinancialView {
  reportDate: string
  reportType: string | null
  announceDate: string | null
  revenue: number | null
  revenueYoy: number | null
  netProfit: number | null
  netProfitYoy: number | null
  roe: number | null
  grossMargin: number | null
  netMargin: number | null
  ocf: number | null
  totalAssets: number | null
  totalEquity: number | null
  totalLiabilities: number | null
  currentAssets: number | null
  currentLiabilities: number | null
  interestExpense: number | null
  ebit: number | null
  goodwill: number | null
  accountsReceivable: number | null
  source: string | null
}

export interface MoneyFlowView {
  tradeDate: string
  mainNet: number | null
  superNet: number | null
  largeNet: number | null
  mediumNet: number | null
  smallNet: number | null
  mainRatio: number | null
  source: string | null
}

export interface IndexProfileView {
  indexCode: string
  name: string | null
  asOf: string | null
  constituentCount: number | null
  top10Weight: number | null
  peTtm: number | null
  dividendYield: number | null
  totalMarketCap: number | null
  source: string | null
}

export interface FundProfileView {
  code: string
  name: string | null
  fullName: string | null
  fundType: string | null
  company: string | null
  custodian: string | null
  manager: string | null
  inceptionDate: string | null
  inceptionScale: string | null
  /** 基金净资产，单位：元。显示前除以 1e8 换成亿元。 */
  aum: number | null
  aumAsOf: string | null
  managementFeePct: number | null
  custodyFeePct: number | null
  benchmark: string | null
  trackingTarget: string | null
  trackingErrorPct: number | null
  peerTrackingErrorPct: number | null
  trackingErrorAsOf: string | null
  source: string | null
}

export interface IndustryView {
  industryCode: string | null
  industryName: string
  weight: number | null
  asOf: string | null
}

export interface EventView {
  category: string | null
  eventType: string | null
  title: string
  summary: string | null
  url: string | null
  publishedAt: string | null
  source: string | null
  importance: string | null
  polarity: string | null
}

export interface Section<T> {
  data: T
  status: string
  note: string | null
  asOf: string | null
}

export interface ScenarioView {
  key: 'bull' | 'base' | 'bear' | string
  label: string
  triggered: boolean
  condition: string
  narrative: string
  evidenceIds: string[]
  invalidation: string
  disclaimer: string
}

export interface DetailView {
  code: string
  snapshotId: string
  snapshotChanged: boolean
  financials: Section<FinancialView[]>
  moneyflow: Section<MoneyFlowView[]>
  indexProfile: Section<IndexProfileView | null>
  fundProfile: Section<FundProfileView | null>
  industry: Section<IndustryView[]>
  events: Section<EventView[]>
  scenarios: ScenarioView[]
  notes: string[]
}

export interface PricePoint {
  date: string
  open: number | null
  high: number | null
  low: number | null
  close: number | null
  volume: number | null
  amount: number | null
  pctChange: number | null
  turnoverRate: number | null
}

export interface ValuationPoint {
  date: string
  peTtm: number | null
  pbMrq: number | null
  dividendYield: number | null
  percentile: number | null
}

export interface MoneyFlowPoint {
  date: string
  mainNet: number | null
  superNet: number | null
  largeNet: number | null
  mediumNet: number | null
  smallNet: number | null
}

export interface SeriesView {
  code: string
  kind: 'price' | 'valuation' | 'moneyflow' | string
  range: string
  interval: string
  price: PricePoint[]
  valuation: ValuationPoint[]
  moneyflow: MoneyFlowPoint[]
  note: string | null
}

export interface JobView {
  task: string
  status: string
  error: string | null
  startedAt: string | null
  finishedAt: string | null
  retryAfter: string | null
}

export interface StatusView {
  code: string
  snapshotId: string
  priceAsOf: string | null
  valuationAsOf: string | null
  financialAsOf: string | null
  eventsAsOf: string | null
  syncPending: boolean
  retryAfterMs: number
  data: Record<string, DomainView>
  jobs: JobView[]
  checkedAt: string
}

export interface BriefSectionView {
  heading: string
  text: string
  evidenceIds: string[]
}

export interface BriefView {
  code: string
  name: string | null
  snapshotId: string
  snapshotChanged: boolean
  available: boolean
  fallback: boolean
  cached: boolean
  title: string
  sections: BriefSectionView[]
  reason: string | null
  note: string | null
  rulesVersion: string
  promptVersion: string
  generatedAt: string
  disclaimer: string
}

export interface SearchHit {
  instrumentCode: string
  code: string
  name: string | null
  assetType: AssetType
  market: string | null
}
