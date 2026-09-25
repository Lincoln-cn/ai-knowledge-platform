import dayjs from 'dayjs'

/**
 * 时间格式化。
 *
 * 旧实现引入的是 **moment**（本工程禁用依赖）—— 但被用到的 `datetimeFormat`
 * 其实只用原生 `Date`，与 moment 无关；这里照原语义重写，不引任何新依赖。
 * `nowDate` / `beforeDay` 这类才需要日期库，用 dayjs（已在依赖里）。
 */

export const nowDate = dayjs().format('YYYY-MM-DD')
export const nowDateTime = dayjs().format('YYYY-MM-DD HH:mm:ss')

/** 当前时间的前 n 天 */
export function beforeDay(n: number | string) {
  return dayjs().subtract(Number(n), 'day').format('YYYY-MM-DD')
}

function parse(timestamp: unknown): Date | null {
  if (!timestamp) return null
  const dt = new Date(timestamp as string)
  return Number.isNaN(dt.getTime()) ? null : dt
}

const pad = (v: number) => String(v).padStart(2, '0')

/** 解析不了就原样返回 —— 不要把 `—` 或空串变成 `Invalid Date` 显示给用户 */
export const datetimeFormat = (timestamp: unknown) => {
  const dt = parse(timestamp)
  if (!dt) return timestamp
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}:${pad(dt.getSeconds())}`
}

export const dateFormat = (timestamp: unknown) => {
  const dt = parse(timestamp)
  if (!dt) return timestamp
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
}
