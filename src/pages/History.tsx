import React, { useState, useEffect } from 'react'
import './History.css'

interface Report {
  id: number
  title: string
  date: string
  time: string
  category: string
  version: string
  summary: string
}

export default function History() {
  const [reports, setReports] = useState<Report[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    // 模拟数据
    const mockReports: Report[] = [
      {
        id: 1,
        title: 'AI 领域每日简报',
        date: '2026-04-26',
        time: '08:00',
        category: 'AI',
        version: 'v1.2.3',
        summary: '今日简报涵盖 GPT-5 发布预告、Claude 4 新功能、Midjourney V7 更新等重要资讯。'
      },
      {
        id: 2,
        title: 'AI 领域每日简报',
        date: '2026-04-26',
        time: '20:00',
        category: 'AI',
        version: 'v1.2.2',
        summary: '晚间版简报包含 AI 编程工具最新动态、大模型开源进展、以及开发者社区热点讨论。'
      },
      {
        id: 3,
        title: 'AI 领域每日简报',
        date: '2026-04-25',
        time: '08:00',
        category: 'AI',
        version: 'v1.2.1',
        summary: '今日聚焦 AI Agent 发展趋势、OpenAI 最新研究论文、以及多模态模型应用案例。'
      },
      {
        id: 4,
        title: 'AI 领域每日简报',
        date: '2026-04-25',
        time: '20:00',
        category: 'AI',
        version: 'v1.2.0',
        summary: '晚间版简报涵盖 AI 安全治理讨论、监管政策动态、以及行业应用最新进展。'
      },
      {
        id: 5,
        title: 'AI 领域每日简报',
        date: '2026-04-24',
        time: '08:00',
        category: 'AI',
        version: 'v1.1.9',
        summary: '今日简报包括 Google I/O 大会 AI 预告、Meta 开源模型更新、以及 AI 芯片市场竞争分析。'
      },
      {
        id: 6,
        title: 'AI 领域每日简报',
        date: '2026-04-24',
        time: '20:00',
        category: 'AI',
        version: 'v1.1.8',
        summary: '晚间版简报聚焦 AI 教育应用、医疗 AI 突破、以及 AI 助手交互体验升级。'
      }
    ]
    
    setTimeout(() => {
      setReports(mockReports)
      setLoading(false)
    }, 300)
  }, [])

  const getCategoryColor = (category: string) => {
    const colors: Record<string, string> = {
      'AI': '#00d4aa',
      'Tech': '#7c4dff',
      'Science': '#ff6b6b',
      'Business': '#ffd93d'
    }
    return colors[category] || '#00d4aa'
  }

  const getTimeIcon = (time: string) => {
    const hour = parseInt(time.split(':')[0])
    return hour < 12 ? '🌅' : '🌙'
  }

  if (loading) {
    return (
      <div className="history-page">
        <div className="loading-spinner">
          <div className="spinner"></div>
          <p>加载中...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="history-page">
      <div className="history-header">
        <div className="header-info">
          <h2>📋 历史简报</h2>
          <p className="header-desc">查看所有已生成的 AI 简报</p>
        </div>
        <div className="header-stats">
          <div className="stat-badge">
            <span className="stat-number">{reports.length}</span>
            <span className="stat-label">总计</span>
          </div>
        </div>
      </div>

      <div className="reports-list">
        {reports.map(report => (
          <div key={report.id} className="report-card" onClick={() => window.location.href = `/report/${report.id}`}>
            <div className="report-icon">
              {getTimeIcon(report.time)}
            </div>
            <div className="report-content">
              <div className="report-meta">
                <span className="report-category" style={{ borderColor: getCategoryColor(report.category) }}>
                  {report.category}
                </span>
                <span className="report-version">{report.version}</span>
              </div>
              <h3 className="report-title">{report.title}</h3>
              <p className="report-summary">{report.summary}</p>
              <div className="report-footer">
                <span className="report-date">{report.date}</span>
                <span className="report-time">{report.time}</span>
              </div>
            </div>
            <div className="report-arrow">
              →
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
