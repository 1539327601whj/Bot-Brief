import dayjs from '../utils/dayjs'
import type {
  AiTextResponse,
  CompetitorAccount,
  ContentAccount,
  ContentOverview,
  ContentWork,
} from '../api/contentGrowth'
import type {
  ShopAiReport,
  ShopAiReportPage,
  ShopOverview,
  ShopStore,
} from '../api/shopAnalytics'

const demoNow = dayjs()

export const demoPushLogs = [
  { id: 501, reportId: 301, channelId: 401, channelType: 'email', status: 'success' as const, errorMessage: null, pushedAt: demoNow.hour(8).minute(15).second(0).toISOString() },
  { id: 502, reportId: 302, channelId: 402, channelType: 'feishu', status: 'success' as const, errorMessage: null, pushedAt: demoNow.hour(20).minute(15).second(0).toISOString() },
  { id: 503, reportId: 299, channelId: 401, channelType: 'email', status: 'failed' as const, errorMessage: '合成示例：目标渠道暂时不可达', pushedAt: demoNow.subtract(1, 'day').hour(20).minute(15).second(0).toISOString() },
]

export const demoContentAccounts: ContentAccount[] = [
  { id: 101, platform: 'douyin', accountName: '城市灵感实验室', homepageUrl: 'https://example.invalid/creator/city-lab', followerCount: 28600, accountPositioning: '城市生活与效率技巧', bindStatus: 'BOUND' },
  { id: 102, platform: 'xiaohongshu', accountName: '慢读科技手册', homepageUrl: 'https://example.invalid/creator/tech-notes', followerCount: 17400, accountPositioning: '通俗科技与数字生活', bindStatus: 'BOUND' },
]

export const demoContentWorks: ContentWork[] = [
  { id: 201, accountId: 101, platform: 'douyin', title: '下班后的一小时，如何重新找回专注', workUrl: 'https://example.invalid/work/focus-hour', publishTime: '2026-07-18T19:30:00+08:00', playCount: 126000, likeCount: 9100, commentCount: 420, collectCount: 2600, shareCount: 780, followerGain: 1150, contentType: 'video', interactionRate: 0.1024, status: 'hot' },
  { id: 202, accountId: 101, platform: 'douyin', title: '三种让通勤更轻松的小习惯', workUrl: 'https://example.invalid/work/commute', publishTime: '2026-07-16T08:10:00+08:00', playCount: 48300, likeCount: 3100, commentCount: 176, collectCount: 980, shareCount: 240, followerGain: 310, contentType: 'video', interactionRate: 0.0931, status: 'normal' },
  { id: 203, accountId: 101, platform: 'douyin', title: '周末城市散步路线设计方法', workUrl: 'https://example.invalid/work/city-walk', publishTime: '2026-07-12T10:20:00+08:00', playCount: 18200, likeCount: 820, commentCount: 65, collectCount: 340, shareCount: 91, followerGain: 82, contentType: 'video', interactionRate: 0.0723, status: 'declining' },
  { id: 204, accountId: 102, platform: 'xiaohongshu', title: '普通人也能看懂的本地 AI 入门', workUrl: 'https://example.invalid/work/local-ai', publishTime: '2026-07-19T12:00:00+08:00', playCount: 89700, likeCount: 7200, commentCount: 390, collectCount: 4100, shareCount: 620, followerGain: 920, contentType: 'image', interactionRate: 0.1372, status: 'hot' },
  { id: 205, accountId: 102, platform: 'xiaohongshu', title: '给数字资料做减法的四个步骤', workUrl: 'https://example.invalid/work/digital-cleanup', publishTime: '2026-07-15T18:45:00+08:00', playCount: 35400, likeCount: 2400, commentCount: 130, collectCount: 1560, shareCount: 205, followerGain: 260, contentType: 'image', interactionRate: 0.1213, status: 'normal' },
]

export const demoCompetitors: CompetitorAccount[] = [
  { id: 601, platform: 'douyin', accountName: '样本创作观察站', homepageUrl: 'https://example.invalid/competitor/observer', note: '合成同行样本' },
  { id: 602, platform: 'xiaohongshu', accountName: '虚构趋势笔记', homepageUrl: 'https://example.invalid/competitor/trends', note: '内容结构参考' },
]

