import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CLOUD_ENV_ID } from '../config'
import { clearAuth, saveAuth } from './auth'
import { api, authorizeNextDiagnostic, request } from './api'

type RequestOptions = Parameters<typeof wx.cloud.callContainer>[0]
const storage = new Map<string, unknown>()
let calls: RequestOptions[] = []
let response: (options: RequestOptions) => { statusCode: number; data: unknown }

beforeEach(() => {
  storage.clear()
  calls = []
  response = () => ({ statusCode: 200, data: { ok: true } })
  vi.stubGlobal('wx', {
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    login: ({ success }: { success: (value: { code: string }) => void }) => success({ code: 'wx-code' }),
    cloud: {
      init: () => {},
      callContainer: (options: RequestOptions) => {
        calls.push(options)
        return Promise.resolve(response(options))
      },
    },
  })
  saveAuth({ token: 'secret-token', expiresAt: '2099-01-01T00:00:00',
    user: { id: 1000000, role: 'USER', status: 'ACTIVE', displayName: null } })
})

describe('miniapp API', () => {
  it('sends bearer and consumes a diagnostic token once', async () => {
    authorizeNextDiagnostic('one-shot')
    await request('/api/v1/diet/meals/personal')
    await request('/api/v1/diet/chat', 'POST', { message: 'test' })
    await request('/api/v1/diet/chat', 'POST', { message: 'again' })
    expect(calls[0].header?.Authorization).toBe('Bearer secret-token')
    expect(calls[0].header?.['X-WX-SERVICE']).toBe('diet-agent')
    expect(calls[0].config.env).toBe(CLOUD_ENV_ID)
    expect(calls[0].path).toBe('/api/v1/diet/meals/personal')
    expect(calls[0].header?.['X-Diet-Diagnostic-Consent']).toBeUndefined()
    expect(calls[1].header?.['X-Diet-Diagnostic-Consent']).toBe('one-shot')
    expect(calls[2].header?.['X-Diet-Diagnostic-Consent']).toBeUndefined()
  })

  it('relogs once after 401 and retries with the new token', async () => {
    response = options => {
      if (options.path.endsWith('/wechat/login')) return { statusCode: 200,
        data: { token: 'new-token', expiresAt: '2099-01-01T00:00:00',
          user: { id: 1000000, role: 'USER', status: 'ACTIVE', displayName: null } } }
      if (options.header?.Authorization === 'Bearer secret-token') return { statusCode: 401, data: {} }
      return { statusCode: 200, data: { ok: true } }
    }
    await request('/api/v1/diet/meals/personal')
    expect(calls).toHaveLength(3)
    expect(calls[2].header?.Authorization).toBe('Bearer new-token')
  })

  it('clears the old user session when identity changes', () => {
    storage.set('diet-session-1000000', 'old-session')
    saveAuth({ token: 'other-token', expiresAt: '2099-01-01T00:00:00',
      user: { id: 1000001, role: 'USER', status: 'ACTIVE', displayName: null } })
    expect(storage.has('diet-session-1000000')).toBe(false)
    clearAuth()
    expect(storage.has('diet-auth')).toBe(false)
  })

  it('creates an admin invitation through the authenticated cloud service', async () => {
    response = () => ({ statusCode: 200, data: { invitationCode: 'friend-code' } })
    expect(await api.createInvitation()).toEqual({ invitationCode: 'friend-code' })
    expect(calls[0].path).toBe('/api/v1/admin/invitations')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].header.Authorization).toBe('Bearer secret-token')
  })
})
