import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import api from '../../utils/api'
import type { CoreView, SearchHit } from './types'
import { useCodeLookup } from './useCodeLookup'
import { assetTypeLabel, dateTime } from './format'
import DataQualityBanner from './components/DataQualityBanner'
import DecisionCard from './components/DecisionCard'
import VerdictHero from './components/VerdictHero'
import ScoreTiles from './components/ScoreTiles'
import ScenarioCards from './components/ScenarioCards'
import LookbackTables from './components/LookbackTables'
import AiBriefPanel from './components/AiBriefPanel'
import { PriceChart, ScoreRadar } from './components/Charts'
import {
  EventsTab,
  FinancialTab,
  FundProfileCard,
  KeyNumbers,
  PeersTab,
  ProvenanceTab,
  TechnicalTab,
  ValuationTab,
} from './components/DetailSections'
import './CodeLookupV2.css'

// 只有「真的是代码」才跳过搜索直接查询。名称、简称、拼音都要走 /search，
// 否则 gzmt、茅台这类输入既搜不到、又会被当成代码去查一个不存在的标的。
const LOOKUP_CODE = /^(?:(?:SH|SZ)?\d{6}|NDX|SP500|GDAXI|HSFML|H3\d{4})$/i

function looksLikeLookupCode(value: string): boolean {
  return LOOKUP_CODE.test(value.trim())
}

type TabKey = 'overview' | 'financial' | 'valuation' | 'technical' | 'events' | 'peers' | 'provenance'

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'overview', label: '概览' },
  { key: 'financial', label: '财务' },
  { key: 'valuation', label: '估值' },
  { key: 'technical', label: '技术资金' },
  { key: 'events', label: '新闻政策' },
  { key: 'peers', label: '同行 / 指数' },
  { key: 'provenance', label: '口径' },
]

