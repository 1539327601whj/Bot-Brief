import dayjs from 'dayjs'
import timezone from 'dayjs/plugin/timezone'
import utc from 'dayjs/plugin/utc'

// 配置 dayjs 使用北京时间
dayjs.extend(utc)
dayjs.extend(timezone)
dayjs.tz.setDefault('Asia/Shanghai')

export default dayjs
