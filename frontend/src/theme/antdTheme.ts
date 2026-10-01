import { theme, type ThemeConfig } from 'antd'
import type { ThemeMode } from '../context/ThemeContext'

/**
 * antd 组件的主题。
 *
 * 深色分支的取值就是原来那四个页面各自写死的那一份，一字不改；
 * 每个页面的额外覆盖（ContentGrowth 的 success/warning/error、Subscription 的阴影）
 * 单独用下面的 extras 函数补，避免改到别的页面。
 */
const BASE: Record<ThemeMode, ThemeConfig['token']> = {
  dark: {
    colorPrimary: '#8b9cff',
    colorPrimaryHover: '#a8b2ff',
    colorBgBase: '#05070d',
    colorBgContainer: '#0d111b',
    colorBgElevated: '#111620',
    colorBorder: 'rgba(255, 255, 255, 0.14)',
    colorText: '#f4f7fb',
    colorTextSecondary: '#9aa4b5',
    colorTextPlaceholder: '#667085',
    borderRadius: 12,
  },
  light: {
    colorPrimary: '#5b6ee0',
    colorPrimaryHover: '#4f5fd6',
    colorBgBase: '#f4f5f9',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorBorder: 'rgba(16, 24, 40, 0.16)',
    colorText: '#101828',
    colorTextSecondary: '#5a6577',
    colorTextPlaceholder: '#7c8698',
    borderRadius: 12,
  },
}

export function getAntdTheme(mode: ThemeMode): ThemeConfig {
  return {
    algorithm: mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: { ...BASE[mode] },
  }
}

export function withAntdExtras(mode: ThemeMode, extras: ThemeConfig['token']): ThemeConfig {
  const base = getAntdTheme(mode)
  return { ...base, token: { ...base.token, ...extras } }
}

/** 内容增长页：多了状态色和日志/弹窗尺寸 */
export function contentGrowthTheme(mode: ThemeMode): ThemeConfig {
  return withAntdExtras(mode, mode === 'dark' ? {
    colorBorderSecondary: 'rgba(255, 255, 255, 0.08)',
    colorTextPlaceholder: '#667085',
    colorSuccess: '#3dd68c',
    colorWarning: '#f5c451',
    colorError: '#ff6b6b',
    borderRadiusLG: 18,
    boxShadowSecondary: '0 24px 80px rgba(0, 0, 0, 0.42)',
  } : {
    colorBorderSecondary: 'rgba(16, 24, 40, 0.08)',
    colorTextPlaceholder: '#7c8698',
    colorSuccess: '#12a05c',
    colorWarning: '#a2660b',
    colorError: '#cf3a3a',
    borderRadiusLG: 18,
    boxShadowSecondary: '0 18px 48px rgba(16, 24, 40, 0.14)',
  })
}

/** 订阅管理页：只有弹窗阴影和别人不一样 */
export function subscriptionTheme(mode: ThemeMode): ThemeConfig {
  return withAntdExtras(mode, {
    boxShadowSecondary: mode === 'dark'
      ? '0 24px 80px rgba(0, 0, 0, 0.48)'
      : '0 18px 48px rgba(16, 24, 40, 0.14)',
  })
}