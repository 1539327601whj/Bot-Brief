import { useCallback } from 'react'
import type { EChartsOption } from 'echarts'
import EChart, { type ChartColors } from '../../../components/EChart'
import { useTheme } from '../../../context/ThemeContext'
import type {
  DimensionView,
  FinancialView,
  IndustryView,
  MoneyFlowPoint,
  PricePoint,
  ValuationPoint,
} from '../types'
import { num } from '../format'

const RANGES = [
  { key: '1y', label: '近 1 年' },
  { key: '3y', label: '近 3 年' },
  { key: '5y', label: '近 5 年' },
  { key: '10y', label: '近 10 年' },
]

interface RangeProps {
  range: string
  onRange: (range: string) => void
}

function RangeSwitch({ range, onRange }: RangeProps) {
  return (
    <div className="cl-range-switch" role="group" aria-label="图表区间">
      {RANGES.map(item => (
        <button
          key={item.key}
          type="button"
          className={item.key === range ? 'is-active' : undefined}
          onClick={() => onRange(item.key)}
        >
          {item.label}
        </button>
      ))}
    </div>
  )
}

/** 简单移动平均；窗口不足的位置返回 null，ECharts 会自然断线，而不是画一条假线。 */
export function movingAverage(points: PricePoint[], window: number): Array<number | null> {
  const out: Array<number | null> = []
  let sum = 0
  for (let i = 0; i < points.length; i += 1) {
    const close = points[i].close
    if (close === null || close === undefined) {
      out.push(null)
      continue
    }
    sum += Number(close)
    if (i >= window) {
      const drop = points[i - window].close
      if (drop !== null && drop !== undefined) sum -= Number(drop)
    }
    out.push(i >= window - 1 ? sum / window : null)
  }
  return out
}

// ---------------------------------------------------------------------
// 价格
// ---------------------------------------------------------------------

interface PriceChartProps extends RangeProps {
  points: PricePoint[]
  interval: string
  loading: boolean
  note: string | null
  zoneLow: number | null
  zoneHigh: number | null
  stopLoss: number | null
}

