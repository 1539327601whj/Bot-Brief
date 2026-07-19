import { useEffect, useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { Alert, Button, Card, DatePicker, Empty, Form, Input, InputNumber, Modal, Select, Spin, Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import dayjs from '../utils/dayjs'
import {
  analyzeHotWork,
  createAccount,
  createCompetitor,
  createWork,
  deleteCompetitor,
  getAccounts,
  getCompetitors,
  getOverview,
  getWorks,
  platformLabel,
  platformOptions,
  recommendTopics,
  rewriteAdvice,
  type AiTextResponse,
  type CompetitorAccount,
  type ContentAccount,
  type ContentOverview,
  type ContentWork,
  type PageData,
} from '../api/contentGrowth'
import './ContentGrowth.css'

const goalOptions = [
  { label: '涨粉', value: '涨粉' },
  { label: '提高播放', value: '提高播放' },
  { label: '提高收藏', value: '提高收藏' },
  { label: '带货转化', value: '带货转化' },
]

const formatNumber = (value?: number) => {
  const n = value || 0
  if (n >= 10000) return `${(n / 10000).toFixed(n >= 100000 ? 0 : 1)}万`
  return n.toLocaleString('zh-CN')
}

const formatRate = (value?: number) => `${(((value || 0) * 100)).toFixed(2)}%`

export default function ContentGrowth() {
  const [accounts, setAccounts] = useState<ContentAccount[]>([])
  const [works, setWorks] = useState<PageData<ContentWork>>({ records: [], total: 0, current: 1, size: 10 })
  const [competitors, setCompetitors] = useState<CompetitorAccount[]>([])
  const [overview, setOverview] = useState<ContentOverview | null>(null)
  const [selectedAccountId, setSelectedAccountId] = useState<number | undefined>()
  const [loading, setLoading] = useState(true)
  const [workLoading, setWorkLoading] = useState(false)
  const [accountModalOpen, setAccountModalOpen] = useState(false)
  const [workModalOpen, setWorkModalOpen] = useState(false)
  const [competitorModalOpen, setCompetitorModalOpen] = useState(false)
  const [aiLoading, setAiLoading] = useState<string>('')
  const [hotAnalysis, setHotAnalysis] = useState<AiTextResponse | null>(null)
  const [topicResult, setTopicResult] = useState<AiTextResponse | null>(null)
  const [rewriteResult, setRewriteResult] = useState<AiTextResponse | null>(null)
  const [accountForm] = Form.useForm()
  const [workForm] = Form.useForm()
  const [competitorForm] = Form.useForm()
  const [topicForm] = Form.useForm()
  const [rewriteForm] = Form.useForm()

  const accountSelectOptions = useMemo(() => accounts.map(account => ({
    label: `${platformLabel(account.platform)} · ${account.accountName}`,
    value: account.id,
  })), [accounts])

  const loadAccounts = async () => {
    const res = await getAccounts()
    if (res.data?.code === 200) setAccounts(res.data.data || [])
  }

  const loadOverview = async () => {
    const res = await getOverview(selectedAccountId)
    if (res.data?.code === 200) setOverview(res.data.data)
  }

  const loadWorks = async (page = works.current || 1, size = works.size || 10) => {
    setWorkLoading(true)
    try {
      const res = await getWorks({ accountId: selectedAccountId, page, size })
      if (res.data?.code === 200) setWorks(res.data.data)
    } finally {
      setWorkLoading(false)
    }
  }

  const loadCompetitors = async () => {
    const res = await getCompetitors()
    if (res.data?.code === 200) setCompetitors(res.data.data || [])
  }

  const refreshAll = async () => {
    setLoading(true)
    try {
      await Promise.all([loadAccounts(), loadOverview(), loadWorks(1, works.size || 10), loadCompetitors()])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refreshAll()
  }, [])

  useEffect(() => {
    loadOverview()
    loadWorks(1, works.size || 10)
  }, [selectedAccountId])

  const handleCreateAccount = async () => {
    const values = await accountForm.validateFields()
    const res = await createAccount(values)
    if (res.data?.code === 200) {
      message.success('账号已添加')
      setAccountModalOpen(false)
      accountForm.resetFields()
      await loadAccounts()
      await loadOverview()
    } else {
      message.error(res.data?.message || '添加失败')
    }
  }

  const handleCreateWork = async () => {
    const values = await workForm.validateFields()
    const payload = {
      ...values,
      publishTime: values.publishTime ? values.publishTime.format('YYYY-MM-DD HH:mm:ss') : undefined,
    }
    const res = await createWork(payload)
    if (res.data?.code === 200) {
      message.success('作品已添加')
      setWorkModalOpen(false)
      workForm.resetFields()
      await loadWorks(1, works.size || 10)
      await loadOverview()
    } else {
      message.error(res.data?.message || '添加失败')
    }
  }

  const handleCreateCompetitor = async () => {
    const values = await competitorForm.validateFields()
    const res = await createCompetitor(values)
    if (res.data?.code === 200) {
      message.success('竞品账号已添加')
      setCompetitorModalOpen(false)
      competitorForm.resetFields()
      await loadCompetitors()
    } else {
      message.error(res.data?.message || '添加失败')
    }
  }

  const runHotAnalysis = async () => {
    setAiLoading('hot')
    try {
      const res = await analyzeHotWork({ accountId: selectedAccountId, workId: overview?.bestWork?.id })
      if (res.data?.code === 200) setHotAnalysis(res.data.data)
      else message.error(res.data?.message || '分析失败')
    } finally {
      setAiLoading('')
    }
  }

  const runTopicRecommendation = async () => {
    const values = await topicForm.validateFields()
    setAiLoading('topic')
    try {
      const res = await recommendTopics({ accountId: selectedAccountId, goal: values.goal, count: 5 })
      if (res.data?.code === 200) setTopicResult(res.data.data)
      else message.error(res.data?.message || '生成失败')
    } finally {
      setAiLoading('')
    }
  }

  const runRewriteAdvice = async () => {
    const values = await rewriteForm.validateFields()
    setAiLoading('rewrite')
    try {
      const res = await rewriteAdvice({ accountId: selectedAccountId, ...values })
      if (res.data?.code === 200) setRewriteResult(res.data.data)
      else message.error(res.data?.message || '生成失败')
    } finally {
      setAiLoading('')
    }
  }

  const columns: ColumnsType<ContentWork> = [
    {
      title: '作品',
      dataIndex: 'title',
      render: (title: string, record) => (
        <div className="growth-work-title">
          <span>{title}</span>
          {record.workUrl && <a href={record.workUrl} target="_blank" rel="noreferrer">查看</a>}
        </div>
      ),
    },
    { title: '平台', dataIndex: 'platform', width: 90, render: platformLabel },
    { title: '发布时间', dataIndex: 'publishTime', width: 150, render: value => value ? dayjs(value).format('MM-DD HH:mm') : '-' },
    { title: '播放', dataIndex: 'playCount', width: 100, render: formatNumber },
    { title: '点赞', dataIndex: 'likeCount', width: 90, render: formatNumber },
    { title: '评论', dataIndex: 'commentCount', width: 90, render: formatNumber },
    { title: '收藏', dataIndex: 'collectCount', width: 90, render: formatNumber },
    { title: '涨粉', dataIndex: 'followerGain', width: 90, render: formatNumber },
    { title: '互动率', dataIndex: 'interactionRate', width: 100, render: formatRate },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: status => {
        if (status === 'hot') return <Tag color="red">爆款</Tag>
        if (status === 'declining') return <Tag color="orange">下滑</Tag>
        return <Tag color="blue">正常</Tag>
      },
    },
  ]

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="growth-page">
      <section className="growth-hero">
        <div>
          <h1>内容创作者增长助手</h1>
          <p>帮短视频用户、博主、MCN、小商家复盘数据、发现爆款、生成明日选题。</p>
          <div className="growth-tags">
            <span>账号概览</span>
            <span>作品表现</span>
            <span>爆款分析</span>
            <span>AI 改稿建议</span>
          </div>
        </div>
        <Button type="primary" size="large" onClick={() => setAccountModalOpen(true)}>添加内容账号</Button>
      </section>

      <section className="growth-toolbar">
        <Select
          allowClear
          placeholder="查看全部账号"
          value={selectedAccountId}
          options={accountSelectOptions}
          onChange={setSelectedAccountId}
          className="growth-account-select"
        />
        <Button onClick={() => setWorkModalOpen(true)} disabled={!accounts.length}>录入作品数据</Button>
        <Button onClick={() => setCompetitorModalOpen(true)}>添加竞品账号</Button>
      </section>

      {!accounts.length && (
        <Alert
          type="info"
          showIcon
          message="先添加一个内容账号"
          description="第一版先支持手动绑定账号和录入作品数据，平台自动同步数据后续开放。"
          className="growth-alert"
        />
      )}

      <section className="growth-stat-grid">
        <Card><div className="growth-stat"><span>已绑定账号</span><strong>{overview?.accountCount || 0}</strong></div></Card>
        <Card><div className="growth-stat"><span>总粉丝</span><strong>{formatNumber(overview?.totalFollowers)}</strong></div></Card>
        <Card><div className="growth-stat"><span>总播放</span><strong>{formatNumber(overview?.totalPlayCount)}</strong></div></Card>
        <Card><div className="growth-stat"><span>总互动</span><strong>{formatNumber((overview?.totalLikeCount || 0) + (overview?.totalCommentCount || 0) + (overview?.totalCollectCount || 0) + (overview?.totalShareCount || 0))}</strong></div></Card>
        <Card><div className="growth-stat"><span>总涨粉</span><strong>{formatNumber(overview?.totalFollowerGain)}</strong></div></Card>
        <Card><div className="growth-stat"><span>平均互动率</span><strong>{formatRate(overview?.averageInteractionRate)}</strong></div></Card>
      </section>

      <section className="growth-main-grid">
        <Card title="作品表现" extra={<Button type="primary" onClick={() => setWorkModalOpen(true)} disabled={!accounts.length}>新增作品</Button>} className="growth-table-card">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={works.records}
            loading={workLoading}
            scroll={{ x: 1100 }}
            locale={{ emptyText: <Empty description="暂无作品数据，录入 3-5 条作品后分析更准确" /> }}
            pagination={{
              current: works.current,
              pageSize: works.size,
              total: works.total,
              onChange: loadWorks,
            }}
          />
        </Card>

        <Card title="爆款分析" className="growth-ai-card">
          {overview?.bestWork ? (
            <div className="growth-best-work">
              <span>当前最佳作品</span>
              <strong>{overview.bestWork.title}</strong>
              <p>播放 {formatNumber(overview.bestWork.playCount)} · 互动率 {formatRate(overview.bestWork.interactionRate)}</p>
            </div>
          ) : <Empty description="暂无可分析作品" />}
          <Button type="primary" block loading={aiLoading === 'hot'} disabled={!works.records.length} onClick={runHotAnalysis}>分析爆款原因</Button>
          {hotAnalysis && <AiResult result={hotAnalysis} />}
        </Card>
      </section>

      <section className="growth-main-grid">
        <Card title="选题推荐" className="growth-ai-card">
          <Form form={topicForm} layout="vertical" initialValues={{ goal: '涨粉' }}>
            <Form.Item name="goal" label="增长目标" rules={[{ required: true, message: '请选择目标' }]}>
              <Select options={goalOptions} />
            </Form.Item>
          </Form>
          <Button type="primary" block loading={aiLoading === 'topic'} disabled={!accounts.length} onClick={runTopicRecommendation}>生成明日选题</Button>
          {topicResult && <AiResult result={topicResult} />}
        </Card>

        <Card title="竞品监控" extra={<Button onClick={() => setCompetitorModalOpen(true)}>添加竞品</Button>}>
          {competitors.length ? (
            <div className="growth-competitor-list">
              {competitors.map(item => (
                <div className="growth-competitor" key={item.id}>
                  <div>
                    <strong>{item.accountName}</strong>
                    <span>{platformLabel(item.platform)} · {item.note || '重点同行账号'}</span>
                  </div>
                  <Button type="link" danger onClick={async () => { await deleteCompetitor(item.id); await loadCompetitors() }}>删除</Button>
                </div>
              ))}
            </div>
          ) : <Empty description="先记录重点同行账号，自动追踪爆款视频后续开放" />}
          <Alert className="growth-alert" type="warning" showIcon message="自动追踪同行最近爆的视频将在后续版本开放。" />
        </Card>
      </section>

      <Card title="AI 改稿建议" className="growth-rewrite-card">
        <Form form={rewriteForm} layout="vertical" initialValues={{ targetPlatform: 'douyin', goal: '提高完播和收藏' }}>
          <div className="growth-form-grid">
            <Form.Item name="targetPlatform" label="目标平台" rules={[{ required: true, message: '请选择平台' }]}>
              <Select options={platformOptions} />
            </Form.Item>
            <Form.Item name="goal" label="优化目标" rules={[{ required: true, message: '请输入优化目标' }]}>
              <Input placeholder="例如：提高完播和收藏" />
            </Form.Item>
          </div>
          <Form.Item name="draftTitle" label="标题草稿">
            <Input placeholder="例如：普通人做账号最容易踩的 3 个坑" />
          </Form.Item>
          <Form.Item name="draftScript" label="脚本草稿" rules={[{ required: true, message: '请输入脚本草稿或内容方向' }]}>
            <Input.TextArea rows={5} placeholder="粘贴脚本草稿，AI 会给出标题、封面、开头钩子和结构建议" />
          </Form.Item>
          <Button type="primary" loading={aiLoading === 'rewrite'} onClick={runRewriteAdvice}>生成改稿建议</Button>
        </Form>
        {rewriteResult && <AiResult result={rewriteResult} />}
      </Card>

      <Modal title="添加内容账号" open={accountModalOpen} onOk={handleCreateAccount} onCancel={() => setAccountModalOpen(false)} destroyOnClose>
        <Form form={accountForm} layout="vertical" initialValues={{ platform: 'douyin', followerCount: 0 }}>
          <Form.Item name="platform" label="平台" rules={[{ required: true, message: '请选择平台' }]}><Select options={platformOptions} /></Form.Item>
          <Form.Item name="accountName" label="账号名称" rules={[{ required: true, message: '请输入账号名称' }]}><Input /></Form.Item>
          <Form.Item name="homepageUrl" label="主页链接"><Input placeholder="可选" /></Form.Item>
          <Form.Item name="followerCount" label="当前粉丝数"><InputNumber min={0} className="growth-full" /></Form.Item>
          <Form.Item name="accountPositioning" label="账号定位"><Input.TextArea rows={3} placeholder="例如：职场干货、本地生活、美妆测评" /></Form.Item>
        </Form>
      </Modal>

      <Modal title="录入作品数据" open={workModalOpen} onOk={handleCreateWork} onCancel={() => setWorkModalOpen(false)} destroyOnClose width={720}>
        <Form form={workForm} layout="vertical" initialValues={{ platform: 'douyin', accountId: selectedAccountId, contentType: 'video', playCount: 0, likeCount: 0, commentCount: 0, collectCount: 0, shareCount: 0, followerGain: 0 }}>
          <div className="growth-form-grid">
            <Form.Item name="accountId" label="内容账号" rules={[{ required: true, message: '请选择账号' }]}><Select options={accountSelectOptions} /></Form.Item>
            <Form.Item name="platform" label="平台" rules={[{ required: true, message: '请选择平台' }]}><Select options={platformOptions} /></Form.Item>
          </div>
          <Form.Item name="title" label="作品标题" rules={[{ required: true, message: '请输入作品标题' }]}><Input /></Form.Item>
          <div className="growth-form-grid">
            <Form.Item name="workUrl" label="作品链接"><Input /></Form.Item>
            <Form.Item name="coverUrl" label="封面链接"><Input /></Form.Item>
          </div>
          <Form.Item name="publishTime" label="发布时间"><DatePicker showTime className="growth-full" /></Form.Item>
          <div className="growth-number-grid">
            <Form.Item name="playCount" label="播放"><InputNumber min={0} /></Form.Item>
            <Form.Item name="likeCount" label="点赞"><InputNumber min={0} /></Form.Item>
            <Form.Item name="commentCount" label="评论"><InputNumber min={0} /></Form.Item>
            <Form.Item name="collectCount" label="收藏"><InputNumber min={0} /></Form.Item>
            <Form.Item name="shareCount" label="分享"><InputNumber min={0} /></Form.Item>
            <Form.Item name="followerGain" label="涨粉"><InputNumber min={0} /></Form.Item>
          </div>
        </Form>
      </Modal>

      <Modal title="添加竞品账号" open={competitorModalOpen} onOk={handleCreateCompetitor} onCancel={() => setCompetitorModalOpen(false)} destroyOnClose>
        <Form form={competitorForm} layout="vertical" initialValues={{ platform: 'douyin' }}>
          <Form.Item name="platform" label="平台" rules={[{ required: true, message: '请选择平台' }]}><Select options={platformOptions} /></Form.Item>
          <Form.Item name="accountName" label="账号名称" rules={[{ required: true, message: '请输入账号名称' }]}><Input /></Form.Item>
          <Form.Item name="homepageUrl" label="主页链接"><Input /></Form.Item>
          <Form.Item name="note" label="备注"><Input.TextArea rows={3} placeholder="例如：同赛道高频爆款账号" /></Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

function AiResult({ result }: { result: AiTextResponse }) {
  return (
    <div className="growth-ai-result">
      {result.fallback && <Alert type="warning" showIcon message="AI 结果为兜底提示" />}
      <Spin spinning={false}>
        <ReactMarkdown>{result.content}</ReactMarkdown>
      </Spin>
    </div>
  )
}
