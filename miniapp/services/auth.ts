import { callBackend } from './transport'

export interface User { id: number; displayName: string | null; role: 'ADMIN' | 'USER'; status: string }
export interface AuthState { token: string; expiresAt: string; user: User }
const KEY = 'diet-auth'

export function getStoredAuth(): AuthState | null {
  const value = wx.getStorageSync(KEY) as AuthState | undefined
  return value?.token && value.user?.id ? value : null
}

export function getAuth(): AuthState | null {
  const value = getStoredAuth()
  return value?.token && new Date(value.expiresAt).getTime() > Date.now() ? value : null
}

export function saveAuth(value: AuthState): void {
  const previous = wx.getStorageSync(KEY) as AuthState | undefined
  if (previous?.user.id && previous.user.id !== value.user.id) {
    wx.removeStorageSync(`diet-session-${previous.user.id}`)
  }
  wx.setStorageSync(KEY, value)
}

export function clearAuth(): void {
  const previous = wx.getStorageSync(KEY) as AuthState | undefined
  if (previous?.user.id) wx.removeStorageSync(`diet-session-${previous.user.id}`)
  wx.removeStorageSync(KEY)
}

export async function login(invitationCode?: string): Promise<AuthState> {
  const code = await new Promise<string>((resolve, reject) => wx.login({
    success: result => resolve(result.code), fail: reject,
  }))
  const result = await callBackend('/api/v1/auth/wechat/login', 'POST',
    { code, invitationCode }, { 'Content-Type': 'application/json' })
  if (result.statusCode !== 200) {
    const error = result.data as { message?: string }
    throw new Error(error?.message || '微信登录失败')
  }
  const state = result.data as AuthState
  saveAuth(state)
  return state
}
