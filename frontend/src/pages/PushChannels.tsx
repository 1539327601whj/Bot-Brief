import { useEffect, useState } from 'react'
import api from '../utils/api'
import { useAuth } from '../context/AuthContext'
import DemoNotice from '../components/DemoNotice'
import { demoChannels } from '../demo/fixtures'
import './PushChannels.css'

type ChannelType = 'email' | 'wechat' | 'dingtalk' | 'feishu'
type FeedbackType = 'success' | 'error' | 'info'

interface Channel {
  id: number
  channelType: ChannelType
  displayName?: string
  targetPreview: string
  secretConfigured: boolean
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

interface ChannelForm {
  id?: number
  originalChannelType?: ChannelType
  channelType: ChannelType
  displayName: string
  target: string
  targetPreview?: string
  secret: string
  secretConfigured: boolean
  clearSecret: boolean
  enabled: boolean
}

interface ResultEnvelope<T = unknown> {
  code: number
  message?: string
  data?: T
}

interface Feedback {
  type: FeedbackType
  text: string
}

interface CreateChannelBody {
  channelType: ChannelType
  displayName?: string
  target: string
  secret?: string
  enabled: boolean
}

interface UpdateChannelBody {
  channelType?: ChannelType
  displayName?: string
  enabled?: boolean
  target?: string
  secret?: string
  clearSecret?: boolean
}

const TYPE_META: Record<ChannelType, { icon: string; label: string; targetLabel: string; targetPlaceholder: string; supportsSecret: boolean; secretHint?: string }> = {
  email:    { icon: '✉️', label: '邮箱',   targetLabel: '邮箱地址',   targetPlaceholder: 'you@example.com',                                supportsSecret: false },
  wechat:   { icon: '💬', label: '企业微信', targetLabel: 'Webhook URL', targetPlaceholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...', supportsSecret: false },
  dingtalk: { icon: '🔔', label: '钉钉',    targetLabel: 'Webhook URL', targetPlaceholder: 'https://oapi.dingtalk.com/robot/send?access_token=...',    supportsSecret: true, secretHint: '钉钉后台开启加签时的签名密钥' },
  feishu:   { icon: '🚀', label: '飞书',    targetLabel: 'Webhook URL', targetPlaceholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...',       supportsSecret: true, secretHint: '飞书后台开启签名校验时的密钥' },
}

const emptyForm: ChannelForm = {
  channelType: 'email',
  displayName: '',
  target: '',
  secret: '',
  secretConfigured: false,
  clearSecret: false,
  enabled: true,
}

function requireBusinessSuccess<T>(result: ResultEnvelope<T> | undefined, fallbackMessage: string): T | undefined {
  if (result?.code !== 200) {
    throw new Error(result?.message || fallbackMessage)
  }
  return result.data
}

function getErrorMessage(error: any, fallbackMessage: string) {
  return error?.response?.data?.message || error?.message || fallbackMessage
}

export default function PushChannels() {
  const { user } = useAuth()
  const isDemo = user?.accountType === 'DEMO'
  const [channels, setChannels] = useState<Channel[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<ChannelForm | null>(null)
  const [saving, setSaving] = useState(false)
  const [testingIds, setTestingIds] = useState<Set<number>>(() => new Set())
  const [feedback, setFeedback] = useState<Feedback | null>(null)

  const load = async (): Promise<boolean> => {
    if (isDemo) {
      setChannels(demoChannels)
      setLoading(false)
      return true
    }

    setLoading(true)
    try {
      const res = await api.get<ResultEnvelope<Channel[]>>('/channels')
      const data = requireBusinessSuccess(res.data, '加载渠道失败')
      setChannels(data || [])
      return true
    } catch (error: any) {
      setChannels([])
      setFeedback({ type: 'error', text: `加载渠道失败：${getErrorMessage(error, '请稍后重试')}` })
      return false
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [isDemo])

  const startNew = () => {
    if (isDemo) return
    setFeedback(null)
    setEditing({ ...emptyForm })
  }

  const startEdit = (channel: Channel) => {
    if (isDemo) return
    setFeedback(null)
    setEditing({
      id: channel.id,
      originalChannelType: channel.channelType,
      channelType: channel.channelType,
      displayName: channel.displayName || '',
      target: '',
      targetPreview: channel.targetPreview,
      secret: '',
      secretConfigured: channel.secretConfigured,
      clearSecret: false,
      enabled: channel.enabled,
    })
  }

  const cancel = () => setEditing(null)

  const save = async () => {
    if (isDemo || !editing) return

    const target = editing.target.trim()
    const secret = editing.secret.trim()
    const isCreate = editing.id == null
    const typeChanged = !isCreate && editing.channelType !== editing.originalChannelType

    if ((isCreate || typeChanged) && !target) {
      setFeedback({
        type: 'error',
        text: typeChanged ? '更改渠道类型后必须填写新的目标地址' : '必须填写目标（邮箱或 Webhook URL）',
      })
      return
    }

    setSaving(true)
    setFeedback(null)
    try {
      let res
      if (isCreate) {
        const payload: CreateChannelBody = {
          channelType: editing.channelType,
          target,
          enabled: editing.enabled,
        }
        const displayName = editing.displayName.trim()
        if (displayName) payload.displayName = displayName
        if (TYPE_META[editing.channelType].supportsSecret && secret) payload.secret = secret
        res = await api.post<ResultEnvelope<Channel>>('/channels', payload)
      } else {
        const payload: UpdateChannelBody = {
          displayName: editing.displayName.trim(),
          enabled: editing.enabled,
        }
        if (typeChanged) payload.channelType = editing.channelType
        if (target) payload.target = target
        if (TYPE_META[editing.channelType].supportsSecret) {
          if (editing.clearSecret) payload.clearSecret = true
          else if (secret) payload.secret = secret
        }
        res = await api.put<ResultEnvelope<Channel>>(`/channels/${editing.id}`, payload)
      }

      requireBusinessSuccess(res.data, '保存渠道失败')
      setEditing(null)
      const refreshed = await load()
      setFeedback(refreshed
        ? { type: 'success', text: isCreate ? '渠道添加成功' : '渠道修改成功' }
        : { type: 'error', text: '渠道已保存，但列表刷新失败，请稍后重试' })
    } catch (error: any) {
      setFeedback({ type: 'error', text: `保存失败：${getErrorMessage(error, '请检查配置后重试')}` })
    } finally {
      setSaving(false)
    }
  }

  const remove = async (id?: number) => {
    if (isDemo || id == null) return
    if (!confirm('确定删除该渠道？删除后将无法恢复。')) return

    setFeedback(null)
    try {
      const res = await api.delete<ResultEnvelope>(`/channels/${id}`)
      requireBusinessSuccess(res.data, '删除渠道失败')
      const refreshed = await load()
      setFeedback(refreshed
        ? { type: 'success', text: '渠道已删除' }
        : { type: 'error', text: '渠道已删除，但列表刷新失败，请稍后重试' })
    } catch (error: any) {
      setFeedback({ type: 'error', text: `删除失败：${getErrorMessage(error, '请稍后重试')}` })
    }
  }

  const test = async (id?: number) => {
    if (isDemo || id == null || testingIds.has(id)) return

    setTestingIds(current => {
      const next = new Set(current)
      next.add(id)
      return next
    })
    setFeedback({ type: 'info', text: '正在发送测试推送...' })
    try {
      const res = await api.post<ResultEnvelope>(`/channels/${id}/test`)
      requireBusinessSuccess(res.data, '测试推送失败')
      setFeedback({ type: 'success', text: res.data.message || '测试推送发送成功' })
    } catch (error: any) {
      setFeedback({ type: 'error', text: `测试失败：${getErrorMessage(error, '请检查渠道配置后重试')}` })
    } finally {
      setTestingIds(current => {
        const next = new Set(current)
        next.delete(id)
        return next
      })
    }
  }

  const toggle = async (channel: Channel) => {
    if (isDemo) return

    const nextEnabled = !channel.enabled
    setFeedback(null)
    try {
      const res = await api.put<ResultEnvelope<Channel>>(`/channels/${channel.id}`, { enabled: nextEnabled })
      requireBusinessSuccess(res.data, nextEnabled ? '启用渠道失败' : '暂停渠道失败')
      const refreshed = await load()
      setFeedback(refreshed
        ? { type: 'success', text: nextEnabled ? '渠道已启用' : '渠道已暂停' }
        : { type: 'error', text: '渠道状态已更新，但列表刷新失败，请稍后重试' })
    } catch (error: any) {
      setFeedback({
        type: 'error',
        text: `${nextEnabled ? '启用' : '暂停'}失败：${getErrorMessage(error, '请稍后重试')}`,
      })
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
        {feedback && <span className={`channels-msg ${feedback.type}`}>{feedback.text}</span>}
      </div>

      {editing && (
        <div className="channel-form">
          <div className="form-row">
            <label>
              <span>类型</span>
              <select
                value={editing.channelType}
                onChange={event => setEditing({
                  ...editing,
                  channelType: event.target.value as ChannelType,
                  target: '',
                  secret: '',
                  clearSecret: false,
                })}
              >
                {(Object.keys(TYPE_META) as ChannelType[]).map(key => (
                  <option key={key} value={key}>{TYPE_META[key].icon} {TYPE_META[key].label}</option>
                ))}
              </select>
            </label>
            <label>
              <span>昵称（可选）</span>
              <input
                value={editing.displayName}
                onChange={event => setEditing({ ...editing, displayName: event.target.value })}
                placeholder="如：我的常用邮箱"
              />
            </label>
          </div>

          {editing.id != null && (
            <div className="stored-config">
              <div>
                <span>当前目标预览</span>
                <strong title={editing.targetPreview}>{editing.targetPreview || '未提供'}</strong>
              </div>
              <div>
                <span>签名密钥</span>
                <strong className={editing.secretConfigured ? 'configured' : ''}>
                  {editing.secretConfigured ? '已配置' : '未配置'}
                </strong>
              </div>
              <p>目标和签名密钥留空将保留已保存的配置；如果更改渠道类型，必须填写新的目标地址。</p>
            </div>
          )}

          <label className="form-block">
            <span>
              {TYPE_META[editing.channelType].targetLabel}
              {(editing.id == null || editing.channelType !== editing.originalChannelType) ? '（必填）' : '（留空则保留）'}
            </span>
            <input
              value={editing.target}
              onChange={event => setEditing({ ...editing, target: event.target.value })}
              placeholder={TYPE_META[editing.channelType].targetPlaceholder}
            />
          </label>

          {TYPE_META[editing.channelType].supportsSecret && (
            <>
              <label className="form-block">
                <span>签名密钥（可选{editing.id != null ? '，留空则保留' : ''}）</span>
                <input
                  type="password"
                  value={editing.secret}
                  disabled={editing.clearSecret}
                  onChange={event => setEditing({ ...editing, secret: event.target.value })}
                  placeholder={TYPE_META[editing.channelType].secretHint}
                  autoComplete="new-password"
                />
              </label>
              {editing.id != null && editing.secretConfigured && (
                <label className="clear-secret-option">
                  <input
                    type="checkbox"
                    checked={editing.clearSecret}
                    onChange={event => setEditing({
                      ...editing,
                      clearSecret: event.target.checked,
                      secret: event.target.checked ? '' : editing.secret,
                    })}
                  />
                  <span>清除已保存的签名密钥</span>
                </label>
              )}
            </>
          )}

          <div className="form-actions">
            <button className="btn-primary" onClick={save} disabled={saving}>
              {saving ? '保存中...' : (editing.id != null ? '保存修改' : '添加')}
            </button>
            <button className="btn-ghost" onClick={cancel} disabled={saving}>取消</button>
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
          {channels.map(channel => {
            const meta = TYPE_META[channel.channelType]
            const testing = testingIds.has(channel.id)
            return (
              <div key={channel.id} className={`channel-card ${channel.enabled ? '' : 'disabled'}`}>
                <div className="channel-icon">{meta.icon}</div>
                <div className="channel-info">
                  <div className="channel-title">
                    {channel.displayName || meta.label}
                    <span className="channel-type-badge">{meta.label}</span>
                  </div>
                  <div className="channel-target" title={channel.targetPreview}>{channel.targetPreview || '未提供目标预览'}</div>
                  <div className={`channel-secret-status ${channel.secretConfigured ? 'configured' : ''}`}>
                    签名密钥：{channel.secretConfigured ? '已配置' : '未配置'}
                  </div>
                </div>
                <div className="channel-actions">
                  <button className="btn-icon" disabled={isDemo} onClick={() => toggle(channel)} title={channel.enabled ? '暂停' : '启用'}>
                    {channel.enabled ? '⏸' : '▶'}
                  </button>
                  <button className="btn-icon" disabled={isDemo || testing} onClick={() => test(channel.id)} title="测试推送">
                    {testing ? '发送中' : '📤'}
                  </button>
                  <button className="btn-icon" disabled={isDemo} onClick={() => startEdit(channel)} title="编辑">✏️</button>
                  <button className="btn-icon danger" disabled={isDemo} onClick={() => remove(channel.id)} title="删除">🗑</button>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
