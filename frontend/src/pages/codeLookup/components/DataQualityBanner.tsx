import type { CoreView, DomainView } from '../types'
import type { LookupFailure } from '../useCodeLookup'
import { domainStatusText } from '../format'

interface Props {
  core: CoreView
  notice: string
  failure: LookupFailure | null
}

/** 需要用户知道的降级。全绿时不渲染——横幅常驻会让人对它免疫。 */
export default function DataQualityBanner({ core, notice, failure }: Props) {
  const stuck = Object.entries(core.data)
    .filter(([, view]) => view.status === 'FAILED' || view.status === 'PARTIAL')
    .map(([key, view]) => `${domainLabel(key)}：${domainStatusText(view.status)}`)

  const lowCoverage = core.verdict.coverage !== null && core.verdict.coverage < 0.5
  const hasAnything = Boolean(notice) || Boolean(failure) || core.syncPending || lowCoverage || stuck.length > 0
  if (!hasAnything) return null

  return (
    <div className="cl-banner" role="status">
      {notice && <p className="cl-banner-line">{notice}</p>}
      {failure && failure.kind === 'rate-limited' && (
        <p className="cl-banner-line cl-banner-warn">
          行情源正在限流：{failure.message}
          <b> 本页不会自动重试</b>，等提示的时间过去再手动查询。
        </p>
      )}
      {failure && failure.kind !== 'rate-limited' && (
        <p className="cl-banner-line cl-banner-warn">
          部分请求没有成功：{failure.message}
        </p>
      )}
      {core.syncPending && (
        <p className="cl-banner-line">
          后台正在同步财务 / 资金流等明细（通常 10–30 秒）。首屏结论仍然可用，
          页面会自动刷新，不需要反复点击。
        </p>
      )}
      {lowCoverage && (
        <p className="cl-banner-line">
          数据覆盖率不足 50%，因此<b>没有给出数值结论</b>。缺的是数据，不是这家公司有问题。
        </p>
      )}
      {stuck.length > 0 && (
        <p className="cl-banner-line">
          未完整取到的数据段：{stuck.join('、')}。每一段右上角都标了状态与原因。
        </p>
      )}
    </div>
  )
}

export function domainLabel(key: string): string {
  const labels: Record<string, string> = {
    quote: '行情',
    prices: '日线',
    valuations: '个股估值',
    indexValuation: '指数估值',
    financials: '财务',
    moneyflow: '资金流',
    indexProfile: '指数画像',
    fundProfile: '基金档案',
    industry: '行业权重',
    events: '新闻公告',
  }
  return labels[key] || key
}

export function domainEntries(data: Record<string, DomainView>): Array<[string, DomainView]> {
  return Object.entries(data)
}
