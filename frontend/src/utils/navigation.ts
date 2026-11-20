import type { NavigateFunction } from 'react-router-dom'

/**
 * 站内是否真的有「上一页」。
 *
 * react-router 把站内历史序号写在 `history.state.idx`：直接打开链接时是 0，
 * push 一次变 1。不能只看 `location.key !== 'default'`——用 replace 改 URL
 * （比如历史简报筛选、详情页翻页）也会换 key，但并没有上一页。
 */
export function canGoBackInApp() {
  const state = window.history.state as { idx?: number } | null
  if (state && typeof state.idx === 'number') return state.idx > 0
  return window.history.length > 1
}

/**
 * 返回上一步打开当前页的页面；确实没有上一页时才去 fallback。
 * fallback 用 replace，避免在历史里再叠一条没法继续返回的记录。
 */
export function goBackOr(navigate: NavigateFunction, fallback: string) {
  if (canGoBackInApp()) navigate(-1)
  else navigate(fallback, { replace: true })
}
