import { useEffect, useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { Alert, Button, Card, ConfigProvider, DatePicker, Empty, Form, Input, InputNumber, Modal, Select, Space, Spin, Table, Tag, Upload, message, theme } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import dayjs from '../utils/dayjs'
import { parseCsv } from '../utils/csv'
import {
  analyzeHotWork,
  createAccount,
  createCompetitor,
  createWork,
  deleteAccount,
  deleteCompetitor,
  deleteWork,
  getAccounts,
  getCompetitors,
  getOverview,
  getWorks,
  importWorks,
  platformLabel,
  platformOptions,
  recommendTopics,
  rewriteAdvice,
  updateAccount,
  updateWork,
  type AccountPayload,
  type AiTextResponse,
  type CompetitorAccount,
  type ContentAccount,
  type ContentOverview,
  type ContentWork,
  type PageData,
  type WorkImportResult,
  type WorkImportRow,
  type WorkPayload,
} from '../api/contentGrowth'
import './ContentGrowth.css'

const goalOptions = [
  { label: '涨粉', value: '涨粉' },
  { label: '提高播放', value: '提高播放' },
  { label: '提高收藏', value: '提高收藏' },
  { label: '带货转化', value: '带货转化' },
]

const contentGrowthTheme = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#8b9cff', colorPrimaryHover: '#a8b2ff', colorBgBase: '#05070d',
    colorBgContainer: '#0d111b', colorBgElevated: '#111620', colorBorder: 'rgba(255, 255, 255, 0.14)',
    colorBorderSecondary: 'rgba(255, 255, 255, 0.08)', colorText: '#f4f7fb', colorTextSecondary: '#9aa4b5',
    colorTextPlaceholder: '#667085', colorSuccess: '#3dd68c', colorWarning: '#f5c451', colorError: '#ff6b6b',
    borderRadius: 12, borderRadiusLG: 18, boxShadowSecondary: '0 24px 80px rgba(0, 0, 0, 0.42)',
  },
}

const csvHeaders = ['platform', 'title', 'workUrl', 'coverUrl', 'publishTime', 'playCount', 'likeCount', 'commentCount', 'collectCount', 'shareCount', 'followerGain', 'contentType']
const numericFields = ['playcount', 'likecount', 'commentcount', 'collectcount', 'sharecount', 'followergain'] as const

interface CsvPreviewRow {
  key: number
  row: WorkImportRow
  errors: string[]
}

const formatNumber = (value?: number) => {
  const n = value || 0
  if (n >= 10000) return `${(n / 10000).toFixed(n >= 100000 ? 0 : 1)}万`
  return n.toLocaleString('zh-CN')
}
const formatRate = (value?: number) => `${((value || 0) * 100).toFixed(2)}%`

