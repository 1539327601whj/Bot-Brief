import React, { useState, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import api from '../utils/api'
import { useAuth } from '../context/AuthContext'
import { getReportEditionInfo } from '../utils/reportEdition'
import DemoNotice from '../components/DemoNotice'
import './Chat.css'

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: SourceItem[]
}

interface SourceItem {
  id?: number | null
  title: string
  edition: string
  createdAt: string
}

const SUGGESTIONS = [
  '最近有哪些 AI 大模型更新？',
  '最近的 AI 安全新闻有哪些？',
  '有哪些新的开源 AI 项目？',
  '沪深300ETF 现在估值贵吗？'
]

export default function Chat() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const canSeePublicDigest = isDemo || user?.role === 'ADMIN'
  const navigate = useNavigate()
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }

  useEffect(() => {
    scrollToBottom()
  }, [messages])

  const handleSubmit = async (question?: string) => {
    const q = question || input.trim()
    if (isDemo || !q || loading) return

    const userMessage: Message = { role: 'user', content: q }
    setMessages(prev => [...prev, userMessage])
    setInput('')
    setLoading(true)
    setError('')

    const history = messages.slice(-6).map(item => ({ role: item.role, content: item.content }))

    try {
      const res = await api.post('/chat', { question: q, history })
      const result = res.data
      if (result.code === 200 && result.data) {
        const assistantMessage: Message = {
          role: 'assistant',
          content: result.data.answer || '抱歉，未能获取有效回答',
          sources: result.data.sources || []
        }
        setMessages(prev => [...prev, assistantMessage])
      } else {
        setError(result.message || '请求失败')
      }
    } catch (e: any) {
      setError(e?.response?.data?.message || '网络错误，请检查后端服务是否启动')
    } finally {
      setLoading(false)
      inputRef.current?.focus()
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (isDemo) return
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  const formatEdition = (edition: string) => {
    if (edition === 'w00_06' || edition === 'w06_12') return '🌅 早间主题'
    if (edition === 'w12_18' || edition === 'w18_24') return '🌙 晚间主题'
    const info = getReportEditionInfo(edition)
    return `${info.icon} ${info.shortLabel}`
  }

  return (
    <div className="chat-container">
      {isDemo && <DemoNotice />}
      {/* 头部 */}
      <header className="chat-header">
        <div className="header-title">
          <span className="ai-icon">💬</span>
          <span>AI 对话</span>
        </div>
        <div className="header-subtitle">{canSeePublicDigest ? '按主题检索科技日报与市场观察，再据此回答' : '按你勾选的主题检索，再据此回答'}</div>
      </header>

      {/* 消息区域 */}
      <div className="chat-messages">
        {messages.length === 0 && !loading && (
          <div className="welcome">
            <div className="welcome-icon">🤖</div>
            <h2>你好！我是 AI 小助手</h2>
            <p>{canSeePublicDigest ? '先定位对应主题的科技日报或行情简报，再据此回答。也可以接着追问。' : '先从你勾选的主题简报里找依据，再据此回答。也可以接着追问。'}</p>
            <div className="suggestions">
              {(canSeePublicDigest ? SUGGESTIONS : SUGGESTIONS.filter(s => !s.includes('ETF'))).map((s, i) => (
                <button
                  key={i}
                  className="suggestion-btn"
                  disabled={isDemo}
                  onClick={() => handleSubmit(s)}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((msg, index) => (
          <div key={index} className={`message ${msg.role}`}>
            <div className="message-avatar">
              {msg.role === 'user' ? '👤' : '🤖'}
            </div>
            <div className="message-content">
              <div className="message-text">
                {msg.content.split('\n').map((line, i) => (
                  <p key={i}>{line || <br/>}</p>
                ))}
              </div>
              {msg.sources && msg.sources.length > 0 && (
                <div className="sources">
                  <div className="sources-title">📚 参考来源</div>
                  {msg.sources.map((source, i) => (
                    <div
                      key={i}
                      className={`source-item${source.id ? '' : ' is-static'}`}
                      onClick={() => source.id && navigate(`/report/${source.id}`)}
                    >
                      <span className="source-tag">{formatEdition(source.edition)}</span>
                      <span className="source-title">{source.title}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}

        {loading && (
          <div className="message assistant">
            <div className="message-avatar">🤖</div>
            <div className="message-content">
              <div className="typing">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </div>
        )}

        {error && (
          <div className="error-message">❌ {error}</div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* 输入区域 */}
      <div className="chat-input-area">
        <div className="input-wrapper">
          <textarea
            ref={inputRef}
            className="chat-input"
            placeholder="输入问题..."
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={1}
            disabled={isDemo || loading}
          />
          <button
            className="send-btn"
            onClick={() => handleSubmit()}
            disabled={isDemo || !input.trim() || loading}
          >
            {loading ? (
              <span className="loading-spinner"></span>
            ) : (
              '发送'
            )}
          </button>
        </div>
        <div className="input-hint">{isDemo ? '公开 Demo 中 AI 对话不可提交' : '按 Enter 发送，Shift + Enter 换行；可接着上一问追问'}</div>
      </div>
    </div>
  )
}
