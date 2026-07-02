import api from '../utils/api'

export async function fetchReports(page = 1, size = 10, edition?: string) {
  const params: Record<string, string | number> = { page, size }
  if (edition) params.edition = edition
  const res = await api.get('/reports', { params })
  return res.data
}

export async function fetchLatest(edition?: string) {
  const res = await api.get('/reports/latest', { params: edition ? { edition } : {} })
  return res.data
}

export async function fetchReportById(id: string) {
  const res = await api.get(`/reports/${id}`)
  return res.data
}
