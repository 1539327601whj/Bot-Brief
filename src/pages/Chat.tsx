import React, { useState, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import './Chat.css'

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: SourceItem[]
}

interface SourceItem {
  id: number
  title: string
  edition: string
  createdAt: string
}

const SUGGESTIONS = [
  '最近有哪些 AI 大模型更新？',
  '解释一下 RAG 技术',
  '最近的 AI 安全新闻有哪些？',
  '有哪些新的开源 AI 项目？'
]

export default function Chat() {
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
    if (!q || loading) return

    const userMessage: Message = { role: 'user', content: q }
    setMessages(prev => [...prev, userMessage])
    setInput('')
    setLoading(true)
    setError('')

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: q })
      })
      const result = await res.json()
      
      if (result.code === 200) {
        const assistantMessage: Message = {
          role: 'assistant',
          content: result.data.answer,
          sources: result.data.sources
        }
        setMessages(prev => [...prev, assistantMessage])
      } else {
        setError(result.message || '请求失败')
      }
    } catch (e) {
      setError('网络错误，请检查后端服务是否启动')
    } finally {
      setLoading(false)
      inputRef.current?.focus()
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  const formatEdition = (edition: string) => {
    return edition === 'morning' ? '🌅 早间' : '🌙 晚间'
  }

  return (
    <div className="chat-container">
      {/* 头部 */}
      <header className="chat-header">
        <div className="header-title">
          <span className="ai-icon">💬</span>
          <span>AI 对话</span>
        </div>
        <div className="header-subtitle">基于历史简报的智能问答 (RAG)</div>
      </header>

      {/* 消息区域 */}
      <div className="chat-messages">
        {messages.length === 0 && !loading && (
          <div className="welcome">
            <div className="welcome-icon">🤖</div>
            <h2>你好！我是 AI 小助手</h2>
            <p>我可以根据历史简报回答你的问题，比如：</p>
            <div className="suggestions">
              {SUGGESTIONS.map((s, i) => (
                <button
                  key={i}
                  className="suggestion-btn"
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
                      className="source-item"
                      onClick={() => navigate(`/report/${source.id}`)}
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
            disabled={loading}
          />
          <button
            className="send-btn"
            onClick={() => handleSubmit()}
            disabled={!input.trim() || loading}
          >
            {loading ? (
              <span className="loading-spinner"></span>
            ) : (
              '发送'
            )}
          </button>
        </div>
        <div className="input-hint">按 Enter 发送，Shift + Enter 换行</div>
      </div>
    </div>
  )
}
