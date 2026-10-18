import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart, PieChart, RadarChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([
  LineChart,
  BarChart,
  CandlestickChart,
  RadarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TitleComponent,
  CanvasRenderer,
])

export interface ChartColors {
  text: string
  muted: string
  subtle: string
  border: string
  accent: string
  accentStrong: string
  accentSoft: string
  success: string
  warning: string
  danger: string
  up: string
  down: string
  surface: string
  bg: string
}

function css(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

/**
 * 图表颜色**只在运行时从语义 token 读取**，不在组件里写死色值。
 * 深浅主题各有一套 token，切换主题时由 {@link EChart} 在下一帧重建 option。
 */
export function readChartColors(): ChartColors {
  return {
    text: css('--text', '#f4f7fb'),
    muted: css('--text-muted', '#9aa4b5'),
    subtle: css('--text-subtle', '#667085'),
    border: css('--border-strong', 'rgba(255,255,255,0.14)'),
    accent: css('--accent', '#8b9cff'),
    accentStrong: css('--accent-strong', '#a8b2ff'),
    accentSoft: css('--accent-soft', 'rgba(139,156,255,0.14)'),
    success: css('--success', '#3dd68c'),
    warning: css('--warning', '#f5c451'),
    danger: css('--danger', '#ff6b6b'),
    up: css('--up-red', '#ff4d4f'),
    down: css('--down-green', '#14b86a'),
    surface: css('--surface-1', '#161b22'),
    bg: css('--bg-elevated', '#0b0f19'),
  }
}

interface Props {
  /** 用回调而不是现成 option：主题切换后需要重新读 CSS 变量再生成一次。 */
  buildOption: (colors: ChartColors) => EChartsOption
  /** 主题令牌；变化时强制重建 option。 */
  theme: string
  height?: number
  ariaLabel?: string
  className?: string
}

/**
 * ECharts 的薄封装：按需注册、CSS token 上色、容器变化自动 resize、卸载即销毁。
 *
 * <p>**为什么在 requestAnimationFrame 里 setOption**：`data-theme` 是 ThemeProvider
 * 的 useEffect 写入的，而子组件的 effect 先于父组件执行；直接读会拿到上一套主题的颜色。
 * 挪到下一帧就能保证读到的已是新主题，同时避免在渲染期间读 DOM。
 */
export default function EChart({ buildOption, theme, height = 280, ariaLabel, className }: Props) {
  const holder = useRef<HTMLDivElement | null>(null)
  const plot = useRef<HTMLDivElement | null>(null)
  const chart = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    const node = plot.current
    if (!node) return
    const instance = echarts.init(node, undefined, { renderer: 'canvas' })
    chart.current = instance
    const observer = new ResizeObserver(() => instance.resize())
    if (holder.current) observer.observe(holder.current)
    return () => {
      observer.disconnect()
      instance.dispose()
      chart.current = null
    }
  }, [])

  useEffect(() => {
    const instance = chart.current
    if (!instance) return
    let frame = 0
    // 等 ThemeProvider 把 data-theme 写到 <html> 之后再读颜色。
    frame = requestAnimationFrame(() => {
      instance.setOption(buildOption(readChartColors()), { notMerge: true })
      instance.resize()
    })
    return () => cancelAnimationFrame(frame)
  }, [buildOption, theme])

  return (
    <div
      ref={holder}
      className={className ? `cl-chart ${className}` : 'cl-chart'}
      style={{ height }}
      role="img"
      aria-label={ariaLabel || '图表'}
    >
      {/*
        ECharts 会给 init 的节点写死一个像素宽度。如果那个节点在普通文档流里，
        它会把整页的 min-content 撑大；于是窄屏下页面不肯缩，ResizeObserver
        永远量到旧宽度。放进绝对定位的绘图层后，宽度不再参与布局计算。
      */}
      <div ref={plot} className="cl-chart-plot" />
    </div>
  )
}