export function PriceChart({ points, interval, loading, note, zoneLow, zoneHigh, stopLoss,
  range, onRange }: PriceChartProps) {
  const { theme } = useTheme()
  const hasOhlc = points.some(p => p.open !== null && p.high !== null && p.low !== null)
  const ma20 = movingAverage(points, 20)
  const ma60 = movingAverage(points, 60)
  const ma250 = movingAverage(points, 250)

  const buildOption = useCallback((c: ChartColors): EChartsOption => {
    const categories = points.map(p => p.date)
    const priceSeries = hasOhlc
      ? [{
          name: 'K 线',
          type: 'candlestick' as const,
          data: points.map(p => [p.open ?? p.close, p.close, p.low ?? p.close, p.high ?? p.close]),
          itemStyle: {
            color: c.up,
            color0: c.down,
            borderColor: c.up,
            borderColor0: c.down,
          },
        }]
      : [{
          name: '收盘价',
          type: 'line' as const,
          data: points.map(p => p.close),
          showSymbol: false,
          lineStyle: { color: c.accent, width: 1.6 },
        }]

    const marks: Array<{ value: number; label: string; color: string }> = []
    if (zoneLow !== null) marks.push({ value: zoneLow, label: '参考区间下沿', color: c.success })
    if (zoneHigh !== null) marks.push({ value: zoneHigh, label: '参考区间上沿', color: c.success })
    if (stopLoss !== null) marks.push({ value: stopLoss, label: '参考止损', color: c.danger })

    return {
      animation: false,
      backgroundColor: 'transparent',
      grid: { left: 8, right: 16, top: 34, bottom: 66, containLabel: true },
      legend: {
        top: 0,
        textStyle: { color: c.muted, fontSize: 11 },
        itemWidth: 12,
        itemHeight: 8,
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        backgroundColor: c.surface,
        borderColor: c.border,
        textStyle: { color: c.text, fontSize: 12 },
      },
      xAxis: {
        type: 'category',
        data: categories,
        axisLine: { lineStyle: { color: c.border } },
        axisLabel: { color: c.muted, fontSize: 10 },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: c.muted, fontSize: 10 },
        splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
      },
      dataZoom: [
        { type: 'inside', start: 0, end: 100 },
        { type: 'slider', height: 16, bottom: 8, borderColor: c.border,
          textStyle: { color: c.muted }, fillerColor: c.accentSoft },
      ],
      series: [
        ...priceSeries,
        {
          name: 'MA20',
          type: 'line',
          data: ma20,
          showSymbol: false,
          lineStyle: { color: c.warning, width: 1 },
        },
        {
          name: 'MA60',
          type: 'line',
          data: ma60,
          showSymbol: false,
          lineStyle: { color: c.accent, width: 1 },
        },
        {
          name: 'MA250',
          type: 'line',
          data: ma250,
          showSymbol: false,
          lineStyle: { color: c.accentStrong, width: 1, type: 'dashed' },
        },
        ...(priceSeries[0].type === 'candlestick' && marks.length > 0
          ? [{
              name: '参考位',
              type: 'line' as const,
              data: [] as number[],
              markLine: {
                symbol: 'none',
                data: marks.map(mark => ({
                  yAxis: mark.value,
                  name: mark.label,
                  lineStyle: { color: mark.color, type: 'dashed' as const },
                  label: { color: mark.color, fontSize: 10, formatter: `${mark.label} {c}` },
                })),
              },
            }]
          : []),
      ],
    }
  }, [points, hasOhlc, ma20, ma60, ma250, zoneLow, zoneHigh, stopLoss])

  return (
    <div className="cl-chart-card">
      <header>
        <div>
          <h4>价格走势</h4>
          <span>{interval === '1w' ? '周线（后端降采样）' : '日线'} · 前复权</span>
        </div>
        <RangeSwitch range={range} onRange={onRange} />
      </header>
      {loading ? <p className="cl-empty">正在读取本地日线…</p> : points.length === 0
        ? <p className="cl-empty">{note || '本地还没有日线数据。'}</p>
        : <EChart buildOption={buildOption} theme={theme} height={320} ariaLabel="价格走势图" />}
      {note && points.length > 0 && <p className="cl-note">{note}</p>}
    </div>
  )
}

// ---------------------------------------------------------------------
// 六维雷达
// ---------------------------------------------------------------------

export function ScoreRadar({ dimensions }: { dimensions: DimensionView[] }) {
  const { theme } = useTheme()
  const buildOption = useCallback((c: ChartColors): EChartsOption => ({
    animation: false,
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: c.surface,
      borderColor: c.border,
      textStyle: { color: c.text },
    },
    radar: {
      radius: '66%',
      indicator: dimensions.map(d => ({ name: d.label, max: 100 })),
      axisName: { color: c.muted, fontSize: 11 },
      splitLine: { lineStyle: { color: c.border } },
      splitArea: { areaStyle: { color: ['transparent'] } },
      axisLine: { lineStyle: { color: c.border } },
    },
    series: [{
      type: 'radar',
      data: [{
        value: dimensions.map(d => (d.score === null ? 0 : Number(d.score))),
        name: '得分',
        symbolSize: 4,
        lineStyle: { color: c.accent, width: 2 },
        itemStyle: { color: c.accent },
        areaStyle: { color: c.accentSoft },
      }],
    }],
  }), [dimensions])

  return (
    <div className="cl-chart-card">
      <header><div><h4>六维雷达</h4><span>不适用 / 未评分的维度按 0 画，仅用于看形状</span></div></header>
      <EChart buildOption={buildOption} theme={theme} height={280} ariaLabel="六维评分雷达图" />
    </div>
  )
}

// ---------------------------------------------------------------------
// 估值
// ---------------------------------------------------------------------

