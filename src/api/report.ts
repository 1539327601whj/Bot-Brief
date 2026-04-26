const API_BASE = '/api'

export async function fetchReports(page = 1, size = 10, edition?: string) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (edition) params.set('edition', edition)
  const res = await fetch(`${API_BASE}/reports?${params}`)
  if (!res.ok) throw new Error('获取简报列表失败')
  return res.json()
}

export async function fetchLatest(edition?: string) {
  const params = edition ? `?edition=${edition}` : ''
  const res = await fetch(`${API_BASE}/reports/latest${params}`)
  if (!res.ok) throw new Error('获取最新简报失败')
  return res.json()
}

export async function fetchReportById(id: string) {
  const res = await fetch(`${API_BASE}/reports/${id}`)
  if (!res.ok) throw new Error('获取简报详情失败')
  return res.json()
}