export default function CodeLookupPage() {
  const { user } = useAuth()
  const canSee = user?.accountType === 'DEMO' || user?.role === 'ADMIN'

  const [input, setInput] = useState('')
  const [tab, setTab] = useState<TabKey>('overview')
  const [priceRange, setPriceRange] = useState('1y')
  const [valuationRange, setValuationRange] = useState('5y')
  const [suggestions, setSuggestions] = useState<SearchHit[]>([])
  const [searching, setSearching] = useState(false)
  const searchSeq = useRef(0)

  const lookup = useCodeLookup()
  const {
    core, coreLoading, coreFailure, detail, detailLoading, detailFailure,
    brief, briefLoading, briefFailure, series, seriesLoading, seriesFailure,
    notice, recent, load, loadSeries, runBrief, clear,
  } = lookup

  const codeReady = looksLikeLookupCode(input)

  // 本地库 + 腾讯 smartbox 搜索（代码 / 名称 / 简称 / 拼音）。
  // 本地库不触发行情外呼；smartbox 由后端做缓存与限流，前端只做防抖。
  useEffect(() => {
    const q = input.trim()
    if (q.length < 2 || codeReady) {
      setSuggestions([])
      return
    }
    const token = ++searchSeq.current
    setSearching(true)
    const timer = window.setTimeout(async () => {
      try {
        const res = await api.get('/code-lookup/v2/search', { params: { q } })
        if (token !== searchSeq.current) return
        const data = res.data?.code === 200 ? (res.data.data as SearchHit[]) : []
        setSuggestions(Array.isArray(data) ? data.slice(0, 8) : [])
      } catch {
        if (token === searchSeq.current) setSuggestions([])
      } finally {
        if (token === searchSeq.current) setSearching(false)
      }
    }, 250)
    return () => window.clearTimeout(timer)
  }, [input, codeReady])

  const submit = useCallback((raw?: string) => {
    const code = (raw ?? input).trim()
    if (!code) return
    setInput(code)
    setSuggestions([])
    setTab('overview')
    void load(code)
  }, [input, load])

  // 标签页按需拉序列：切到哪个页才读哪个本地库，不在首屏一次性取三段。
  useEffect(() => {
    if (!core) return
    if (tab === 'overview') void loadSeries('price', priceRange)
    if (tab === 'valuation') void loadSeries('valuation', valuationRange)
    if (tab === 'technical') void loadSeries('moneyflow', '1y')
  }, [tab, core, priceRange, valuationRange, loadSeries])

  if (!canSee) {
    return (
      <div className="cl-page">
        <section className="cl-idle">
          <h2>代码查询</h2>
          <p>代码查询仅管理员和 Demo 可见。普通用户只看自己订阅的简报。</p>
        </section>
      </div>
    )
  }

  return (
    <div className="cl-page">
      <header className="cl-page-head">
        <div>
          <div className="cl-kicker">Code Lookup · v2</div>
          <h1>代码查询</h1>
          <p>
            输入代码，先给<b>当前数据下的规则结论</b>、参考区间与止损位，再按标签页看估值、
            财务、技术资金与新闻依据。每个数字都带来源与数据日；缺数据就写缺什么。
          </p>
        </div>
        <Link className="cl-legacy-link" to="/code-lookup/legacy">旧版页面</Link>
      </header>

      <section className="cl-search">
        <div className="cl-search-row">
          <input
            value={input}
            onChange={event => setInput(event.target.value)}
            onKeyDown={event => {
              if (event.key !== 'Enter' || coreLoading) return
              if (codeReady) { submit(); return }
              if (suggestions.length > 0) submit(suggestions[0].code)
            }}
            placeholder="600519 / 510300 / 000300 / NDX，或输入名称搜索"
            maxLength={24}
            autoComplete="off"
            spellCheck={false}
            aria-label="代码或名称"
          />
          <button type="button" className="cl-btn cl-btn-primary"
            disabled={!codeReady || coreLoading} onClick={() => submit()}>
            {coreLoading ? '查询中…' : '查询'}
          </button>
          <button type="button" className="cl-btn"
            disabled={coreLoading}
            onClick={() => { setInput(''); setSuggestions([]); clear() }}>
            清空
          </button>
        </div>

        {suggestions.length > 0 && (
          <ul className="cl-suggestions" role="listbox" aria-label="搜索结果">
            {suggestions.map(hit => (
              <li key={hit.instrumentCode}>
                <button type="button" onClick={() => submit(hit.code)}>
                  <b>{hit.code}</b>
                  <span>{hit.name || '名称未标'}</span>
                  <em>{assetTypeLabel(hit.assetType)} · {hit.market || '市场未标'}</em>
                </button>
              </li>
            ))}
          </ul>
        )}
        {searching && suggestions.length === 0 && <p className="cl-search-hint">正在搜索标的…</p>}
        {!searching && input.trim().length >= 2 && !codeReady && suggestions.length === 0 && (
          <p className="cl-search-hint">
            没找到匹配的名称。也可以直接输入 6 位代码（如 600519、510300）。
          </p>
        )}

        {recent.length > 0 && !core && (
          <div className="cl-recent">
            <span>最近查询</span>
            {recent.map(code => (
              <button key={code} type="button" className="cl-chip" onClick={() => submit(code)}>{code}</button>
            ))}
          </div>
        )}
        <p className="cl-search-hint">
          进页面不会自动查询；每次查询都会读取本地仓库，缺失时才会在预算内同步。
        </p>
      </section>

      {coreLoading && !core && <LoadingSkeleton />}

      {!core && !coreLoading && coreFailure && (
        <FailureBlock failure={coreFailure} onRetry={() => submit()} />
      )}

      {!core && !coreLoading && !coreFailure && (
        <section className="cl-idle">
          <h3>还没有查询</h3>
          <p>
            填好代码后回车或点「查询」。首屏先给结论与六维评分；
            财务、资金流、估值曲线在切到对应标签页时才读取本地库。
          </p>
          <p className="cl-note">
            本页是<b>按需查询</b>：不订阅、不推送、不记录用户级查询日志。
            数据同步到本地仓库是为了减少对免费源的重复外呼，不是为每个用户建档。
          </p>
        </section>
      )}

      {core && (
        <>
          <DataQualityBanner core={core} notice={notice} failure={coreFailure} />

          <DecisionCard core={core} />
          <VerdictHero core={core} />
          <ScoreTiles dimensions={core.dimensions} />

          <nav className="cl-tabs" aria-label="详情标签页">
            {TABS.map(item => (
              <button
                key={item.key}
                type="button"
                className={item.key === tab ? 'is-active' : undefined}
                aria-current={item.key === tab ? 'page' : undefined}
                onClick={() => setTab(item.key)}
              >
                {item.label}
              </button>
            ))}
          </nav>

          {tab === 'overview' && (
            <OverviewTab
              core={core}
              detailLoading={detailLoading}
              detailFailure={detailFailure}
              detail={detail}
              series={series}
              seriesLoading={seriesLoading}
              seriesFailure={seriesFailure}
              priceRange={priceRange}
              onPriceRange={setPriceRange}
              brief={brief}
              briefLoading={briefLoading}
              briefFailure={briefFailure}
              onBrief={runBrief}
            />
          )}

          {tab === 'financial' && <FinancialTab detail={detail} />}

          {tab === 'valuation' && (
            <ValuationTab
              core={core}
              points={series[`${core.inputCode}:valuation:${valuationRange}`]?.valuation || []}
              loading={Boolean(seriesLoading[`${core.inputCode}:valuation:${valuationRange}`])}
              note={series[`${core.inputCode}:valuation:${valuationRange}`]?.note
                || seriesFailure[`${core.inputCode}:valuation:${valuationRange}`]?.message || null}
              range={valuationRange}
              onRange={setValuationRange}
            />
          )}

          {tab === 'technical' && (
            <TechnicalTab
              core={core}
              moneyflow={series[`${core.inputCode}:moneyflow:1y`]?.moneyflow || []}
              moneyflowLoading={Boolean(seriesLoading[`${core.inputCode}:moneyflow:1y`])}
              moneyflowNote={series[`${core.inputCode}:moneyflow:1y`]?.note
                || seriesFailure[`${core.inputCode}:moneyflow:1y`]?.message || null}
            />
          )}

          {tab === 'events' && <EventsTab detail={detail} />}

          {tab === 'peers' && (
            <PeersTab core={core} detail={detail} industry={detail?.industry.data || []} />
          )}

          {tab === 'provenance' && <ProvenanceTab core={core} series={series} detail={detail} />}

          <footer className="cl-footer">
            <p>{core.disclaimer}</p>
            <p className="cl-footer-meta">
              规则版本 {core.rulesVersion} · 快照 {core.snapshotId} · 数据时间 {dateTime(core.snapshotAt)}
            </p>
          </footer>
        </>
      )}
    </div>
  )
}