export function getDemoContentOverview(accountId?: number): ContentOverview {
  const accounts = accountId ? demoContentAccounts.filter(item => item.id === accountId) : demoContentAccounts
  const works = accountId ? demoContentWorks.filter(item => item.accountId === accountId) : demoContentWorks
  const sum = (field: keyof Pick<ContentWork, 'playCount' | 'likeCount' | 'commentCount' | 'collectCount' | 'shareCount' | 'followerGain'>) => works.reduce((total, item) => total + item[field], 0)
  const totalPlayCount = sum('playCount')
  const totalLikeCount = sum('likeCount')
  const totalCommentCount = sum('commentCount')
  const totalCollectCount = sum('collectCount')
  const totalShareCount = sum('shareCount')
  const ranked = [...works].sort((a, b) => b.playCount - a.playCount)
  const latest = [...works]
    .filter((item): item is ContentWork & { publishTime: string } => Boolean(item.publishTime))
    .sort((a, b) => b.publishTime.localeCompare(a.publishTime))[0]
  const averagePlayCount = works.length ? totalPlayCount / works.length : 0
  return {
    accountCount: accounts.length,
    totalFollowers: accounts.reduce((total, item) => total + item.followerCount, 0),
    totalPlayCount,
    totalLikeCount,
    totalCommentCount,
    totalCollectCount,
    totalShareCount,
    totalFollowerGain: sum('followerGain'),
    averageInteractionRate: totalPlayCount ? Math.round((totalLikeCount + totalCommentCount + totalCollectCount + totalShareCount) / totalPlayCount * 10000) / 10000 : 0,
    bestWork: ranked[0],
    decliningWork: works.length >= 3 && latest && latest.playCount < averagePlayCount * 0.5 ? latest : undefined,
  }
}

export const demoAiExamples: Record<'hot' | 'topics' | 'rewrite', AiTextResponse> = {
  hot: { fallback: false, highlights: ['开头冲突明确', '步骤可执行', '收藏动机强'], content: '### 预生成爆款分析\n\n示例作品在前三秒给出明确收益，并用三个可执行步骤降低理解成本。固定合成数据表明，收藏和分享贡献了较高的长尾传播。' },
  topics: { fallback: false, highlights: ['专注恢复', '轻量通勤', '城市探索'], content: '### 预生成选题示例\n\n1. **低能量下的专注恢复清单**\n2. **一周通勤体验改造实验**\n3. **不用打卡的城市探索方法**\n\n以上仅用于展示结果布局，不会调用 AI。' },
  rewrite: { fallback: false, highlights: ['标题具体化', '前置用户收益', '结尾增加行动提示'], content: '### 预生成改稿示例\n\n将抽象标题改成包含场景与收益的具体表达；开头先呈现用户痛点，正文保留三个短步骤，结尾用可保存的清单收束。' },
}

export const demoShopStores: ShopStore[] = [
  { id: 701, platform: 'manual', storeName: '合成生活选物店', enabled: true },
]

const demoShopReports: ShopAiReport[] = [
  { id: 901, reportDate: demoNow.format('YYYY-MM-DD'), title: `合成经营日报 · ${demoNow.format('M月D日')}`, summary: '销售稳定，旅行收纳袋贡献主要增量。', content: '## 经营摘要\n\n今日合成销售额保持稳定，**旅行收纳袋**贡献主要增量。\n\n- 建议关注便携水杯库存\n- 周末可测试组合优惠\n- 本报告为预生成示例，未调用 AI', riskLevel: 'LOW', generatedBy: 'DEMO_FIXTURE', createdAt: demoNow.hour(21).minute(0).second(0).toISOString() },
  { id: 902, reportDate: demoNow.subtract(1, 'day').format('YYYY-MM-DD'), title: `合成经营日报 · ${demoNow.subtract(1, 'day').format('M月D日')}`, summary: '新客增长，客单价小幅提升。', content: '## 经营摘要\n\n合成数据中新客占比提升，组合购买带动客单价上升。此内容为固定预生成示例。', riskLevel: 'LOW', generatedBy: 'DEMO_FIXTURE', createdAt: demoNow.subtract(1, 'day').hour(21).minute(0).second(0).toISOString() },
]

