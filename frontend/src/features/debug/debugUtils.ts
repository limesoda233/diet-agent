import type { SlotKey, TraceLabelRequest } from '../../api/types'

export function localDateTime(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function defaultRange() {
  const end = new Date()
  return { startAt: localDateTime(new Date(end.getTime() - 24 * 60 * 60 * 1000)), endAt: localDateTime(end) }
}

export function rangeError(startAt: string, endAt: string, limit: number) {
  if (!startAt || !endAt || startAt >= endAt) return '结束时间必须晚于开始时间'
  if (!Number.isInteger(limit) || limit < 1 || limit > 500) return '数量上限须为 1 至 500'
  return ''
}

export function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

export function prettyJson(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2) }
    catch { return value }
  }
  return JSON.stringify(value, null, 2)
}

const slotKeys: SlotKey[] = ['mealTime', 'mood', 'scene', 'healthGoal', 'cuisine', 'taste', 'convenience']

export function parseExpectedSlots(text: string): TraceLabelRequest['expectedSlots'] {
  if (!text.trim()) return null
  let value: unknown
  try { value = JSON.parse(text) }
  catch { throw new Error('预期槽位必须是合法 JSON') }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('预期槽位必须是 JSON 对象')
  }
  const slots: NonNullable<TraceLabelRequest['expectedSlots']> = {}
  for (const [key, values] of Object.entries(value)) {
    // SlotBundle serializes its computed isEmpty() property alongside the slot arrays.
    if (key === 'empty') {
      if (typeof values !== 'boolean') throw new Error('empty 必须是布尔值')
      continue
    }
    if (!slotKeys.includes(key as SlotKey)) throw new Error(`未知槽位：${key}`)
    if (!Array.isArray(values) || !values.every(item => typeof item === 'string')) {
      throw new Error(`槽位 ${key} 的值必须是字符串数组`)
    }
    slots[key as SlotKey] = values
  }
  return slots
}
