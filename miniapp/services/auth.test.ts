import { beforeEach, describe, expect, it, vi } from 'vitest'
import { login } from './auth'

const storage = new Map<string, unknown>()

beforeEach(() => {
  storage.clear()
  vi.stubGlobal('wx', {
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    login: ({ success }: { success: (value: { code: string }) => void }) => success({ code: 'wx-code' }),
    cloud: { callContainer: () => Promise.resolve({ statusCode: 200, data: {
      token: 'fresh-token', expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 1000000, role: 'ADMIN', status: 'ACTIVE', displayName: null },
    } }) },
  })
})

describe('miniapp login', () => {
  it('accepts a successful login when old local auth has no user', async () => {
    storage.set('diet-auth', { token: 'stale-token', expiresAt: '2099-01-01T00:00:00Z' })

    const state = await login('123456')

    expect(state.user.id).toBe(1000000)
    expect(storage.get('diet-auth')).toEqual(state)
  })
})