export function getDemoShopOverview(): ShopOverview {
  return {
    analysisDate: demoNow.format('YYYY-MM-DD'), requestedRange: 7, effectiveDays: 7,
    today: { salesAmount: 18642.5, orderCount: 238, buyerCount: 221, averageOrderValue: 78.33, salesChangeRate: 8.6, orderChangeRate: 6.2, repeatCustomerCount: 57 },
    hotProducts: [
      { productId: 801, productName: '旅行收纳袋组合', salesAmount: 6280, orderCount: 82, quantitySold: 96, stock: 188, trend: 'up', trendRate: 18.2, dataDays: 7, avgDailySales: 13.7, reason: '收藏转化率与组合购买率提升' },
      { productId: 802, productName: '桌面理线器', salesAmount: 3940, orderCount: 71, quantitySold: 85, stock: 326, trend: 'stable', trendRate: 1.4, dataDays: 7, avgDailySales: 12.1 },
    ],
    slowProducts: [{ productId: 803, productName: '便携阅读灯', salesAmount: 620, orderCount: 9, quantitySold: 10, stock: 240, trend: 'down', trendRate: -12.5, dataDays: 7, avgDailySales: 1.4, reason: '近三日曝光与转化同步下降' }],
    customers: { newCustomerCount: 164, repeatCustomerCount: 57, highValueCustomerCount: 34, avgCustomerValue: 92.8, femaleRatio: 62, maleRatio: 38, topRegions: [{ name: '华东', value: 36 }, { name: '华南', value: 24 }, { name: '华北', value: 18 }], ageDistribution: [{ name: '18-24', value: 22 }, { name: '25-34', value: 48 }, { name: '35-44', value: 21 }] },
    salesTrend: [
      { date: '2026-07-14', salesAmount: 12800, orderCount: 169, buyerCount: 158 }, { date: '2026-07-15', salesAmount: 14220, orderCount: 181, buyerCount: 170 }, { date: '2026-07-16', salesAmount: 13760, orderCount: 176, buyerCount: 165 }, { date: '2026-07-17', salesAmount: 15190, orderCount: 193, buyerCount: 180 }, { date: '2026-07-18', salesAmount: 16980, orderCount: 216, buyerCount: 202 }, { date: '2026-07-19', salesAmount: 17170, orderCount: 224, buyerCount: 209 }, { date: '2026-07-20', salesAmount: 18642.5, orderCount: 238, buyerCount: 221 },
    ],
    replenishmentSuggestions: [{ productId: 804, productName: '便携水杯', stock: 36, avgDailySales: 11.4, estimatedDaysLeft: 3.16, suggestedReplenishment: 124, priority: 'high', reason: '预计三天后低于安全库存' }],
    activitySuggestions: [{ type: 'bundle', title: '测试收纳组合优惠', description: '将热销收纳袋与理线器组成轻量套装，观察组合转化。', priority: 'medium' }, { type: 'inventory', title: '控制阅读灯曝光预算', description: '先优化详情页信息，再逐步恢复曝光。', priority: 'low' }],
    aiReport: demoShopReports[0],
  }
}

export const demoShopReportPage: ShopAiReportPage = { records: demoShopReports, total: demoShopReports.length, pages: 1, current: 1, size: 10 }

const demoTopics = ['AI大模型', 'Web开发', '移动端', '云原生', '数据库', '安全', 'DevOps', '数据分析', '机器学习', '区块链']
export const demoSubscription = {
  receiveTime: '08:15', preferenceFields: ['AI大模型', 'Web开发', '数据分析'], enabled: true,
  morningEnabled: true, morningTime: '08:15', eveningEnabled: true, eveningTime: '20:15',
  topicSchedules: {
    morning: demoTopics.map((topic, index) => ({ topic, enabled: index < 3, time: index < 3 ? '08:15' : '08:00' })),
    evening: demoTopics.map((topic, index) => ({ topic, enabled: index === 0 || index === 5 || index === 7, time: index === 0 || index === 5 || index === 7 ? '20:15' : '20:00' })),
  },
}

export const demoChannels = [
  { id: 401, channelType: 'email' as const, displayName: '演示邮箱', target: 'd***@example.invalid', enabled: true },
  { id: 402, channelType: 'feishu' as const, displayName: '演示群机器人', target: 'https://example.invalid/webhook/••••••••', secret: '••••••••', enabled: true },
]

export const demoInviteCodes = [
  { id: 1001, code: 'DEMO-••••-READONLY', usedBy: null, usedAt: null, expiresAt: '2026-12-31T23:59:59+08:00', createdAt: '2026-07-01T10:00:00+08:00' },
  { id: 1002, code: 'DEMO-••••-USED', usedBy: null, usedAt: '2026-07-02T11:30:00+08:00', expiresAt: '2026-12-31T23:59:59+08:00', createdAt: '2026-07-01T10:00:00+08:00' },
]
