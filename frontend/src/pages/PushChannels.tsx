import { useEffect, useState } from 'react'
import api from '../utils/api'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoChannels } from '../demo/fixtures'
import './PushChannels.css'

type ChannelType = 'email' | 'wechat' | 'dingtalk' | 'feishu'

interface Channel {
  id?: number
  channelType: ChannelType
  displayName?: string
  target: string
  secret?: string
  enabled: boolean
}

const TYPE_META: Record<ChannelType, { icon: string; label: string; targetLabel: string; targetPlaceholder: string; supportsSecret: boolean; secretHint?: string }> = {
  email:    { icon: '✉️', label: '邮箱',   targetLabel: '邮箱地址',   targetPlaceholder: 'you@example.com',                                supportsSecret: false },
  wechat:   { icon: '💬', label: '企业微信', targetLabel: 'Webhook URL', targetPlaceholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...', supportsSecret: false },
  dingtalk: { icon: '🔔', label: '钉钉',    targetLabel: 'Webhook URL', targetPlaceholder: 'https://oapi.dingtalk.com/robot/send?access_token=...',    supportsSecret: true, secretHint: '钉钉后台开启加签时的签名密钥' },
  feishu:   { icon: '🚀', label: '飞书',    targetLabel: 'Webhook URL', targetPlaceholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...',       supportsSecret: true, secretHint: '飞书后台开启签名校验时的密钥' },
}

const empty: Channel = { channelType: 'email', displayName: '', target: '', secret: '', enabled: true }

export default function PushChannels() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [channels, setChannels] = useState<Channel[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<Channel | null>(null)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState('')

  const load = () => {
    if (isDemo) {
      setChannels(demoChannels)
      setLoading(false)
      return
    }
    setLoading(true)
    api.get('/channels')
      .then(res => setChannels(res.data?.data || []))
      .catch(() => setChannels([]))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [isDemo])

  const startNew = () => { if (!isDemo) setEditing({ ...empty }) }
  const startEdit = (c: Channel) => { if (!isDemo) setEditing({ ...c }) }
  const cancel = () => setEditing(null)

  const save = async () => {
    if (isDemo || !editing) return
    if (!editing.target?.trim()) { setMsg('❌ 必须填写目标（邮箱或 webhook URL）'); return }
    setSaving(true)
    setMsg('')
    try {
      const payload = { ...editing, target: editing.target.trim() }
      if (editing.id) {
        await api.put(`/channels/${editing.id}`, payload)
      } else {
        await api.post('/channels', payload)
      }
      setEditing(null)
      load()
      setMsg('✅ 已保存')
    } catch (e: any) {
      setMsg('❌ 保存失败：' + (e?.response?.data?.message || e?.message || ''))
    } finally {
      setSaving(false)
    }
  }

  const remove = async (id?: number) => {
    if (isDemo || !id) return
    if (!confirm('确定删除该渠道？')) return
    try {
      await api.delete(`/channels/${id}`)
      load()
    } catch (e: any) {
      alert('删除失败：' + (e?.response?.data?.message || ''))
    }
  }

  const test = async (id?: number) => {
    if (isDemo || !id) return
    try {
      const res = await api.post(`/channels/${id}/test`)
      setMsg(res.data?.message || '✅ 测试推送已发出')
    } catch (e: any) {
      setMsg('❌ 测试失败：' + (e?.response?.data?.message || e?.message || ''))
    }
  }

  const toggle = async (c: Channel) => {
    if (isDemo || !c.id) return
    try {
      await api.put(`/channels/${c.id}`, { ...c, enabled: !c.enabled })
      load()
    } catch (e: any) {
      alert('切换失败：' + (e?.response?.data?.message || ''))
    }
  }

  return (
    <div className="channels-page">
      {isDemo && <DemoNotice />}
      <div className="page-header">
        <h2>🔔 推送渠道</h2>
        <p className="page-desc">添加你的邮箱或群机器人 Webhook，简报会按订阅时间自动推送过来</p>
      </div>

      <div className="channels-toolbar">
        <button className="btn-primary" onClick={startNew} disabled={isDemo || !!editing}>+ 添加渠道</button>
        {msg && <span className="channels-msg">{msg}</span>}
      </div>

      {editing && (
        <div className="channel-form">
          <div className="form-row">
            <label>
              <span>类型</span>
              <select
                value={editing.channelType}
                onChange={e => setEditing({ ...editing, channelType: e.target.value as ChannelType })}
              >
                {(Object.keys(TYPE_META) as ChannelType[]).map(k => (
                  <option key={k} value={k}>{TYPE_META[k].icon} {TYPE_META[k].label}</option>
                ))}
              </select>
            </label>
            <label>
              <span>昵称（可选）</span>
              <input
                value={editing.displayName || ''}
                onChange={e => setEditing({ ...editing, displayName: e.target.value })}
                placeholder="如：我的常用邮箱"
              />
            </label>
          </div>
          <label className="form-block">
            <span>{TYPE_META[editing.channelType].targetLabel}</span>
            <input
              value={editing.target}
              onChange={e => setEditing({ ...editing, target: e.target.value })}
              placeholder={TYPE_META[editing.channelType].targetPlaceholder}
            />
          </label>
          {TYPE_META[editing.channelType].supportsSecret && (
            <label className="form-block">
              <span>签名密钥（可选）</span>
              <input
                value={editing.secret || ''}
                onChange={e => setEditing({ ...editing, secret: e.target.value })}
                placeholder={TYPE_META[editing.channelType].secretHint}
              />
            </label>
          )}
          <div className="form-actions">
            <button className="btn-primary" onClick={save} disabled={saving}>
              {saving ? '保存中...' : (editing.id ? '保存修改' : '添加')}
            </button>
            <button className="btn-ghost" onClick={cancel}>取消</button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="loading">加载中...</div>
      ) : channels.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">📭</div>
          <p>你还没有配置任何推送渠道</p>
          <p className="hint">添加至少一个渠道，才能自动收到简报推送</p>
        </div>
      ) : (
        <div className="channel-list">
          {channels.map(c => {
            const meta = TYPE_META[c.channelType]
            return (
              <div key={c.id} className={`channel-card ${c.enabled ? '' : 'disabled'}`}>
                <div className="channel-icon">{meta.icon}</div>
                <div className="channel-info">
                  <div className="channel-title">
                    {c.displayName || meta.label}
                    <span className="channel-type-badge">{meta.label}</span>
                  </div>
                  <div className="channel-target" title={c.target}>{c.target}</div>
                </div>
                <div className="channel-actions">
                  <button className="btn-icon" disabled={isDemo} onClick={() => toggle(c)} title={c.enabled ? '暂停' : '启用'}>
                    {c.enabled ? '⏸' : '▶'}
                  </button>
                  <button className="btn-icon" disabled={isDemo} onClick={() => test(c.id)} title="测试推送">📤</button>
                  <button className="btn-icon" disabled={isDemo} onClick={() => startEdit(c)} title="编辑">✏️</button>
                  <button className="btn-icon danger" disabled={isDemo} onClick={() => remove(c.id)} title="删除">🗑</button>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
