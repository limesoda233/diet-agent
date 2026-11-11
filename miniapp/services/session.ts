import { getAuth } from './auth'

export function getSession(): string | null {
  const id = getAuth()?.user.id
  return id ? (wx.getStorageSync(`diet-session-${id}`) as string || null) : null
}
export function saveSession(sessionId: string): void {
  const id = getAuth()?.user.id
  if (id) wx.setStorageSync(`diet-session-${id}`, sessionId)
}
export function clearSession(): void {
  const id = getAuth()?.user.id
  if (id) wx.removeStorageSync(`diet-session-${id}`)
}
