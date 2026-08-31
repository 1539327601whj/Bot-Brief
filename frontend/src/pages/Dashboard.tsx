import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import MarketMarkdown from '../components/MarketMarkdown'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import { getReportEditionInfo } from '../utils/reportEdition'
import { coversWeekday, weekdayRangeLabel, weekdaysOf } from '../utils/weekdays'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs, demoSubscription, demoTodayStatus } from '../demo/fixtures'
import { slotEmptyHint, type TodayProgress, type TopicProgressItem } from '../utils/pushDisplay'
import './Dashboard.css'

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  content?: string
  createdAt: string
  displayTime?: string
}

interface DashboardStats {
  todayCount: number
  totalCount: number
  hotTags: string[]
  nextPushAt?: string | null
}

interface TopicScheduleItem {
  topic: string
  enabled: boolean
  time?: string
  weekdayFrom?: number
  weekdayTo?: number
  channelIds?: number[]
}

interface Subscription {
  enabled: boolean
  receiveTime?: string
  preferenceFields?: string[]
  morningEnabled?: boolean
  morningTime?: string
  eveningEnabled?: boolean
  eveningTime?: string
  topicSchedules?: {
    items?: TopicScheduleItem[]
    morning?: TopicScheduleItem[]
    evening?: TopicScheduleItem[]
  }
}

interface PushLog {
  id: number
  reportId: number
  channelId: number
  channelType: string
  status: 'success' | 'failed'
  errorMessage: string | null
  pushedAt: string
}

type EditionKey = 'morning' | 'evening' | 'market_watch_evening' | 'personal'

function isToday(date?: string) {
  if (!date) return false
  const parsed = dayjs(date)
  if (!parsed.isValid()) return false
  return parsed.tz('Asia/Shanghai').format('YYYY-MM-DD') === dayjs.tz().format('YYYY-MM-DD')
}

function isSystemBriefEdition(edition?: string) {
  return edition === 'morning' || edition === 'evening' || edition === 'personal'
}

function subscriptionItems(subscription?: Subscription | null): TopicScheduleItem[] {
  const schedules = subscription?.topicSchedules
  const raw = schedules?.items?.length
    ? schedules.items
    : [...(schedules?.morning || []), ...(schedules?.evening || [])]
  return raw.filter(item => item.enabled)
}

function uniqueTimes(items: TopicScheduleItem[]) {
  return [...new Set(items.map(item => toHHmm(item.time)).filter(time => time !== '--:--'))].sort()
}

function todayIsoWeekday() {
  const sundayBased = dayjs.tz().day()
  return sundayBased === 0 ? 7 : sundayBased
}

function itemsDueToday(items: TopicScheduleItem[]) {
  const today = todayIsoWeekday()
  return items.filter(item => {
    const range = weekdaysOf(item, 1, 7)
    return coversWeekday(range.weekdayFrom, range.weekdayTo, today)
  })
}

function toHHmm(value?: string) {
  if (!value) return '--:--'
  return value.length >= 5 ? value.slice(0, 5) : value
}

async function safeGet<T>(url: string, config?: Record<string, unknown>): Promise<T | null> {
  try {
    const res = await api.get(url, config)
    if (res.data?.code === 200) return res.data.data as T
    return null
  } catch {
    return null
  }
}

function firstByEdition(reports: Report[] | undefined, edition: string) {
  return reports?.find(report => report.edition === edition) || null
}

async function latestPublicReport(edition: string): Promise<Report | null> {
  const latest = await safeGet<Report>('/reports/latest', { params: { edition } })
  if (latest) return latest
  const page = await safeGet<{ records: Report[] }>('/reports', { params: { edition, page: 1, size: 1 } })
  return page?.records?.[0] || null
}