export function ValuationChart({ points, loading, note, range, onRange }: {
  points: ValuationPoint[]
  loading: boolean
  note: string | null
} & RangeProps) {
  const { theme } = useTheme()
  const buildOption = useCallback((c: ChartColors): EChartsOption => ({
    animation: false,
    backgroundColor: 'transparent',
    grid: { left: 8, right: 8, top: 34, bottom: 56, containLabel: true },
    legend: { top: 0, textStyle: { color: c.muted, fontSize: 11 }, itemWidth: 12, itemHeight: 8 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.surface,
      borderColor: c.border,
      textStyle: { color: c.text, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: points.map(p => p.date),
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.muted, fontSize: 10 },
    },
    yAxis: [
      {
        type: 'value',
        name: 'PE(TTM)',
        scale: true,
        axisLabel: { color: c.muted, fontSize: 10 },
        nameTextStyle: { color: c.muted, fontSize: 10 },
        splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
      },
      {
        type: 'value',
        name: '分位 %',
        min: 0,
        max: 100,
        axisLabel: { color: c.muted, fontSize: 10 },
        nameTextStyle: { color: c.muted, fontSize: 10 },
        splitLine: { show: false },
      },
    ],
    dataZoom: [{ type: 'inside', start: 0, end: 100 }],
    series: [
      {
        name: 'PE(TTM)',
        type: 'line',
        data: points.map(p => p.peTtm),
        showSymbol: false,
        connectNulls: false,
        lineStyle: { color: c.accent, width: 1.4 },
      },
      {
        name: 'PE 分位',
        type: 'line',
        yAxisIndex: 1,
        data: points.map(p => p.percentile),
        showSymbol: false,
        lineStyle: { color: c.warning, width: 1.2, type: 'dashed' },
      },
    ],
  }), [points])

  return (
    <div className="cl-chart-card">
      <header>
        <div><h4>估值历史</h4><span>PE 与滚动分位同一条序列</span></div>
        <RangeSwitch range={range} onRange={onRange} />
      </header>
      {loading ? <p className="cl-empty">正在读取估值历史…</p> : points.length === 0
        ? <p className="cl-empty">{note || '本地没有这一段估值历史。'}</p>
        : <EChart buildOption={buildOption} theme={theme} height={300} ariaLabel="估值历史图" />}
      {note && points.length > 0 && <p className="cl-note">{note}</p>}
    </div>
  )
}

// ---------------------------------------------------------------------
// 财务
// ---------------------------------------------------------------------

export function FinancialTrendChart({ reports }: { reports: FinancialView[] }) {
  const { theme } = useTheme()
  const ordered = [...reports].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const buildOption = useCallback((c: ChartColors): EChartsOption => ({
    animation: false,
    backgroundColor: 'transparent',
    grid: { left: 8, right: 8, top: 34, bottom: 28, containLabel: true },
    legend: { top: 0, textStyle: { color: c.muted, fontSize: 11 }, itemWidth: 12, itemHeight: 8 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.surface,
      borderColor: c.border,
      textStyle: { color: c.text, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: ordered.map(r => r.reportDate.slice(0, 10)),
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.muted, fontSize: 10 },
    },
    yAxis: [
      {
        type: 'value',
        name: '同比 %',
        axisLabel: { color: c.muted, fontSize: 10 },
        nameTextStyle: { color: c.muted, fontSize: 10 },
        splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
      },
      {
        type: 'value',
        name: 'ROE/毛利率 %',
        axisLabel: { color: c.muted, fontSize: 10 },
        nameTextStyle: { color: c.muted, fontSize: 10 },
        splitLine: { show: false },
      },
    ],
    series: [
      { name: '营收同比', type: 'bar', data: ordered.map(r => r.revenueYoy), itemStyle: { color: c.accent } },
      { name: '净利同比', type: 'bar', data: ordered.map(r => r.netProfitYoy), itemStyle: { color: c.accentStrong } },
      { name: 'ROE', type: 'line', yAxisIndex: 1, data: ordered.map(r => r.roe), lineStyle: { color: c.success }, itemStyle: { color: c.success } },
      { name: '毛利率', type: 'line', yAxisIndex: 1, data: ordered.map(r => r.grossMargin), lineStyle: { color: c.warning }, itemStyle: { color: c.warning } },
    ],
  }), [ordered])

  if (ordered.length === 0) return null
  return (
    <div className="cl-chart-card">
      <header><div><h4>财务趋势</h4><span>按报告期，左轴同比、右轴比率</span></div></header>
      <EChart buildOption={buildOption} theme={theme} height={300} ariaLabel="财务趋势图" />
    </div>
  )
}

