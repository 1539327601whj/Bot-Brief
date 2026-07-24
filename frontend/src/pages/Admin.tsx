import { useEffect, useState } from 'react'
import api from '../utils/api'
import dayjs from '../utils/dayjs'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoInviteCodes } from '../demo/fixtures'
import './Admin.css'

interface InviteCode {
  id: number
  code: string
  createdBy?: number
  usedBy: number | null
  usedAt: string | null
  expiresAt: string | null
  createdAt: string
}

export default function Admin() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [codes, setCodes] = useState<InviteCode[]>([])
  const [loading, setLoading] = useState(true)
  const [count, setCount] = useState(3)
  const [generating, setGenerating] = useState(false)
  const [msg, setMsg] = useState('')

  const load = () => {
    if (isDemo) {
      setCodes(demoInviteCodes)
      setLoading(false)
      return
    }
    setLoading(true)
    api.get('/admin/invite-codes')
      .then(res => setCodes(res.data?.data || []))
      .catch(err => {
        if (err?.response?.status !== 401) setMsg('❌ 加载失败：' + (err?.response?.data?.message || ''))
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [isDemo])

  const generate = async () => {
    if (isDemo) return
    setGenerating(true)
    setMsg('')
    try {
      const res = await api.post(`/admin/invite-codes?count=${count}`)
      const newCodes: InviteCode[] = res.data?.data || []
      setMsg(`✅ 已生成 ${newCodes.length} 个邀请码：${newCodes.map(c => c.code).join(' / ')}`)
      load()
    } catch (e: any) {
      setMsg('❌ 生成失败：' + (e?.response?.data?.message || e?.message || ''))
    } finally {
      setGenerating(false)
    }
  }

  const copy = async (code: string) => {
    if (isDemo) return
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(code)
      } else {
        const textarea = document.createElement('textarea')
        textarea.value = code
        textarea.style.position = 'fixed'
        textarea.style.left = '-9999px'
        document.body.appendChild(textarea)
        textarea.focus()
        textarea.select()
        document.execCommand('copy')
        document.body.removeChild(textarea)
      }
      setMsg(`📋 已复制邀请码 ${code}`)
    } catch {
      setMsg(`❌ 自动复制失败，请手动复制：${code}`)
    }
  }

  return (
    <div className="admin-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>🛠 管理员面板 · 邀请码</h2>
        <p className="page-desc">{isDemo ? '以下为脱敏假邀请码，仅展示管理员界面；公开 Demo 不可注册或分发邀请码。' : '生成邀请码分发给朋友注册。已使用的邀请码不可复用。'}</p>
      </div>

      <div className="admin-toolbar">
        <label>
          <span>生成数量</span>
          <input
            type="number"
            min={1}
            max={100}
            value={count}
            disabled={isDemo}
            onChange={e => setCount(Math.max(1, Math.min(100, Number(e.target.value) || 1)))}
          />
        </label>
        <button className="btn-primary" onClick={generate} disabled={isDemo || generating}>
          {generating ? '生成中...' : '+ 生成邀请码'}
        </button>
        {msg && <span className="admin-msg">{msg}</span>}
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : codes.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🎟️</div>
          <p>还没有生成过邀请码</p>
        </div>
      ) : (
        <div className="code-list">
          <div className="code-head">
            <div>邀请码</div>
            <div>状态</div>
            <div>创建时间</div>
            <div>使用时间</div>
            <div></div>
          </div>
          {codes.map(c => (
            <div key={c.id} className={`code-row ${c.usedAt ? 'used' : ''}`}>
              <div className="code-value">{c.code}</div>
              <div>
                {c.usedAt
                  ? <span className="badge used">{isDemo ? '已使用' : `已使用（user #${c.usedBy}）`}</span>
                  : <span className="badge unused">未使用</span>}
              </div>
              <div className="mono">{dayjs(c.createdAt).format('YYYY-MM-DD HH:mm')}</div>
              <div className="mono">{c.usedAt ? dayjs(c.usedAt).format('YYYY-MM-DD HH:mm') : '—'}</div>
              <div>
                {!c.usedAt && (
                  <button className="btn-ghost" disabled={isDemo} onClick={() => copy(c.code)}>复制</button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