function ReportMiniCard({ report, edition, emptyHint, displayTime, emptyTo }: {
  report: Report | null
  edition: EditionKey
  emptyHint?: string
  displayTime?: string
  emptyTo?: { href: string; label: string }
}) {
  const [expanded, setExpanded] = useState(false)
  const info = getReportEditionInfo(edition, report?.displayTime || displayTime)
  const fresh = isToday(report?.createdAt)
  const reportDate = report ? dayjs(report.createdAt).format('YYYY-MM-DD') : ''

  return (
    <div className={`overview-card report-mini-card ${fresh ? 'is-fresh' : ''}`}>
      <div className="overview-card-header">
        <div className="overview-card-title">
          <span>{info.icon}</span>
          <span>{info.label}</span>
        </div>
        {report && <Link to={`/report/${report.id}`} className="section-link">详情 →</Link>}
      </div>

      {!report ? (
        <div className="overview-empty">
          <span>今日暂无内容</span>
          <small>{emptyHint || `预计 ${info.expectedLabel} 推送`}</small>
          {emptyTo && <Link to={emptyTo.href} className="section-link">{emptyTo.label}</Link>}
        </div>
      ) : (
        <>
          <div className="report-card-meta">
            <span className={info.className}>{info.shortLabel}</span>
            <span>{dayjs(report.createdAt).format('MM-DD HH:mm')}</span>
            {!fresh && <span className="stale-badge">最近一期</span>}
          </div>
          <h3 className="overview-report-title">{report.title}</h3>
          {!fresh && (
            <div className="today-card-stale-hint">今日尚未生成，当前展示 {reportDate} 的最近一期</div>
          )}
          <div className={`today-card-body ${expanded ? 'expanded' : ''}`}>
            <MarketMarkdown>{expanded ? (report.content || report.summary) : report.summary}</MarketMarkdown>
          </div>
          <button className="today-card-toggle" onClick={() => setExpanded(v => !v)}>
            {expanded ? '收起' : '展开阅读'}
          </button>
        </>
      )}
    </div>
  )
}

function FocusCard({ report }: { report: Report | null }) {
  if (!report) {
    return (
      <div className="overview-card focus-card">
        <div className="overview-card-title">🎯 今日重点</div>
        <div className="overview-empty">暂无可展示重点，等待今日报告生成</div>
      </div>
    )
  }

  const info = getReportEditionInfo(report.edition, report.displayTime)
  return (
    <div className="overview-card focus-card">
      <div className="overview-card-header">
        <div className="overview-card-title">🎯 今日重点</div>
        <Link to={`/report/${report.id}`} className="section-link">查看详情 →</Link>
      </div>
      <div className="report-card-meta">
        <span className={info.className}>{info.label}</span>
        <span>{dayjs(report.createdAt).format('MM-DD HH:mm')}</span>
        {!isToday(report.createdAt) && <span className="stale-badge">最近一期</span>}
      </div>
      <h2 className="focus-title">{report.title}</h2>
      <div className="focus-summary"><MarketMarkdown>{report.summary}</MarketMarkdown></div>
    </div>
  )
}

function progressTone(status?: string) {
  if (status === 'failed') return 'danger'
  if (status === 'skipped') return 'warn'
  if (status === 'ready' || status === 'delivered') return 'ok'
  if (status === 'preparing') return 'info'
  return ''
}

