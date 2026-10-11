import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApi } from './client'

afterEach(() => vi.unstubAllGlobals())

describe('createApi', () => {
  it('keeps the user identity captured at creation and sends JSON', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"sessionId":"s-1"}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    const original = createApi('17')
    createApi('23')

    const result = await original.chat({ sessionId: 's-1', message: '午餐吃什么', sourceMode: 'PERSONAL', context: {} })

    expect(result.sessionId).toBe('s-1')
    expect(fetcher).toHaveBeenCalledOnce()
    const [url, init] = fetcher.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/diet/chat')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('X-User-Id')).toBe('17')
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
    expect(init.body).toBe('{"sessionId":"s-1","message":"午餐吃什么","sourceMode":"PERSONAL","context":{}}')
  })

  it('maps an empty successful response to null', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(createApi('1').deletePersonalMeal(5)).resolves.toBeNull()
  })

  it('surfaces a JSON error message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"无权访问"}', { status: 403 })))
    await expect(createApi('1').listPersonalMeals()).rejects.toThrow('无权访问')
  })

  it('encodes path segments and filters absent query fields', async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    const api = createApi('2')
    await api.listSessionTraces('a/b', undefined)
    await api.listTraces({ startAt: '2026-10-11T00:00:00', endAt: '2026-10-12T00:00:00', limit: 0 })
    expect(fetcher.mock.calls[0][0]).toBe('/api/v1/diet/debug/sessions/a%2Fb/traces')
    expect(fetcher.mock.calls[1][0]).toBe('/api/v1/diet/debug/traces?startAt=2026-10-11T00%3A00%3A00&endAt=2026-10-12T00%3A00%3A00&limit=0')
  })
})