// =====================================================================
// 概览
// =====================================================================

function OverviewTab({ core, detail, detailLoading, detailFailure, series, seriesLoading,
  seriesFailure, priceRange, onPriceRange, brief, briefLoading, briefFailure, onBrief }: {
  core: CoreView
  detail: import('./types').DetailView | null
  detailLoading: boolean
  detailFailure: import('./useCodeLookup').LookupFailure | null
  series: Record<string, import('./types').SeriesView>
  seriesLoading: Record<string, boolean>
  seriesFailure: Record<string, import('./useCodeLookup').LookupFailure>
  priceRange: string
  onPriceRange: (range: string) => void
  brief: import('./types').BriefView | null
  briefLoading: boolean
  briefFailure: import('./useCodeLookup').LookupFailure | null
  onBrief: () => void
}) {
  const priceKey = `${core.inputCode}:price:${priceRange}`
  const priceSeries = series[priceKey]
  const priceError = seriesFailure[priceKey]

  return (
    <div className="cl-stack">
      <div className="cl-overview-grid">
        <PriceChart
          points={priceSeries?.price || []}
          interval={priceSeries?.interval || '1d'}
          loading={Boolean(seriesLoading[priceKey])}
          note={priceSeries?.note || priceError?.message || null}
          zoneLow={core.buyZone?.low ?? null}
          zoneHigh={core.buyZone?.high ?? null}
          stopLoss={core.buyZone?.stopLoss ?? null}
          range={priceRange}
          onRange={onPriceRange}
        />
        <ScoreRadar dimensions={core.dimensions} />
      </div>

      <KeyNumbers core={core} />

      {detail?.fundProfile?.data && <FundProfileCard section={detail.fundProfile} />}

      <LookbackTables core={core} />

      <section className="cl-subsection">
        <header>
          <h3>条件推演</h3>
          <span>三张固定情景；不显示概率、不给目标价</span>
        </header>
        {detailLoading && !detail
          ? <p className="cl-empty">正在读取明细并生成情景卡…</p>
          : detailFailure && !detail
            ? <p className="cl-empty">明细没有取到：{detailFailure.message}</p>
            : <ScenarioCards scenarios={detail?.scenarios || []} withheld={core.verdict.score === null} />}
      </section>

      <AiBriefPanel
        brief={brief}
        loading={briefLoading}
        failure={briefFailure}
        onGenerate={onBrief}
        disabled={core.verdict.score === null}
      />
      {core.verdict.score === null && (
        <p className="cl-note">数据不足时不会调用模型：没有可绑定的结论与证据。</p>
      )}
    </div>
  )
}