// ---------------------------------------------------------------------
// 资金流
// ---------------------------------------------------------------------

export function MoneyFlowChart({ points, loading, note }: {
  points: MoneyFlowPoint[]
  loading: boolean
  note: string | null
}) {
  const { theme } = useTheme()
  const ordered = [...points].sort((a, b) => a.date.localeCompare(b.date))
  const inYi = (value: number | null) => (value === null ? null : Number(value) / 1e8)
  const main = ordered.map(p => inYi(p.mainNet))

  const buildOption = useCallback((c: ChartColors): EChartsOption => ({
    animation: false,
    backgroundColor: 'transparent',
    grid: { left: 8, right: 8, top: 34, bottom: 28, containLabel: true },
    legend: { top: 0, textStyle: { color: c.muted, fontSize: 11 }, itemWidth: 12, itemHeight: 8 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.surface,
      borderColor: c.border,
      textStyle: { color: c.text, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: ordered.map(p => p.date),
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.muted, fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      name: '亿元',
      axisLabel: { color: c.muted, fontSize: 10 },
      nameTextStyle: { color: c.muted, fontSize: 10 },
      splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
    },
    series: [
      {
        name: '主力净流入',
        type: 'bar',
        data: main.map(value => ({
          value,
          itemStyle: { color: value !== null && value >= 0 ? c.up : c.down },
        })),
      },
    ],
  }), [ordered, main])

  return (
    <div className="cl-chart-card">
      <header><div><h4>主力资金净流入</h4><span>红柱净流入、绿柱净流出；单位亿元</span></div></header>
      {loading ? <p className="cl-empty">正在读取资金流…</p> : ordered.length === 0
        ? <p className="cl-empty">{note || '本地没有资金流数据。'}</p>
        : <EChart buildOption={buildOption} theme={theme} height={280} ariaLabel="主力资金净流入图" />}
      {note && ordered.length > 0 && <p className="cl-note">{note}</p>}
    </div>
  )
}

// ---------------------------------------------------------------------
// 行业权重
// ---------------------------------------------------------------------

export function IndustryDonut({ items }: { items: IndustryView[] }) {
  const { theme } = useTheme()
  const usable = items.filter((item): item is IndustryView & { weight: number } => item.weight !== null)
  const buildOption = useCallback((c: ChartColors): EChartsOption => ({
    animation: false,
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      backgroundColor: c.surface,
      borderColor: c.border,
      textStyle: { color: c.text, fontSize: 12 },
    },
    legend: {
      bottom: 0,
      textStyle: { color: c.muted, fontSize: 11 },
      type: 'scroll',
    },
    series: [{
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '44%'],
      data: usable.map(item => ({ name: item.industryName, value: item.weight })),
      label: { color: c.muted, fontSize: 10, formatter: '{b} {d}%' },
      labelLine: { lineStyle: { color: c.border } },
      itemStyle: { borderColor: c.bg, borderWidth: 2 },
    }],
  }), [usable])

  if (usable.length === 0) return null
  return (
    <div className="cl-chart-card">
      <header><div><h4>行业权重</h4><span>来源见「口径」标签页</span></div></header>
      <EChart buildOption={buildOption} theme={theme} height={300} ariaLabel="行业权重占比图" />
    </div>
  )
}

/** 数字进度条只用于小卡片，不画成图表。 */
export function MiniBar({ value }: { value: number | null }) {
  if (value === null || value === undefined) return <span className="cl-muted">未给</span>
  return (
    <span className="cl-mini-bar" aria-hidden>
      <span style={{ width: `${Math.max(0, Math.min(100, Number(value)))}%` }} />
      <em>{num(value, 0)}</em>
    </span>
  )
}
