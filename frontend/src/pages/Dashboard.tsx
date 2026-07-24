import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import MarketMarkdown from '../components/MarketMarkdown'
import dayjs from '../utils/dayjs'
import api from '../utils/api'
import { getReportEditionInfo } from '../utils/reportEdition'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoPushLogs, demoSubscription } from '../demo/fixtures'
import './Dashboard.css'

interface Report {
  id: number
  edition: string
  title: string
  summary: string
  content?: string
  createdAt: string
}

interface DashboardStats {
  todayCount: number
  totalCount: number
  hotTags: string[]
  nextPushAt: string
}

interface Subscription {
  enabled: boolean
  receiveTime?: string
  preferenceFields?: string[]
  morningEnabled: boolean
  morningTime: string
  eveningEnabled: boolean
  eveningTime: string
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

type EditionKey = 'morning' | 'evening' | 'etf_morning' | 'etf_evening'
type ReportMap = Record<EditionKey, Report | null>

const emptyReports: ReportMap = {
  morning: null,
  evening: null,
  etf_morning: null,
  etf_evening: null,
}

function isToday(date?: string) {
  return !!date && dayjs(date).format('YYYY-MM-DD') === dayjs().format('YYYY-MM-DD')
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

function ReportMiniCard({ report, edition }: { report: Report | null; edition: EditionKey }) {
  const [expanded, setExpanded] = useState(false)
  const info = getReportEditionInfo(edition)
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
          <small>预计 {info.expectedLabel} 推送</small>
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

  const info = getReportEditionInfo(report.edition)
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

function SubscriptionCard({ subscription }: { subscription: Subscription | null }) {
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

  const fields = subscription.preferenceFields || []
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
          <span>早间推送</span>
          <span>{subscription.morningEnabled ? toHHmm(subscription.morningTime) : '未开启'}</span>
        </div>
        <div className="status-row">
          <span>晚间推送</span>
          <span>{subscription.eveningEnabled ? toHHmm(subscription.eveningTime) : '未开启'}</span>
        </div>
      </div>
      <div className="preference-tags">
        {fields.length > 0 ? fields.map(field => <span key={field} className="preference-tag">{field}</span>) : <span className="overview-muted">暂未设置关注领域</span>}
      </div>
    </div>
  )
}

function PushStatusCard({ logs }: { logs: PushLog[] }) {
  const todayLogs = logs.filter(log => isToday(log.pushedAt))
  const failed = todayLogs.filter(log => log.status === 'failed')
  const latest = todayLogs[0]

  return (
    <div className="overview-card">
      <div className="overview-card-header">
        <div className="overview-card-title">🔔 今日推送状态</div>
        <Link to="/notifications" className="section-link">记录 →</Link>
      </div>
      <div className="push-summary-grid">
        <div><strong>{todayLogs.length}</strong><span>今日推送</span></div>
        <div><strong>{todayLogs.length - failed.length}</strong><span>成功</span></div>
        <div className={failed.length > 0 ? 'danger-text' : ''}><strong>{failed.length}</strong><span>失败</span></div>
      </div>
      {latest ? (
        <p className="overview-muted">最近一次：{dayjs(latest.pushedAt).format('HH:mm')} · {latest.channelType}</p>
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
  const [reports, setReports] = useState<ReportMap>(emptyReports)
  const [recentReports, setRecentReports] = useState<Report[]>([])
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [subscription, setSubscription] = useState<Subscription | null>(null)
  const [pushLogs, setPushLogs] = useState<PushLog[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let mounted = true

    async function loadOverview() {
      setLoading(true)
      const [morning, evening, etfMorning, etfEvening, statsData, subscriptionData, pushLogData, recentData] = await Promise.all([
        safeGet<Report>('/reports/latest', { params: { edition: 'morning' } }),
        safeGet<Report>('/reports/latest', { params: { edition: 'evening' } }),
        safeGet<Report>('/reports/latest', { params: { edition: 'etf_morning' } }),
        safeGet<Report>('/reports/latest', { params: { edition: 'etf_evening' } }),
        safeGet<DashboardStats>('/stats/dashboard'),
        isDemo ? Promise.resolve(demoSubscription) : safeGet<Subscription>('/subscription'),
        isDemo ? Promise.resolve(demoPushLogs) : safeGet<PushLog[]>('/push-logs', { params: { limit: 20 } }),
        safeGet<{ records: Report[] }>('/reports', { params: { page: 1, size: 6 } }),
      ])

      if (!mounted) return
      setReports({ morning, evening, etf_morning: etfMorning, etf_evening: etfEvening })
      setStats(statsData)
      setSubscription(subscriptionData)
      setPushLogs(pushLogData || [])
      setRecentReports(recentData?.records || [])
      setLoading(false)
    }

    loadOverview()
    return () => { mounted = false }
  }, [isDemo])

  const todayReports = useMemo(() => Object.values(reports).filter((report): report is Report => !!report && isToday(report.createdAt)), [reports])
  const focusReport = todayReports.find(report => report.edition === 'evening' || report.edition === 'morning') || todayReports[0] || reports.morning || reports.evening || reports.etf_morning || reports.etf_evening
  const todayLogs = pushLogs.filter(log => isToday(log.pushedAt))
  const failedLogs = todayLogs.filter(log => log.status === 'failed')
  const nextPushLabel = stats?.nextPushAt ? dayjs(stats.nextPushAt).format('MM-DD HH:mm') : '--:--'

  const alerts = useMemo(() => {
    const now = dayjs()
    const items: string[] = []
    if ((stats?.todayCount ?? todayReports.length) === 0) items.push('今日暂无任何报告入库')
    if (now.hour() >= 9 && !isToday(reports.morning?.createdAt)) items.push('AI 早间简报尚未生成')
    if (now.hour() >= 21 && !isToday(reports.evening?.createdAt)) items.push('AI 晚间简报尚未生成')
    if (now.hour() >= 11 && !isToday(reports.etf_morning?.createdAt)) items.push('ETF/A股早间观察尚未生成')
    if (now.hour() >= 18 && !isToday(reports.etf_evening?.createdAt)) items.push('ETF/A股晚间观察尚未生成')
    if (failedLogs.length > 0) items.push(`今日有 ${failedLogs.length} 条推送失败`)
    if (subscription?.enabled && todayLogs.length === 0 && now.hour() >= 9) items.push('订阅已开启，但今日暂无推送记录')
    return items
  }, [stats, todayReports.length, reports, failedLogs.length, subscription, todayLogs.length])

  const suggestions = useMemo(() => {
    if (failedLogs.length > 0) return ['检查推送渠道配置，优先处理今日失败记录。']
    if (subscription && !subscription.enabled) return ['订阅总开关已关闭，可以开启后接收每日简报。']
    if (subscription && (!subscription.preferenceFields || subscription.preferenceFields.length === 0)) return ['完善关注领域，让后续内容更贴合你的偏好。']
    if (!isToday(reports.morning?.createdAt) || !isToday(reports.evening?.createdAt)) return ['今日 AI 简报未完全生成，建议检查 GitHub Actions 运行记录。']
    if (isToday(reports.etf_morning?.createdAt) || isToday(reports.etf_evening?.createdAt)) return ['ETF/A股观察已更新，可以结合今日重点查看市场变化。']
    return ['今日数据状态正常，建议先查看今日重点和近期热点。']
  }, [failedLogs.length, subscription, reports])

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="dashboard-new">
      {isDemo && <DemoNotice publicContent />}
      <div className="overview-hero">
        <div>
          <span className="overview-kicker">{dayjs().format('YYYY年M月D日 dddd')}</span>
          <h1 className="welcome-title">今日概览</h1>
          <p className="welcome-subtitle">集中查看 AI 简报、ETF/A股观察、订阅和推送状态</p>
        </div>
        <div className="overview-hero-stats">
          <div><strong>{stats?.todayCount ?? todayReports.length}</strong><span>今日报告</span></div>
          <div><strong>{failedLogs.length}</strong><span>推送异常</span></div>
          <div><strong>{nextPushLabel}</strong><span>下次推送</span></div>
        </div>
      </div>

      <div className="overview-main-grid">
        <FocusCard report={focusReport} />
        <SuggestionCard suggestions={suggestions} />
        <AlertsCard alerts={alerts} />
      </div>

      <div className="overview-section-header">
        <h2>今日 AI 简报</h2>
        <Link to="/reports" className="section-link">历史简报 →</Link>
      </div>
      <div className="overview-two-grid">
        <ReportMiniCard report={reports.morning} edition="morning" />
        <ReportMiniCard report={reports.evening} edition="evening" />
      </div>

      <div className="overview-section-header">
        <h2>ETF / A股观察</h2>
      </div>
      <div className="overview-two-grid">
        <ReportMiniCard report={reports.etf_morning} edition="etf_morning" />
        <ReportMiniCard report={reports.etf_evening} edition="etf_evening" />
      </div>

      <div className="overview-main-grid">
        <SubscriptionCard subscription={subscription} />
        <PushStatusCard logs={pushLogs} />
        <div className="overview-card">
          <div className="overview-card-title">🔥 近期热点</div>
          <div className="preference-tags">
            {stats?.hotTags?.length ? stats.hotTags.slice(0, 8).map(tag => <span key={tag} className="preference-tag hot">{tag}</span>) : <span className="overview-muted">暂无热点标签</span>}
          </div>
          <p className="overview-muted">累计报告：{stats?.totalCount ?? '—'} 份</p>
        </div>
      </div>

      <div className="section">
        <div className="section-header">
          <h2 className="section-title">📋 最近报告</h2>
          <Link to="/reports" className="section-link">查看全部 →</Link>
        </div>
        <div className="report-list">
          {recentReports.length === 0 ? (
            <div className="empty">暂无报告</div>
          ) : (
            <div className="list">
              {recentReports.map(report => {
                const info = getReportEditionInfo(report.edition)
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
          )}
        </div>
      </div>
    </div>
  )
}
