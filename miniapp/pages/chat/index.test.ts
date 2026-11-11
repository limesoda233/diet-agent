import { beforeEach, describe, expect, it, vi } from 'vitest'

type Call = { path: string; data?: Record<string, unknown> }
const storage = new Map<string, unknown>()
const calls: Call[] = []
let page: any
let reply: (call: Call) => Record<string, unknown>

beforeEach(async () => {
  vi.resetModules()
  storage.clear()
  calls.length = 0
  reply = () => ({ sessionId: 'next-session', speechText: '推荐', displayBlocks: [] })
  vi.stubGlobal('wx', {
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    cloud: { callContainer: async (call: Call) => {
      calls.push(call)
      return { statusCode: 200, data: reply(call) }
    } },
  })
  vi.stubGlobal('Page', (options: object) => {
    page = { ...options, data: { ...(options as { data: object }).data },
      setData(update: object) { Object.assign(this.data, update) } }
  })
  await import('./index')
  const { saveAuth } = await import('../../services/auth')
  saveAuth({ token: 'test-token', expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 1000000, displayName: null, role: 'USER', status: 'ACTIVE' } })
})

describe('miniapp chat', () => {
  it('starts a personal conversation after switching from a public session', async () => {
    const { saveSession, getSession } = await import('../../services/session')
    saveSession('public-session')
    page.data.messages.push({ role: '小狗', text: '旧回复', meals: [], details: [] })

    page.usePersonal()
    expect(page.data.messages).toHaveLength(1)
    page.onInput({ detail: { value: '晚餐吃什么' } })
    await page.send()

    expect(page.data.sourceMode).toBe('PERSONAL')
    expect(calls[0].data).toMatchObject({ sessionId: null, sourceMode: 'PERSONAL' })
    expect(getSession()).toBe('next-session')
  })

  it('keeps a spoken meal draft for review and saves the current draft explicitly', async () => {
    const draft = { draftId: 'draft-1', originalText: '录入番茄面，午餐',
      data: { name: '番茄面', mealTime: ['午餐'], knowledge: { ingredients: ['番茄'] } },
      missingFields: [], warnings: [], status: 'READY' }
    reply = call => call.data?.context && Object.keys(call.data.context).length
      ? { sessionId: 'next-session', speechText: '已保存', mealDraft: { ...draft, status: 'SAVED', savedMealId: 42 } }
      : { sessionId: 'next-session', speechText: '请核对草稿', mealDraft: draft }

    page.onInput({ detail: { value: '录入番茄面，午餐' } })
    await page.send()
    expect(page.data.draft).toMatchObject({ draftId: 'draft-1', status: 'READY' })

    await page.handleDraftAction({ currentTarget: { dataset: { action: 'SAVE' } } })
    expect(calls[1].data).toMatchObject({ sessionId: 'next-session',
      context: { mealDraftAction: { draftId: 'draft-1', action: 'SAVE' } } })
    expect(page.data.draft).toMatchObject({ status: 'SAVED', savedMealId: 42 })
  })

  it('does not save an incomplete spoken meal draft', async () => {
    reply = () => ({ sessionId: 'draft-session', speechText: '还缺餐次',
      mealDraft: { draftId: 'draft-2', originalText: '录入番茄面', status: 'NEEDS_INPUT',
        data: { name: '番茄面', mealTime: [], knowledge: { ingredients: [] } },
        missingFields: ['mealTime'], warnings: [] } })

    page.onInput({ detail: { value: '录入番茄面' } })
    await page.send()
    await page.handleDraftAction({ currentTarget: { dataset: { action: 'SAVE' } } })

    expect(calls).toHaveLength(1)
    expect(page.data.draftMissing).toEqual(['餐次'])
  })

  it('starts a fresh conversation without changing the selected library', async () => {
    const { saveSession, getSession } = await import('../../services/session')
    saveSession('old-public-session')

    page.restart()

    expect(page.data.sourceMode).toBe('PUBLIC')
    expect(getSession()).toBeNull()
    expect(page.data.messages).toHaveLength(1)
  })
})
