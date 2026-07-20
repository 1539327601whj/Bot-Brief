import api from '../utils/api'

export interface ContentAccount {
  id: number
  platform: string
  accountName: string
  homepageUrl?: string
  avatarUrl?: string
  followerCount: number
  accountPositioning?: string
  bindStatus: string
}

export interface ContentWork {
  id: number
  accountId: number
  platform: string
  title: string
  coverUrl?: string
  workUrl?: string
  publishTime?: string
  playCount: number
  likeCount: number
  commentCount: number
  collectCount: number
  shareCount: number
  followerGain: number
  contentType?: string
  interactionRate: number
  status: 'hot' | 'normal' | 'declining'
}

export interface AccountPayload {
  platform: string
  accountName: string
  homepageUrl?: string
  avatarUrl?: string
  followerCount: number
  accountPositioning?: string
}

export interface WorkPayload {
  accountId: number
  platform: string
  title: string
  coverUrl?: string
  workUrl?: string
  publishTime?: string
  playCount: number
  likeCount: number
  commentCount: number
  collectCount: number
  shareCount: number
  followerGain: number
  contentType: string
}

export interface WorkImportRow extends Omit<WorkPayload, 'accountId'> {
  rowNumber: number
}

export interface WorkImportError {
  rowNumber: number
  field?: string
  message: string
}

export interface WorkImportResult {
  total: number
  created: number
  updated: number
  skipped: number
  failed: number
  errors: WorkImportError[]
}

export interface WorkImportRequest {
  accountId: number
  conflictStrategy: 'UPDATE' | 'SKIP'
  rows: WorkImportRow[]
}

export interface CompetitorAccount {
  id: number
  platform: string
  accountName: string
  homepageUrl?: string
  note?: string
}

export interface ContentOverview {
  accountCount: number
  totalFollowers: number
  totalPlayCount: number
  totalLikeCount: number
  totalCommentCount: number
  totalCollectCount: number
  totalShareCount: number
  totalFollowerGain: number
  averageInteractionRate: number
  bestWork?: ContentWork
  decliningWork?: ContentWork
}

export interface PageData<T> {
  records: T[]
  total: number
  current: number
  size: number
}

export interface AiTextResponse {
  content: string
  highlights: string[]
  fallback: boolean
}

export const platformOptions = [
  { label: '抖音', value: 'douyin' },
  { label: '小红书', value: 'xiaohongshu' },
  { label: '快手', value: 'kuaishou' },
  { label: 'B站', value: 'bilibili' },
]

export const platformLabel = (platform?: string) => {
  return platformOptions.find(item => item.value === platform)?.label || platform || '-'
}

export const getAccounts = () => api.get('/content-growth/accounts')
export const createAccount = (data: AccountPayload) => api.post('/content-growth/accounts', data)
export const updateAccount = (id: number, data: AccountPayload) => api.put(`/content-growth/accounts/${id}`, data)
export const deleteAccount = (id: number) => api.delete(`/content-growth/accounts/${id}`)

export const getWorks = (params: { accountId?: number; page?: number; size?: number }) => api.get('/content-growth/works', { params })
export const createWork = (data: WorkPayload) => api.post('/content-growth/works', data)
export const updateWork = (id: number, data: WorkPayload) => api.put(`/content-growth/works/${id}`, data)
export const deleteWork = (id: number) => api.delete(`/content-growth/works/${id}`)
export const importWorks = (data: WorkImportRequest) => api.post('/content-growth/works/import', data)

export const getOverview = (accountId?: number) => api.get('/content-growth/overview', { params: accountId ? { accountId } : {} })

export const analyzeHotWork = (data: { accountId?: number; workId?: number }) => api.post('/content-growth/ai/hot-analysis', data)
export const recommendTopics = (data: { accountId?: number; goal?: string; count?: number }) => api.post('/content-growth/ai/topic-recommendations', data)
export const rewriteAdvice = (data: { accountId?: number; draftTitle?: string; draftScript?: string; targetPlatform?: string; goal?: string }) => api.post('/content-growth/ai/rewrite-advice', data)

export const getCompetitors = () => api.get('/content-growth/competitors')
export const createCompetitor = (data: Partial<CompetitorAccount>) => api.post('/content-growth/competitors', data)
export const deleteCompetitor = (id: number) => api.delete(`/content-growth/competitors/${id}`)