export default function ContentGrowth() {
  const [accounts, setAccounts] = useState<ContentAccount[]>([])
  const [works, setWorks] = useState<PageData<ContentWork>>({ records: [], total: 0, current: 1, size: 10 })
  const [competitors, setCompetitors] = useState<CompetitorAccount[]>([])
  const [overview, setOverview] = useState<ContentOverview | null>(null)
  const [selectedAccountId, setSelectedAccountId] = useState<number | undefined>()
  const [loading, setLoading] = useState(true)
  const [workLoading, setWorkLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [accountModalOpen, setAccountModalOpen] = useState(false)
  const [workModalOpen, setWorkModalOpen] = useState(false)
  const [competitorModalOpen, setCompetitorModalOpen] = useState(false)
  const [importModalOpen, setImportModalOpen] = useState(false)
  const [editingAccount, setEditingAccount] = useState<ContentAccount | null>(null)
  const [editingWork, setEditingWork] = useState<ContentWork | null>(null)
  const [importAccountId, setImportAccountId] = useState<number | undefined>()
  const [conflictStrategy, setConflictStrategy] = useState<'UPDATE' | 'SKIP'>('UPDATE')
  const [csvFileName, setCsvFileName] = useState('')
  const [csvPreview, setCsvPreview] = useState<CsvPreviewRow[]>([])
  const [csvFileError, setCsvFileError] = useState('')
  const [importResult, setImportResult] = useState<WorkImportResult | null>(null)
  const [importing, setImporting] = useState(false)
  const [aiLoading, setAiLoading] = useState('')
  const [hotAnalysis, setHotAnalysis] = useState<AiTextResponse | null>(null)
  const [topicResult, setTopicResult] = useState<AiTextResponse | null>(null)
  const [rewriteResult, setRewriteResult] = useState<AiTextResponse | null>(null)
  const [accountForm] = Form.useForm()
  const [workForm] = Form.useForm()
  const [competitorForm] = Form.useForm()
  const [topicForm] = Form.useForm()
  const [rewriteForm] = Form.useForm()

  const accountSelectOptions = useMemo(() => accounts.map(account => ({
    label: `${platformLabel(account.platform)} · ${account.accountName}`, value: account.id,
  })), [accounts])
  const selectedAccount = accounts.find(account => account.id === selectedAccountId)
  const importAccount = accounts.find(account => account.id === importAccountId)
  const previewErrorCount = csvPreview.filter(item => item.errors.length
    || (item.row.platform && importAccount && item.row.platform !== importAccount.platform)).length

  const loadAccounts = async () => {
    const res = await getAccounts()
    if (res.data?.code === 200) {
      const data = res.data.data || []
      setAccounts(data)
      return data as ContentAccount[]
    }
    return []
  }
  const loadOverview = async (accountId = selectedAccountId) => {
    const res = await getOverview(accountId)
    if (res.data?.code === 200) setOverview(res.data.data)
  }
  const loadWorks = async (page = works.current || 1, size = works.size || 10, accountId = selectedAccountId) => {
    setWorkLoading(true)
    try {
      const res = await getWorks({ accountId, page, size })
      if (res.data?.code === 200) setWorks(res.data.data)
    } finally { setWorkLoading(false) }
  }
  const loadCompetitors = async () => {
    const res = await getCompetitors()
    if (res.data?.code === 200) setCompetitors(res.data.data || [])
  }

  useEffect(() => {
    Promise.all([loadAccounts(), loadOverview(undefined), loadWorks(1, 10, undefined), loadCompetitors()])
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!loading) {
      loadOverview(selectedAccountId)
      loadWorks(1, works.size || 10, selectedAccountId)
      setHotAnalysis(null)
    }
  }, [selectedAccountId])

  const openCreateAccount = () => {
    setEditingAccount(null)
    accountForm.setFieldsValue({ platform: 'douyin', followerCount: 0, accountName: '', homepageUrl: '', accountPositioning: '' })
    setAccountModalOpen(true)
  }
  const openEditAccount = () => {
    if (!selectedAccount) return
    setEditingAccount(selectedAccount)
    accountForm.setFieldsValue({
      platform: selectedAccount.platform, accountName: selectedAccount.accountName,
      homepageUrl: selectedAccount.homepageUrl, followerCount: selectedAccount.followerCount || 0,
      accountPositioning: selectedAccount.accountPositioning,
    })
    setAccountModalOpen(true)
  }
  const closeAccountModal = () => {
    setAccountModalOpen(false)
    setEditingAccount(null)
    accountForm.resetFields()
  }
  const handleSaveAccount = async () => {
    const values = await accountForm.validateFields()
    const payload: AccountPayload = {
      platform: editingAccount?.platform || values.platform,
      accountName: values.accountName.trim(), homepageUrl: values.homepageUrl?.trim() || undefined,
      avatarUrl: editingAccount?.avatarUrl, followerCount: values.followerCount || 0,
      accountPositioning: values.accountPositioning?.trim() || undefined,
    }
    setSaving(true)
    try {
      const res = editingAccount ? await updateAccount(editingAccount.id, payload) : await createAccount(payload)
      if (res.data?.code !== 200) return message.error(res.data?.message || '保存失败')
      message.success(editingAccount ? '账号已更新' : '账号已添加')
      closeAccountModal()
      await Promise.all([loadAccounts(), loadOverview(selectedAccountId)])
    } finally { setSaving(false) }
  }
  const handleDeleteAccount = () => {
    if (!selectedAccount) return
    Modal.confirm({
      title: `删除账号“${selectedAccount.accountName}”？`,
      content: '该账号下的所有作品数据将一并删除，此操作不可恢复。',
      okText: '确认删除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        const res = await deleteAccount(selectedAccount.id)
        if (res.data?.code !== 200) return message.error(res.data?.message || '删除失败')
        message.success('账号及其作品已删除')
        setSelectedAccountId(undefined)
        setHotAnalysis(null)
        await Promise.all([loadAccounts(), loadOverview(undefined), loadWorks(1, works.size || 10, undefined)])
      },
    })
  }

  const openCreateWork = () => {
    const account = selectedAccount || accounts[0]
    setEditingWork(null)
    workForm.setFieldsValue({
      accountId: account?.id, platform: account?.platform, title: '', workUrl: '', coverUrl: '', publishTime: null,
      contentType: 'video', playCount: 0, likeCount: 0, commentCount: 0, collectCount: 0, shareCount: 0, followerGain: 0,
    })
    setWorkModalOpen(true)
  }
  const openEditWork = (work: ContentWork) => {
    setEditingWork(work)
    workForm.setFieldsValue({ ...work, publishTime: work.publishTime ? dayjs(work.publishTime) : null, contentType: work.contentType || 'video' })
    setWorkModalOpen(true)
  }
  const closeWorkModal = () => {
    setWorkModalOpen(false)
    setEditingWork(null)
    workForm.resetFields()
  }
  const handleWorkAccountChange = (accountId: number) => {
    workForm.setFieldValue('platform', accounts.find(item => item.id === accountId)?.platform)
  }
  const handleSaveWork = async () => {
    const values = await workForm.validateFields()
    const account = accounts.find(item => item.id === values.accountId)
    if (!account) return message.error('请选择有效账号')
    const payload: WorkPayload = {
      accountId: account.id, platform: account.platform, title: values.title.trim(),
      workUrl: values.workUrl?.trim() || undefined, coverUrl: values.coverUrl?.trim() || undefined,
      publishTime: values.publishTime ? values.publishTime.format('YYYY-MM-DD HH:mm:ss') : undefined,
      playCount: values.playCount || 0, likeCount: values.likeCount || 0, commentCount: values.commentCount || 0,
      collectCount: values.collectCount || 0, shareCount: values.shareCount || 0, followerGain: values.followerGain || 0,
      contentType: values.contentType || 'video',
    }
    setSaving(true)
    try {
      const res = editingWork ? await updateWork(editingWork.id, payload) : await createWork(payload)
      if (res.data?.code !== 200) return message.error(res.data?.message || '保存失败')
      message.success(editingWork ? '作品已更新' : '作品已添加')
      closeWorkModal()
      setHotAnalysis(null)
      await Promise.all([loadWorks(editingWork ? works.current : 1, works.size, selectedAccountId), loadOverview(selectedAccountId)])
    } finally { setSaving(false) }
  }
  const handleDeleteWork = (work: ContentWork) => {
    Modal.confirm({
      title: `删除作品“${work.title}”？`, content: '删除后无法恢复。',
      okText: '确认删除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        const res = await deleteWork(work.id)
        if (res.data?.code !== 200) return message.error(res.data?.message || '删除失败')
        const nextPage = works.records.length === 1 && works.current > 1 ? works.current - 1 : works.current
        message.success('作品已删除')
        setHotAnalysis(null)
        await Promise.all([loadWorks(nextPage, works.size, selectedAccountId), loadOverview(selectedAccountId)])
      },
    })
  }

  const handleCreateCompetitor = async () => {
    const values = await competitorForm.validateFields()
    const res = await createCompetitor(values)
    if (res.data?.code === 200) {
      message.success('竞品账号已添加')
      setCompetitorModalOpen(false)
      competitorForm.resetFields()
      await loadCompetitors()
    } else message.error(res.data?.message || '添加失败')
  }
  const runHotAnalysis = async () => {
    setAiLoading('hot')
    try {
      const res = await analyzeHotWork({ accountId: selectedAccountId, workId: overview?.bestWork?.id })
      if (res.data?.code === 200) setHotAnalysis(res.data.data)
      else message.error(res.data?.message || '分析失败')
    } finally { setAiLoading('') }
  }
  const runTopicRecommendation = async () => {
    const values = await topicForm.validateFields()
    setAiLoading('topic')
    try {
      const res = await recommendTopics({ accountId: selectedAccountId, goal: values.goal, count: 5 })
      if (res.data?.code === 200) setTopicResult(res.data.data)
      else message.error(res.data?.message || '生成失败')
    } finally { setAiLoading('') }
  }
  const runRewriteAdvice = async () => {
    const values = await rewriteForm.validateFields()
    setAiLoading('rewrite')
    try {
      const res = await rewriteAdvice({ accountId: selectedAccountId, ...values })
      if (res.data?.code === 200) setRewriteResult(res.data.data)
      else message.error(res.data?.message || '生成失败')
    } finally { setAiLoading('') }
  }

  const openImportModal = () => {
    setImportAccountId(selectedAccountId || accounts[0]?.id)
    setConflictStrategy('UPDATE')
    setCsvFileName('')
    setCsvPreview([])
    setCsvFileError('')
    setImportResult(null)
    setImportModalOpen(true)
  }
  const closeImportModal = () => {
    setImportModalOpen(false)
    setCsvFileName('')
    setCsvPreview([])
    setCsvFileError('')
    setImportResult(null)
  }
  const handleCsvFile = async (file: File) => {
    setImportResult(null)
    setCsvPreview([])
    setCsvFileError('')
    if (!file.name.toLowerCase().endsWith('.csv')) {
      setCsvFileError('只支持 .csv 文件')
      return false
    }
    if (file.size > 2 * 1024 * 1024) {
      setCsvFileError('CSV 文件不能超过 2 MB')
      return false
    }
    try {
      const parsed = parseCsv(await file.text())
      if (parsed.records.length > 500) throw new Error('单次最多导入 500 条作品')
      setCsvFileName(file.name)
      setCsvPreview(parsed.records.map(({ rowNumber, values }) => buildPreviewRow(rowNumber, values)))
    } catch (error) {
      setCsvFileError(error instanceof Error ? error.message : 'CSV 解析失败')
    }
    return false
  }
  const handleImport = async () => {
    if (!importAccountId || !csvPreview.length || previewErrorCount) return
    setImporting(true)
    try {
      const res = await importWorks({ accountId: importAccountId, conflictStrategy, rows: csvPreview.map(item => item.row) })
      if (res.data?.code !== 200) return message.error(res.data?.message || '导入失败')
      setImportResult(res.data.data)
      if (res.data.data.created || res.data.data.updated) {
        await Promise.all([loadWorks(1, works.size, selectedAccountId), loadOverview(selectedAccountId)])
      }
    } finally { setImporting(false) }
  }
  const downloadTemplate = () => {
    const example = ['douyin', '示例作品标题', 'https://example.com/work/1', '', '2026-07-20 10:30:00', '10000', '800', '50', '120', '30', '60', 'video']
    const blob = new Blob([`﻿${csvHeaders.join(',')}\r\n${example.join(',')}\r\n`], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = '内容作品导入模板.csv'
    anchor.click()
    URL.revokeObjectURL(url)
  }

  const columns: ColumnsType<ContentWork> = [
    { title: '作品', dataIndex: 'title', render: (title: string, record) => <div className="growth-work-title"><span>{title}</span>{record.workUrl && <a href={record.workUrl} target="_blank" rel="noreferrer">查看</a>}</div> },
    { title: '平台', dataIndex: 'platform', width: 90, render: platformLabel },
    { title: '发布时间', dataIndex: 'publishTime', width: 150, render: value => value ? dayjs(value).format('MM-DD HH:mm') : '-' },
    { title: '播放', dataIndex: 'playCount', width: 100, render: formatNumber },
    { title: '点赞', dataIndex: 'likeCount', width: 90, render: formatNumber },
    { title: '评论', dataIndex: 'commentCount', width: 90, render: formatNumber },
    { title: '收藏', dataIndex: 'collectCount', width: 90, render: formatNumber },
    { title: '涨粉', dataIndex: 'followerGain', width: 90, render: formatNumber },
    { title: '互动率', dataIndex: 'interactionRate', width: 100, render: formatRate },
    { title: '状态', dataIndex: 'status', width: 90, render: status => status === 'hot' ? <Tag color="red">爆款</Tag> : status === 'declining' ? <Tag color="orange">下滑</Tag> : <Tag color="blue">正常</Tag> },
    { title: '操作', key: 'actions', width: 120, fixed: 'right', render: (_, record) => <Space size={0}><Button type="link" onClick={() => openEditWork(record)}>编辑</Button><Button type="link" danger onClick={() => handleDeleteWork(record)}>删除</Button></Space> },
  ]

  if (loading) return <div className="loading">加载中...</div>

  return (
    <ConfigProvider theme={contentGrowthTheme}>
      <div className="growth-page">
        <section className="growth-hero">
          <div><h1>内容创作者增长助手</h1><p>帮短视频用户、博主、MCN、小商家复盘数据、发现爆款、生成明日选题。</p><div className="growth-tags"><span>账号概览</span><span>作品表现</span><span>爆款分析</span><span>AI 改稿建议</span></div></div>
          <Button type="primary" size="large" onClick={openCreateAccount}>添加内容账号</Button>
        </section>

        <section className="growth-toolbar">
          <Select allowClear placeholder="查看全部账号" value={selectedAccountId} options={accountSelectOptions} onChange={setSelectedAccountId} className="growth-account-select" />
          <Button onClick={openEditAccount} disabled={!selectedAccount}>编辑账号</Button>
          <Button danger onClick={handleDeleteAccount} disabled={!selectedAccount}>删除账号</Button>
          <Button onClick={openCreateWork} disabled={!accounts.length}>录入作品数据</Button>
          <Button onClick={openImportModal} disabled={!accounts.length}>CSV 批量导入</Button>
          <Button onClick={() => setCompetitorModalOpen(true)}>添加竞品账号</Button>
        </section>

        {!accounts.length && <Alert type="info" showIcon message="先添加一个内容账号" description="第一版先支持手动绑定账号和录入作品数据，平台自动同步数据后续开放。" className="growth-alert" />}

        <section className="growth-stat-grid">
          <Card><div className="growth-stat"><span>已绑定账号</span><strong>{overview?.accountCount || 0}</strong></div></Card>
          <Card><div className="growth-stat"><span>总粉丝</span><strong>{formatNumber(overview?.totalFollowers)}</strong></div></Card>
          <Card><div className="growth-stat"><span>总播放</span><strong>{formatNumber(overview?.totalPlayCount)}</strong></div></Card>
          <Card><div className="growth-stat"><span>总互动</span><strong>{formatNumber((overview?.totalLikeCount || 0) + (overview?.totalCommentCount || 0) + (overview?.totalCollectCount || 0) + (overview?.totalShareCount || 0))}</strong></div></Card>
          <Card><div className="growth-stat"><span>总涨粉</span><strong>{formatNumber(overview?.totalFollowerGain)}</strong></div></Card>
          <Card><div className="growth-stat"><span>平均互动率</span><strong>{formatRate(overview?.averageInteractionRate)}</strong></div></Card>
        </section>

        <section className="growth-main-grid">
          <Card title="作品表现" extra={<Space><Button onClick={openImportModal} disabled={!accounts.length}>CSV 导入</Button><Button type="primary" onClick={openCreateWork} disabled={!accounts.length}>新增作品</Button></Space>} className="growth-table-card">
            <Table rowKey="id" columns={columns} dataSource={works.records} loading={workLoading} scroll={{ x: 1220 }} locale={{ emptyText: <Empty description="暂无作品数据，录入 3-5 条作品后分析更准确" /> }} pagination={{ current: works.current, pageSize: works.size, total: works.total, onChange: (page, size) => loadWorks(page, size, selectedAccountId) }} />
          </Card>
          <Card title="爆款分析" className="growth-ai-card">
            {overview?.bestWork ? <div className="growth-best-work"><span>当前最佳作品</span><strong>{overview.bestWork.title}</strong><p>播放 {formatNumber(overview.bestWork.playCount)} · 互动率 {formatRate(overview.bestWork.interactionRate)}</p></div> : <Empty description="暂无可分析作品" />}
            <Button type="primary" block loading={aiLoading === 'hot'} disabled={!works.records.length} onClick={runHotAnalysis}>分析爆款原因</Button>
            {hotAnalysis && <AiResult result={hotAnalysis} />}
          </Card>
        </section>

        <section className="growth-main-grid">
          <Card title="选题推荐" className="growth-ai-card"><Form form={topicForm} layout="vertical" initialValues={{ goal: '涨粉' }}><Form.Item name="goal" label="增长目标" rules={[{ required: true }]}><Select options={goalOptions} /></Form.Item></Form><Button type="primary" block loading={aiLoading === 'topic'} disabled={!accounts.length} onClick={runTopicRecommendation}>生成明日选题</Button>{topicResult && <AiResult result={topicResult} />}</Card>
          <Card title="竞品监控" extra={<Button onClick={() => setCompetitorModalOpen(true)}>添加竞品</Button>}>{competitors.length ? <div className="growth-competitor-list">{competitors.map(item => <div className="growth-competitor" key={item.id}><div><strong>{item.accountName}</strong><span>{platformLabel(item.platform)} · {item.note || '重点同行账号'}</span></div><Button type="link" danger onClick={async () => { await deleteCompetitor(item.id); await loadCompetitors() }}>删除</Button></div>)}</div> : <Empty description="先记录重点同行账号，自动追踪爆款视频后续开放" />}<Alert className="growth-alert" type="warning" showIcon message="自动追踪同行最近爆的视频将在后续版本开放。" /></Card>
        </section>

        <Card title="AI 改稿建议" className="growth-rewrite-card"><Form form={rewriteForm} layout="vertical" initialValues={{ targetPlatform: 'douyin', goal: '提高完播和收藏' }}><div className="growth-form-grid"><Form.Item name="targetPlatform" label="目标平台" rules={[{ required: true }]}><Select options={platformOptions} /></Form.Item><Form.Item name="goal" label="优化目标" rules={[{ required: true }]}><Input /></Form.Item></div><Form.Item name="draftTitle" label="标题草稿"><Input /></Form.Item><Form.Item name="draftScript" label="脚本草稿" rules={[{ required: true, message: '请输入脚本草稿或内容方向' }]}><Input.TextArea rows={5} /></Form.Item><Button type="primary" loading={aiLoading === 'rewrite'} onClick={runRewriteAdvice}>生成改稿建议</Button></Form>{rewriteResult && <AiResult result={rewriteResult} />}</Card>

        <Modal title={editingAccount ? '编辑内容账号' : '添加内容账号'} open={accountModalOpen} onOk={handleSaveAccount} confirmLoading={saving} onCancel={closeAccountModal} destroyOnClose><Form form={accountForm} layout="vertical"><Form.Item name="platform" label="平台" rules={[{ required: true }]}><Select options={platformOptions} disabled={Boolean(editingAccount)} /></Form.Item><Form.Item name="accountName" label="账号名称" rules={[{ required: true }, { max: 120 }]}><Input /></Form.Item><Form.Item name="homepageUrl" label="主页链接" rules={[{ max: 1000 }]}><Input /></Form.Item><Form.Item name="followerCount" label="当前粉丝数"><InputNumber min={0} precision={0} className="growth-full" /></Form.Item><Form.Item name="accountPositioning" label="账号定位" rules={[{ max: 500 }]}><Input.TextArea rows={3} /></Form.Item></Form></Modal>

        <Modal title={editingWork ? '编辑作品数据' : '录入作品数据'} open={workModalOpen} onOk={handleSaveWork} confirmLoading={saving} onCancel={closeWorkModal} destroyOnClose width={720}><Form form={workForm} layout="vertical"><div className="growth-form-grid"><Form.Item name="accountId" label="内容账号" rules={[{ required: true }]}><Select options={accountSelectOptions} onChange={handleWorkAccountChange} /></Form.Item><Form.Item name="platform" label="平台"><Select options={platformOptions} disabled /></Form.Item></div><Form.Item name="title" label="作品标题" rules={[{ required: true }, { max: 500 }]}><Input /></Form.Item><div className="growth-form-grid"><Form.Item name="workUrl" label="作品链接" rules={[{ max: 1000 }]}><Input /></Form.Item><Form.Item name="coverUrl" label="封面链接" rules={[{ max: 1000 }]}><Input /></Form.Item></div><Form.Item name="publishTime" label="发布时间"><DatePicker showTime className="growth-full" /></Form.Item><div className="growth-number-grid">{['playCount', 'likeCount', 'commentCount', 'collectCount', 'shareCount', 'followerGain'].map((name, index) => <Form.Item name={name} label={['播放', '点赞', '评论', '收藏', '分享', '涨粉'][index]} key={name}><InputNumber min={0} precision={0} /></Form.Item>)}</div><Form.Item name="contentType" hidden><Input /></Form.Item></Form></Modal>

        <Modal title="CSV 批量导入作品" open={importModalOpen} onCancel={closeImportModal} width={900} footer={importResult ? <Button type="primary" onClick={closeImportModal}>完成</Button> : <Space><Button onClick={closeImportModal}>取消</Button><Button type="primary" loading={importing} disabled={!importAccountId || !csvPreview.length || Boolean(previewErrorCount || csvFileError)} onClick={handleImport}>开始导入</Button></Space>}>
          <div className="growth-import-options"><div><span>目标账号</span><Select value={importAccountId} options={accountSelectOptions} onChange={setImportAccountId} /></div><div><span>重复作品</span><Select value={conflictStrategy} onChange={setConflictStrategy} options={[{ label: '更新已有作品', value: 'UPDATE' }, { label: '跳过已有作品', value: 'SKIP' }]} /></div><Button onClick={downloadTemplate}>下载 CSV 模板</Button></div>
          {!importResult && <Upload.Dragger accept=".csv" maxCount={1} showUploadList={false} beforeUpload={file => handleCsvFile(file)}><p className="ant-upload-drag-icon">CSV</p><p className="ant-upload-text">点击或拖拽 CSV 文件到这里</p><p className="ant-upload-hint">UTF-8 编码，最多 2 MB / 500 条作品</p></Upload.Dragger>}
          {csvFileError && <Alert type="error" showIcon message={csvFileError} className="growth-alert" />}
          {!importResult && csvPreview.length > 0 && <div className="growth-import-preview"><Alert type={previewErrorCount ? 'error' : 'success'} showIcon message={`${csvFileName}：共 ${csvPreview.length} 条，${previewErrorCount} 条存在错误`} /><Table size="small" rowKey="key" dataSource={csvPreview} pagination={{ pageSize: 5 }} scroll={{ x: 900 }} columns={[{ title: '行号', dataIndex: ['row', 'rowNumber'], width: 70 }, { title: '标题', dataIndex: ['row', 'title'], width: 220 }, { title: '发布时间', dataIndex: ['row', 'publishTime'], width: 170, render: value => value || '-' }, { title: '播放', dataIndex: ['row', 'playCount'], width: 90 }, { title: '校验结果', key: 'validation', render: (_, record) => { const errors = [...record.errors]; if (record.row.platform && importAccount && record.row.platform !== importAccount.platform) errors.push('平台与目标账号不一致'); return errors.length ? <span className="growth-import-error">{errors.join('；')}</span> : <Tag color="green">通过</Tag> } }]} /></div>}
          {importResult && <ImportResultView result={importResult} />}
        </Modal>

        <Modal title="添加竞品账号" open={competitorModalOpen} onOk={handleCreateCompetitor} onCancel={() => setCompetitorModalOpen(false)} destroyOnClose><Form form={competitorForm} layout="vertical" initialValues={{ platform: 'douyin' }}><Form.Item name="platform" label="平台" rules={[{ required: true }]}><Select options={platformOptions} /></Form.Item><Form.Item name="accountName" label="账号名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="homepageUrl" label="主页链接"><Input /></Form.Item><Form.Item name="note" label="备注"><Input.TextArea rows={3} /></Form.Item></Form></Modal>
      </div>
    </ConfigProvider>
  )
}

