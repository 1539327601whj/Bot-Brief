import dayjs from 'dayjs'
import customParseFormat from 'dayjs/plugin/customParseFormat'
import timezone from 'dayjs/plugin/timezone'
import utc from 'dayjs/plugin/utc'
import 'dayjs/locale/zh-cn'

dayjs.extend(utc)
dayjs.extend(timezone)
dayjs.extend(customParseFormat)
dayjs.tz.setDefault('Asia/Shanghai')
dayjs.locale('zh-cn')

const DATE_FORMATS = [
  'YYYY-MM-DD HH:mm:ss',
  'YYYY-MM-DDTHH:mm:ss',
  'YYYY-MM-DDTHH:mm:ss.SSS',
  'YYYY-MM-DDTHH:mm:ssZ',
  'YYYY-MM-DD',
]

function pad(value: number) {
  return String(value).padStart(2, '0')
}

const invalidDate = () => dayjs('invalid')

export function parseBeijing(value?: string | number | Date | number[] | null) {
  if (value == null || value === '') return invalidDate()
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value
    return dayjs.tz(
      `${year}-${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}:${pad(second)}`,
      'Asia/Shanghai'
    )
  }
  if (typeof value === 'string') {
    for (const format of DATE_FORMATS) {
      const parsed = dayjs(value, format, true)
      if (parsed.isValid()) return parsed.tz('Asia/Shanghai', true)
    }
  }
  const loose = dayjs(value as string | number | Date)
  return loose.isValid() ? loose : invalidDate()
}

export default dayjs
