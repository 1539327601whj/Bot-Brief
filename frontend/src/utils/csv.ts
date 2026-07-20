export interface CsvRecord {
  rowNumber: number
  values: Record<string, string>
}

export interface CsvParseResult {
  headers: string[]
  records: CsvRecord[]
}

interface CsvCell {
  value: string
  rowNumber: number
}

export function parseCsv(text: string): CsvParseResult {
  const input = text.replace(/^﻿/, '')
  const rows: CsvCell[][] = []
  let row: CsvCell[] = []
  let field = ''
  let inQuotes = false
  let closedQuote = false
  let line = 1
  let fieldLine = 1

  const pushField = () => {
    row.push({ value: field, rowNumber: fieldLine })
    field = ''
    closedQuote = false
    fieldLine = line
  }

  const pushRow = () => {
    pushField()
    if (row.some(cell => cell.value.trim() !== '')) rows.push(row)
    row = []
    fieldLine = line + 1
  }

  for (let i = 0; i < input.length; i += 1) {
    const char = input[i]
    const next = input[i + 1]

    if (inQuotes) {
      if (char === '"') {
        if (next === '"') {
          field += '"'
          i += 1
        } else {
          inQuotes = false
          closedQuote = true
        }
      } else {
        field += char
        if (char === '\n') line += 1
      }
      continue
    }

    if (closedQuote && char !== ',' && char !== '\n' && char !== '\r' && !/\s/.test(char)) {
      throw new Error(`第 ${line} 行引号字段后存在非法字符`)
    }

    if (char === '"') {
      if (field.trim() !== '') throw new Error(`第 ${line} 行双引号位置不正确`)
      field = ''
      inQuotes = true
    } else if (char === ',') {
      pushField()
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && next === '\n') i += 1
      pushRow()
      line += 1
      fieldLine = line
    } else if (!closedQuote || !/\s/.test(char)) {
      field += char
    }
  }

  if (inQuotes) throw new Error(`第 ${fieldLine} 行存在未闭合的双引号`)
  if (field !== '' || row.length > 0) pushRow()
  if (!rows.length) throw new Error('CSV 文件为空')

  const headers = rows[0].map(cell => cell.value.trim())
  if (headers.some(header => !header)) throw new Error('CSV 表头不能为空')
  const normalized = headers.map(header => header.toLowerCase())
  if (new Set(normalized).size !== normalized.length) throw new Error('CSV 存在重复表头')
  if (!normalized.includes('title')) throw new Error('CSV 缺少必填表头 title')

  const records = rows.slice(1).map(cells => {
    const values: Record<string, string> = {}
    normalized.forEach((header, index) => {
      values[header] = cells[index]?.value.trim() || ''
    })
    return { rowNumber: cells[0]?.rowNumber || 2, values }
  })

  return { headers: normalized, records }
}