// =====================================================================
// 状态块
// =====================================================================

function LoadingSkeleton() {
  return (
    <div className="cl-skeleton" aria-label="正在查询">
      <div className="cl-skeleton-hero" />
      <div className="cl-skeleton-tiles">
        {Array.from({ length: 6 }).map((_, index) => <div key={index} />)}
      </div>
      <p className="cl-search-hint">正在取数并计算规则结论；首次查询可能触发一次同步。</p>
    </div>
  )
}

function FailureBlock({ failure, onRetry }: {
  failure: import('./useCodeLookup').LookupFailure
  onRetry: () => void
}) {
  const info = failureText(failure)
  return (
    <section className={`cl-failure cl-failure-${failure.kind}`}>
      <h3>{info.title}</h3>
      <p>{failure.message}</p>
      <p className="cl-note">{info.note}</p>
      {failure.kind === 'rate-limited' && (
        <p className="cl-note">
          <b>本页不会自动重试。</b>重试越频繁，封禁时间越长。
          {failure.message.includes('分钟') ? '' : '等提示的时间过去后再手动点一次。'}
        </p>
      )}
      <button type="button" className="cl-btn" disabled={failure.kind === 'bad-input'} onClick={onRetry}>
        手动再试一次
      </button>
    </section>
  )
}

function failureText(failure: import('./useCodeLookup').LookupFailure): { title: string; note: string } {
  switch (failure.kind) {
    case 'bad-input':
      return { title: '代码写法不认识', note: '改成 1–12 位字母或数字（如 600519、510300、SZ399006、NDX）。' }
    case 'not-found':
      return { title: '查不到这个代码', note: '数据源明确回答「没有这个代码」。改代码才有用，等一会儿再试同一个不会变。' }
    case 'rate-limited':
      return { title: '行情源正在限流', note: '这不是故障，也不需要改设置。东财按 IP 计时冷却。' }
    case 'unavailable':
      return { title: '数据底座暂不可用', note: '通常是部署或数据库迁移没有完成；点重试可能仍然无效，需要检查服务端。' }
    default:
      return { title: '本次没有取到数据', note: '等一会儿可以再试同一个代码。' }
  }
}
