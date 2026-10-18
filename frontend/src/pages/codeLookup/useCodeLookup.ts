import { useCallback, useEffect, useRef, useState } from 'react'
import api from '../../utils/api'
import type {
  BriefView,
  CoreView,
  DetailView,
  SeriesView,
  StatusView,
} from './types'

export type FailureKind = 'bad-input' | 'not-found' | 'rate-limited' | 'unavailable' | 'error'

export interface LookupFailure {
  kind: FailureKind
  status: number
  message: string
}

export function failureOf(error: unknown): LookupFailure {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })?.response
  const status = response?.data?.code ?? (error as { response?: { status?: number } })?.response?.status ?? 500
  const message = response?.data?.message || (error as Error)?.message || '请求失败，请稍后再试'
  return { kind: kindOf(status), status, message }
}

function kindOf(status: number): FailureKind {
  if (status === 400) return 'bad-input'
  if (status === 404) return 'not-found'
  if (status === 429) return 'rate-limited'
  if (status === 503) return 'unavailable'
  return 'error'
}

/**
 * 统一解包 `Result<T>`。HTTP 200 也可能带业务错误码，所以这里**必须看 body.code**，
 * 不能只看 axios 有没有抛异常。
 */
function unwrap<T>(payload: { code?: number; message?: string; data?: T } | undefined): T {
  if (payload?.code === 200 && payload.data !== undefined) return payload.data
  const status = payload?.code && payload.code !== 200 ? payload.code : 500
  throw { kind: kindOf(status), status, message: payload?.message || '查询未返回结果' } as LookupFailure
}

interface SeriesKey {
  kind: 'price' | 'valuation' | 'moneyflow'
  range: string
}

interface CoreLoadOptions {
  /** 后台同步完成/快照变化后的刷新：不打断当前画面，失败也不清空已有结论。 */
  quiet?: boolean
}

const RECENT_KEY = 'brief.codeLookup.recent'

export function readRecentCodes(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY)
    const list = raw ? (JSON.parse(raw) as unknown) : []
    return Array.isArray(list) ? list.filter(v => typeof v === 'string').slice(0, 8) : []
  } catch {
    return []
  }
}

function rememberCode(code: string) {
  try {
    const next = [code, ...readRecentCodes().filter(c => c !== code)].slice(0, 8)
    localStorage.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    // 隐私模式下 localStorage 会抛异常；记不住最近查询不影响查询本身
  }
}