function buildPreviewRow(rowNumber: number, values: Record<string, string>): CsvPreviewRow {
  const errors: string[] = []
  const title = values.title?.trim() || ''
  if (!title) errors.push('标题不能为空')
  if (title.length > 500) errors.push('标题超过 500 字符')
  const numbers: Record<string, number> = {}
  numericFields.forEach(field => {
    const raw = values[field]?.trim() || ''
    if (raw && !/^\d+$/.test(raw)) errors.push(`${field} 必须是非负整数`)
    numbers[field] = raw && /^\d+$/.test(raw) ? Number(raw) : 0
    if (!Number.isSafeInteger(numbers[field])) errors.push(`${field} 数值过大`)
  })
  const publishTime = parsePublishTime(values.publishtime || '')
  if (values.publishtime && !publishTime) errors.push('发布时间格式无效')
  const platform = values.platform?.trim() || ''
  if (platform && !platformOptions.some(item => item.value === platform)) errors.push('平台值不支持')
  if ((values.workurl || '').length > 1000 || (values.coverurl || '').length > 1000) errors.push('链接超过 1000 字符')
  return { key: rowNumber, errors, row: { rowNumber, platform, title, workUrl: values.workurl?.trim() || undefined, coverUrl: values.coverurl?.trim() || undefined, publishTime: publishTime || undefined, playCount: numbers.playcount, likeCount: numbers.likecount, commentCount: numbers.commentcount, collectCount: numbers.collectcount, shareCount: numbers.sharecount, followerGain: numbers.followergain, contentType: values.contenttype?.trim() || 'video' } }
}