function SubscriptionCard({ subscription, progress }: { subscription: Subscription | null; progress: TopicProgressItem[] }) {
  if (!subscription) {
    return (
      <div className="overview-card">
        <div className="overview-card-header">
          <div className="overview-card-title">📬 我的订阅</div>
          <Link to="/subscription" className="section-link">去设置 →</Link>
        </div>
        <div className="overview-empty">登录后查看订阅状态</div>
      </div>
    )
  }

  const items = subscriptionItems(subscription)
  const fields = [...new Set(items.map(item => item.topic))]
  const times = uniqueTimes(items)
  const days = [...new Set(items.map(item => {
    const range = weekdaysOf(item, 1, 7)
    return weekdayRangeLabel(range.weekdayFrom, range.weekdayTo)
  }))]
  return (
    <div className="overview-card">
      <div className="overview-card-header">
        <div className="overview-card-title">📬 我的订阅</div>
        <Link to="/subscription" className="section-link">管理 →</Link>
      </div>
      <div className="status-list">
        <div className="status-row">
          <span>总开关</span>
          <span className={`sub-status ${subscription.enabled ? 'active' : 'inactive'}`}>{subscription.enabled ? '已开启' : '已关闭'}</span>
        </div>
        <div className="status-row">
          <span>推送时刻</span>
          <span>{times.length > 0 ? times.join(' / ') : '未设置'}</span>
        </div>
        <div className="status-row">
          <span>星期</span>
          <span>{days.length > 0 ? days.join(' / ') : '未设置'}</span>
        </div>
      </div>
      <div className="preference-tags">
        {fields.length > 0 ? fields.map(field => <span key={field} className="preference-tag">{field}</span>) : <span className="overview-muted">暂未设置关注领域</span>}
      </div>
      {progress.length > 0 && (
        <div className="topic-progress-list">
          {progress.map(item => (
            <div key={`${item.topic}-${item.time}`} className={`topic-progress ${progressTone(item.status)}`}>
              <span>{item.time} · {item.topic}</span>
              <strong>{item.label}</strong>
              <small>{item.message}</small>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function PushStatusCard({ logs, todayReports }: { logs: PushLog[]; todayReports: Report[] }) {
  const todayLogs = logs.filter(log => isToday(log.pushedAt))
  const failed = todayLogs.filter(log => log.status === 'failed')
  const latest = todayLogs[0]
  const systemBriefs = todayReports.filter(report => isSystemBriefEdition(report.edition))
  const total = todayLogs.length + systemBriefs.length
  const success = todayLogs.length - failed.length + systemBriefs.length

  return (
    <div className="overview-card">
      <div className="overview-card-header">
        <div className="overview-card-title">🔔 今日推送状态</div>
        <Link to="/notifications" className="section-link">记录 →</Link>
      </div>
      <div className="push-summary-grid">
        <div><strong>{total}</strong><span>今日推送</span></div>
        <div><strong>{success}</strong><span>成功</span></div>
        <div className={failed.length > 0 ? 'danger-text' : ''}><strong>{failed.length}</strong><span>失败</span></div>
      </div>
      {systemBriefs.length > 0 && (
        <div className="system-push-list">
          {systemBriefs.map(report => {
            const info = getReportEditionInfo(report.edition, report.displayTime)
            return (
              <div key={report.id} className="status-row">
                <span>{info.shortLabel}</span>
                <span>今日 {dayjs(report.createdAt).tz('Asia/Shanghai').format('HH:mm')} 已生成</span>
              </div>
            )
          })}
        </div>
      )}
      {latest ? (
        <p className="overview-muted">最近一次渠道投递：{dayjs(latest.pushedAt).tz('Asia/Shanghai').format('HH:mm')} · {latest.channelType}</p>
      ) : systemBriefs.length > 0 ? (
        <p className="overview-muted">系统简报已生成；你的个人渠道今天还没有投递记录。</p>
      ) : (
        <p className="overview-muted">今日暂无推送记录</p>
      )}
      {failed[0]?.errorMessage && <div className="alert-line danger">{failed[0].errorMessage}</div>}
    </div>
  )
}

function SuggestionCard({ suggestions }: { suggestions: string[] }) {
  return (
    <div className="overview-card suggestion-card">
      <div className="overview-card-title">🤖 AI 建议卡片</div>
      <ul className="suggestion-list">
        {suggestions.map(item => <li key={item}>{item}</li>)}
      </ul>
    </div>
  )
}

function isPublicEdition(edition?: string) {
  return edition === 'morning' || edition === 'evening' || Boolean(edition?.startsWith('market_watch')) || Boolean(edition?.startsWith('etf_'))
}

function RecentReportList({ reports }: { reports: Report[] }) {
  if (reports.length === 0) {
    return <div className="empty">暂无报告</div>
  }
  return (
    <div className="report-list">
      <div className="list">
        {reports.map(report => {
          const info = getReportEditionInfo(report.edition, report.displayTime)
          return (
            <div key={report.id} className="report-item">
              <div className="item-left">
                <span className={info.className}>{info.shortLabel}</span>
                <span className="item-time">{dayjs(report.createdAt).format('MM-DD HH:mm')}</span>
              </div>
              <div className="item-right">
                <Link to={`/report/${report.id}`} className="item-title">{report.title}</Link>
                <p className="item-summary">{report.summary}</p>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function AlertsCard({ alerts }: { alerts: string[] }) {
  return (
    <div className={`overview-card ${alerts.length > 0 ? 'alert-card' : 'ok-card'}`}>
      <div className="overview-card-title">🧭 数据异常提醒</div>
      {alerts.length > 0 ? (
        <ul className="suggestion-list">
          {alerts.map(item => <li key={item}>{item}</li>)}
        </ul>
      ) : (
        <div className="overview-empty">今日关键数据暂未发现异常</div>
      )}
    </div>
  )
}

export default function Dashboard() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const isAdmin = user?.role === 'ADMIN'
  const canSeePublicDigest = isDemo || isAdmin
  const [morning, setMorning] = useState<Report | null>(null)
  const [evening, setEvening] = useState<Report | null>(null)
  const [marketWatch, setMarketWatch] = useState<Report | null>(null)
  const [personalReports, setPersonalReports] = useState<Report[]>([])
  const [recentReports, setRecentReports] = useState<Report[]>([])
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [subscription, setSubscription] = useState<Subscription | null>(null)
  const [pushLogs, setPushLogs] = useState<PushLog[]>([])
  const [todayProgress, setTodayProgress] = useState<TodayProgress>({ items: [] })
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let mounted = true

    async function loadOverview() {
      setLoading(true)
      const today = dayjs.tz().format('YYYY-MM-DD')
      const [morningReport, eveningReport, etfEvening, personalPage, statsData, subscriptionData, pushLogData, recentData, progressData] = await Promise.all([
        canSeePublicDigest ? latestPublicReport('morning') : Promise.resolve(null),
        canSeePublicDigest ? latestPublicReport('evening') : Promise.resolve(null),
        canSeePublicDigest ? latestPublicReport('market_watch_evening') : Promise.resolve(null),
        isDemo ? Promise.resolve(null) : safeGet<{ records: Report[] }>('/reports', { params: { edition: 'personal', startDate: today, endDate: today, page: 1, size: 20 } }),
        safeGet<DashboardStats>('/stats/dashboard'),
        isDemo ? Promise.resolve(demoSubscription) : safeGet<Subscription>('/subscription'),
        isDemo ? Promise.resolve(demoPushLogs) : safeGet<PushLog[]>('/push-logs', { params: { limit: 20 } }),
        safeGet<{ records: Report[] }>('/reports', { params: { page: 1, size: canSeePublicDigest ? 12 : 6 } }),
        isDemo ? Promise.resolve(demoTodayStatus) : safeGet<TodayProgress>('/subscription/today-status'),
      ])

      if (!mounted) return
      const recent = recentData?.records || []
      setMorning(morningReport || firstByEdition(recent, 'morning'))
      setEvening(eveningReport || firstByEdition(recent, 'evening'))
      setMarketWatch(etfEvening || firstByEdition(recent, 'market_watch_evening'))
      setPersonalReports(personalPage?.records || [])
      setStats(statsData)
      setSubscription(subscriptionData)
      setPushLogs(pushLogData || [])
      setRecentReports(recent)
      setTodayProgress(progressData || { items: [] })
      setLoading(false)
    }

    loadOverview()
    return () => { mounted = false }
  }, [isDemo, canSeePublicDigest])

  const slotTimes = uniqueTimes(itemsDueToday(subscriptionItems(subscription)))
  const personalByTime = useMemo(() => {
    const map = new Map<string, Report>()
    personalReports.forEach(report => {
      const time = toHHmm(report.displayTime)
      if (time !== '--:--' && !map.has(time)) map.set(time, report)
    })
    return map
  }, [personalReports])
  const todayReports = useMemo(() => {
    const candidates = canSeePublicDigest
      ? [morning, evening, ...personalReports, marketWatch]
      : [...personalReports]
    return candidates.filter((report): report is Report => !!report && isToday(report.createdAt))
  }, [canSeePublicDigest, morning, evening, marketWatch, personalReports])
  const focusReport = todayReports.find(report => report.edition === 'personal' || report.edition === 'evening' || report.edition === 'morning')
    || todayReports[0]
    || (canSeePublicDigest ? morning || evening || marketWatch : personalReports[0])
  const todayLogs = pushLogs.filter(log => isToday(log.pushedAt))
  const failedLogs = todayLogs.filter(log => log.status === 'failed')
  const nextPushLabel = stats?.nextPushAt ? dayjs(stats.nextPushAt).format('MM-DD HH:mm') : '未设置'

  const alerts = useMemo(() => {
    const now = dayjs.tz()
    const items: string[] = []
    if ((stats?.todayCount ?? todayReports.length) === 0) {
      items.push(isDemo ? '今日暂无任何报告入库' : canSeePublicDigest ? '今日暂无公共简报或你的简报' : '今日暂无属于你的简报')
    }
    if (canSeePublicDigest) {
      if (now.hour() >= 9 && !isToday(morning?.createdAt)) items.push('今日早间简报尚未生成')
      if (now.hour() >= 21 && !isToday(evening?.createdAt)) items.push('今日晚间简报尚未生成')
    }
    if (!isDemo) {
      todayProgress.items.forEach(item => {
        if (item.status === 'failed') items.push(`${item.time} 「${item.topic}」生成失败`)
        if (item.status === 'skipped') items.push(`${item.time} 「${item.topic}」没有匹配资讯`)
      })
      if (isAdmin && todayProgress.poller && !todayProgress.poller.healthy) {
        items.push('订阅生成器心跳超时，个人简报可能不会自动生成')
      }
    }
    if (canSeePublicDigest && now.hour() >= 18 && !isToday(marketWatch?.createdAt)) items.push('ETF/A股日报尚未生成')
    if (failedLogs.length > 0) items.push(`今日有 ${failedLogs.length} 条推送失败`)
    const hasSystemBrief = todayReports.some(report => isSystemBriefEdition(report.edition))
    if (subscription?.enabled && todayLogs.length === 0 && now.hour() >= 9) {
      items.push(hasSystemBrief
        ? '你的简报已生成，但个人渠道今天还没有投递记录'
        : '订阅已开启，但今日暂无推送记录')
    }
    return [...new Set(items)]
  }, [stats, todayReports, morning, evening, marketWatch, failedLogs.length, subscription, todayLogs.length, isDemo, canSeePublicDigest, todayProgress, isAdmin])

  const suggestions = useMemo(() => {
    if (failedLogs.length > 0) return ['检查推送渠道配置，优先处理今日失败记录。']
    if (subscription && !subscription.enabled) return ['订阅总开关已关闭，可以开启后接收每日简报。']
    if (subscriptionItems(subscription).length === 0) return ['完善关注领域和时间，让后续内容更贴合你的偏好。']
    if (!isDemo && slotTimes.some(time => !isToday(personalByTime.get(time)?.createdAt))) {
      return ['今日勾选主题尚未完全生成，可以先查看已有段落或等待下次生成。']
    }
    if (canSeePublicDigest && isToday(marketWatch?.createdAt)) return ['ETF/A股日报已更新，可以结合今日重点查看市场变化。']
    return ['今日数据状态正常，建议先查看今日重点和近期热点。']
  }, [failedLogs.length, subscription, slotTimes, personalByTime, marketWatch, isDemo, canSeePublicDigest])

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="dashboard-new">
      {isDemo && <DemoNotice publicContent />}
      {isAdmin && todayProgress.poller && !todayProgress.poller.healthy && (
        <div className="alert-line danger">订阅生成器超过 20 分钟没有心跳，个人简报可能停了。最近一次：{todayProgress.poller.lastSeen || '从未上报'}</div>
      )}
      <div className="overview-hero">
        <div>
          <span className="overview-kicker">{dayjs().format('YYYY年M月D日 dddd')}</span>
          <h1 className="welcome-title">今日概览</h1>
          <p className="welcome-subtitle">{isDemo ? '集中查看 AI 简报、ETF/A股观察、订阅和推送状态' : isAdmin ? '上半部分是全站公共日报，下半部分是你自己的个人订阅。两边互不影响。' : '只展示你勾选的兴趣简报'}</p>
        </div>
        <div className="overview-hero-stats">
          <div><strong>{stats?.todayCount ?? todayReports.length}</strong><span>今日报告</span></div>
          <div><strong>{failedLogs.length}</strong><span>推送异常</span></div>
          <div><strong>{nextPushLabel}</strong><span>下次推送</span></div>
        </div>
      </div>

      {isAdmin ? (
        <>
          <section className="overview-pane">
            <div className="overview-section-header">
              <div>
                <p className="overview-pane-kicker">公共内容</p>
                <h2>全站日报</h2>
                <p className="overview-pane-desc">仅管理员和 Demo 可见的早报、晚报和 ETF 日报。今天没生成时显示最近一期。</p>
              </div>
              <Link to="/reports" className="section-link">历史日报 →</Link>
            </div>
            <div className="overview-two-grid">
              <ReportMiniCard report={morning} edition="morning" />
              <ReportMiniCard report={evening} edition="evening" />
            </div>
            <div className="overview-single-grid">
              <ReportMiniCard report={marketWatch} edition="market_watch_evening" />
            </div>
            <div className="overview-section-header">
              <h2>最近公共日报</h2>
              <Link to="/reports" className="section-link">查看全部 →</Link>
            </div>
            <RecentReportList reports={recentReports.filter(report => isPublicEdition(report.edition))} />
          </section>

          <section className="overview-pane personal">
            <div className="overview-section-header">
              <div>
                <p className="overview-pane-kicker">个人内容</p>
                <h2>我的订阅简报</h2>
                <p className="overview-pane-desc">只按你在订阅管理里勾选的兴趣、星期和时刻生成，和上面的公共日报分开。</p>
              </div>
              <Link to="/subscription" className="section-link">设置个人订阅 →</Link>
            </div>
            <div className="overview-main-grid">
              <FocusCard report={personalReports[0] || null} />
              <SuggestionCard suggestions={suggestions} />
              <AlertsCard alerts={alerts} />
            </div>
            <div className={slotTimes.length > 1 ? 'overview-two-grid' : 'overview-single-grid'}>
              {slotTimes.length === 0 ? (
                <ReportMiniCard
                  report={null}
                  edition="personal"
                  emptyHint="还没有个人订阅。勾选兴趣并设定星期、时刻后，到点会生成简报"
                  emptyTo={{ href: '/subscription', label: '去订阅管理 →' }}
                />
              ) : (
                slotTimes.map(time => (
                  <ReportMiniCard
                    key={time}
                    report={personalByTime.get(time) || null}
                    edition="personal"
                    displayTime={time}
                    emptyHint={slotEmptyHint(todayProgress.items, time, `预计 ${time} 按你勾选的主题生成`)}
                  />
                ))
              )}
            </div>
            <div className="overview-main-grid">
              <SubscriptionCard subscription={subscription} progress={todayProgress.items} />
              <PushStatusCard logs={pushLogs} todayReports={todayReports} />
              <div className="overview-card">
                <div className="overview-card-title">🔥 近期热点</div>
                <div className="preference-tags">
                  {stats?.hotTags?.length ? stats.hotTags.slice(0, 8).map(tag => <span key={tag} className="preference-tag hot">{tag}</span>) : <span className="overview-muted">暂无热点标签</span>}
                </div>
                <p className="overview-muted">累计报告：{stats?.totalCount ?? '—'} 份</p>
              </div>
            </div>
            <div className="overview-section-header">
              <h2>最近个人简报</h2>
              <Link to="/reports" className="section-link">查看全部 →</Link>
            </div>
            <RecentReportList reports={recentReports.filter(report => report.edition === 'personal')} />
          </section>
        </>
      ) : (
        <>
          <div className="overview-main-grid">
            <FocusCard report={focusReport} />
            <SuggestionCard suggestions={suggestions} />
            <AlertsCard alerts={alerts} />
          </div>

          <div className="overview-section-header">
            <h2>{isDemo ? '今日 AI 简报' : '我的简报'}</h2>
            <Link to="/reports" className="section-link">历史简报 →</Link>
          </div>
          <div className={isDemo || slotTimes.length > 1 ? 'overview-two-grid' : 'overview-single-grid'}>
            {isDemo ? (
              <>
                <ReportMiniCard report={morning} edition="morning" />
                <ReportMiniCard report={evening} edition="evening" />
              </>
            ) : slotTimes.length === 0 ? (
              <ReportMiniCard
                report={null}
                edition="personal"
                emptyHint="请先勾选兴趣并设定时间，到点后这里会出现你的简报"
                emptyTo={{ href: '/subscription', label: '去订阅管理 →' }}
              />
            ) : (
              slotTimes.map(time => (
                <ReportMiniCard
                  key={time}
                  report={personalByTime.get(time) || null}
                  edition="personal"
                  displayTime={time}
                  emptyHint={slotEmptyHint(todayProgress.items, time, `预计 ${time} 按你勾选的主题生成`)}
                />
              ))
            )}
          </div>

          {canSeePublicDigest && (
            <>
              <div className="overview-section-header">
                <h2>ETF / A股观察</h2>
              </div>
              <div className="overview-single-grid">
                <ReportMiniCard report={marketWatch} edition="market_watch_evening" />
              </div>
            </>
          )}

          <div className="overview-main-grid">
            <SubscriptionCard subscription={subscription} progress={todayProgress.items} />
            <PushStatusCard logs={pushLogs} todayReports={todayReports} />
            <div className="overview-card">
              <div className="overview-card-title">🔥 近期热点</div>
              <div className="preference-tags">
                {stats?.hotTags?.length ? stats.hotTags.slice(0, 8).map(tag => <span key={tag} className="preference-tag hot">{tag}</span>) : <span className="overview-muted">暂无热点标签</span>}
              </div>
              <p className="overview-muted">累计报告：{stats?.totalCount ?? '—'} 份</p>
            </div>
          </div>
        </>
      )}

      {!isAdmin && (
        <div className="section">
          <div className="section-header">
            <h2 className="section-title">📋 最近报告</h2>
            <Link to="/reports" className="section-link">查看全部 →</Link>
          </div>
          <RecentReportList reports={recentReports} />
        </div>
      )}
    </div>
  )
}