export function useCodeLookup() {
  const [core, setCore] = useState<CoreView | null>(null)
  const [coreLoading, setCoreLoading] = useState(false)
  const [coreFailure, setCoreFailure] = useState<LookupFailure | null>(null)

  const [detail, setDetail] = useState<DetailView | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailFailure, setDetailFailure] = useState<LookupFailure | null>(null)

  const [status, setStatus] = useState<StatusView | null>(null)
  const [brief, setBrief] = useState<BriefView | null>(null)
  const [briefLoading, setBriefLoading] = useState(false)
  const [briefFailure, setBriefFailure] = useState<LookupFailure | null>(null)

  const [series, setSeries] = useState<Record<string, SeriesView>>({})
  const [seriesLoading, setSeriesLoading] = useState<Record<string, boolean>>({})
  const [seriesFailure, setSeriesFailure] = useState<Record<string, LookupFailure>>({})

  const [notice, setNotice] = useState('')
  const [recent, setRecent] = useState<string[]>(readRecentCodes)

  /** 每次用户发起新查询就自增；晚到的旧响应一律丢弃，避免两只标的的数字混在一页。 */
  const seq = useRef(0)
  const autoRefresh = useRef(0)
  const detailKey = useRef('')

  const resetForNewQuery = useCallback(() => {
    setDetail(null)
    setDetailFailure(null)
    setStatus(null)
    setBrief(null)
    setBriefFailure(null)
    setSeries({})
    setSeriesLoading({})
    setSeriesFailure({})
    setNotice('')
    autoRefresh.current = 0
    detailKey.current = ''
  }, [])

  const fetchDetail = useCallback(async (code: string, snapshotId: string, token: number) => {
    setDetailLoading(true)
    try {
      const res = await api.get('/code-lookup/v2/detail', {
        params: { code, snapshotId },
      })
      const data = unwrap<DetailView>(res.data)
      if (token !== seq.current) return
      setDetail(data)
      setDetailFailure(null)
      if (data.snapshotChanged && autoRefresh.current < 2) {
        const key = `${code}:${data.snapshotId}`
        if (detailKey.current !== key) {
          detailKey.current = key
          autoRefresh.current += 1
          setNotice('数据在明细加载期间已更新，已重新拉取首屏与明细。')
          const fresh = await api.get('/code-lookup/v2/core', { params: { code } })
          const freshCore = unwrap<CoreView>(fresh.data)
          if (token !== seq.current) return
          setCore(freshCore)
          setBrief(null)
          const next = await api.get('/code-lookup/v2/detail', {
            params: { code, snapshotId: freshCore.snapshotId },
          })
          const freshDetail = unwrap<DetailView>(next.data)
          if (token !== seq.current) return
          setDetail(freshDetail)
        }
      }
    } catch (e) {
      if (token !== seq.current) return
      const failure = 'kind' in (e as object) ? (e as LookupFailure) : failureOf(e)
      setDetailFailure(failure)
    } finally {
      if (token === seq.current) setDetailLoading(false)
    }
  }, [])

  const load = useCallback(async (rawCode: string, options: CoreLoadOptions = {}) => {
    const code = rawCode.trim()
    if (!code) return
    const token = ++seq.current
    if (!options.quiet) {
      resetForNewQuery()
      setCore(null)
      setCoreFailure(null)
      setCoreLoading(true)
    }
    try {
      const res = await api.get('/code-lookup/v2/core', { params: { code } })
      const data = unwrap<CoreView>(res.data)
      if (token !== seq.current) return
      setCore(data)
      setCoreFailure(null)
      rememberCode(code)
      setRecent(readRecentCodes())
      void fetchDetail(code, data.snapshotId, token)
    } catch (e) {
      if (token !== seq.current) return
      const failure = 'kind' in (e as object) ? (e as LookupFailure) : failureOf(e)
      setCoreFailure(failure)
      // 429 之后**绝不再自动发起任何请求**；轮询/明细都由页面状态挡住。
      if (!options.quiet && failure.kind !== 'rate-limited') setCore(null)
    } finally {
      if (token === seq.current) setCoreLoading(false)
    }
  }, [fetchDetail, resetForNewQuery])

  /**
   * 序列按需拉取，结果按 `code:kind:range` 缓存。
   * **只读本地库**，所以切换标签页/区间不会产生行情源外呼；失败也不清空首屏。
   */
  const loadSeries = useCallback(async (kind: SeriesKey['kind'], range: string) => {
    if (!core) return
    const code = core.inputCode
    const key = `${code}:${kind}:${range}`
    // 失败过就不再自动重试：429 之后每重试一次都会延长封禁。
    // 用户换一个区间/标签页会产生新 key，仍可继续看其他本地序列。
    if (series[key] || seriesLoading[key] || seriesFailure[key]) return
    const token = seq.current
    setSeriesLoading(prev => ({ ...prev, [key]: true }))
    try {
      const res = await api.get('/code-lookup/v2/series', {
        params: { code, kind, range },
      })
      const data = unwrap<SeriesView>(res.data)
      if (token !== seq.current) return
      setSeries(prev => ({ ...prev, [key]: data }))
      setSeriesFailure(prev => {
        const next = { ...prev }
        delete next[key]
        return next
      })
    } catch (e) {
      if (token !== seq.current) return
      const failure = 'kind' in (e as object) ? (e as LookupFailure) : failureOf(e)
      setSeriesFailure(prev => ({ ...prev, [key]: failure }))
    } finally {
      if (token === seq.current) {
        setSeriesLoading(prev => {
          const next = { ...prev }
          delete next[key]
          return next
        })
      }
    }
  }, [core, series, seriesLoading, seriesFailure])

  const runBrief = useCallback(async () => {
    if (!core) return
    setBriefLoading(true)
    setBriefFailure(null)
    const token = seq.current
    try {
      const res = await api.get('/code-lookup/v2/brief', {
        params: { code: core.inputCode, snapshotId: core.snapshotId },
      })
      const data = unwrap<BriefView>(res.data)
      if (token !== seq.current) return
      setBrief(data)
    } catch (e) {
      if (token !== seq.current) return
      const failure = 'kind' in (e as object) ? (e as LookupFailure) : failureOf(e)
      setBriefFailure(failure)
    } finally {
      if (token === seq.current) setBriefLoading(false)
    }
  }, [core])

  const clear = useCallback(() => {
    seq.current += 1
    resetForNewQuery()
    setCore(null)
    setCoreFailure(null)
    setCoreLoading(false)
  }, [resetForNewQuery])

  /**
   * 后台同步未完时轮询 status；完成后重拉一次 core + detail。
   *
   * 用 setTimeout 链而不是 setInterval：一次请求慢于间隔时不会叠加并发。
   * 最多 8 次（约 45 秒），429 立即停止并且**不自动重试**。
   */
  useEffect(() => {
    if (!core?.syncPending) return
    let cancelled = false
    let attempts = 0
    let timer = 0
    const code = core.inputCode
    const token = seq.current

    const tick = async () => {
      if (cancelled || token !== seq.current) return
      attempts += 1
      try {
        const res = await api.get('/code-lookup/v2/status', { params: { code } })
        const data = unwrap<StatusView>(res.data)
        if (cancelled || token !== seq.current) return
        setStatus(data)
        if (!data.syncPending) {
          void load(code, { quiet: true })
          return
        }
      } catch (e) {
        if (cancelled || token !== seq.current) return
        const failure = 'kind' in (e as object) ? (e as LookupFailure) : failureOf(e)
        setCoreFailure(failure)
        if (failure.kind === 'rate-limited') return
      }
      if (attempts < 8) timer = window.setTimeout(tick, 6000)
    }

    timer = window.setTimeout(tick, 4000)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [core?.syncPending, core?.inputCode, core?.snapshotId, load])

  return {
    core,
    coreLoading,
    coreFailure,
    detail,
    detailLoading,
    detailFailure,
    status,
    brief,
    briefLoading,
    briefFailure,
    series,
    seriesLoading,
    seriesFailure,
    notice,
    recent,
    load,
    loadSeries,
    runBrief,
    clear,
  }
}