function parsePublishTime(value: string) {
  const input = value.trim()
  if (!input) return ''
  const normalized = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(input) ? `${input}:00` : input
  if (!/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?$/.test(normalized)) return ''
  const parsed = dayjs(normalized)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : ''
}

function ImportResultView({ result }: { result: WorkImportResult }) {
  return <div className="growth-import-result"><div className="growth-import-summary"><div><span>总数</span><strong>{result.total}</strong></div><div><span>新增</span><strong>{result.created}</strong></div><div><span>更新</span><strong>{result.updated}</strong></div><div><span>跳过</span><strong>{result.skipped}</strong></div><div><span>失败</span><strong>{result.failed}</strong></div></div>{result.errors.length ? <Table size="small" rowKey={(item, index) => `${item.rowNumber}-${index}`} dataSource={result.errors} pagination={{ pageSize: 5 }} columns={[{ title: '行号', dataIndex: 'rowNumber', width: 80 }, { title: '字段', dataIndex: 'field', width: 120, render: value => value || '-' }, { title: '错误与跳过明细', dataIndex: 'message' }]} /> : <Alert type="success" showIcon message="全部导入成功" />}</div>
}

function AiResult({ result }: { result: AiTextResponse }) {
  return <div className="growth-ai-result">{result.fallback && <Alert type="warning" showIcon message="AI 结果为兜底提示" />}<Spin spinning={false}><ReactMarkdown>{result.content}</ReactMarkdown></Spin></div>
}
