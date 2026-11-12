import { beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ dietaryProfile: vi.fn(), saveDietaryProfile: vi.fn(), skipDietaryProfile: vi.fn() }))
vi.mock('../../services/api', () => ({ api }))
let page: any
const switchTab = vi.fn()

beforeEach(async () => {
  vi.resetModules()
  vi.clearAllMocks()
  api.dietaryProfile.mockResolvedValue({ allergens: [], avoidedFoods: [], dislikedFoods: [],
    spicePreference: 'UNSET', spiceNote: '', onboardingStatus: 'NEW' })
  api.saveDietaryProfile.mockImplementation(async value => value)
  api.skipDietaryProfile.mockResolvedValue({ onboardingStatus: 'SKIPPED' })
  vi.stubGlobal('wx', { switchTab, navigateBack: vi.fn(), showToast: vi.fn() })
  vi.stubGlobal('Page', (options: object) => {
    page = { ...options, data: { ...(options as { data: object }).data },
      setData(update: object) { Object.assign(this.data, update) } }
  })
  await import('./index')
  await page.onLoad({ onboarding: '1' })
})

it('asks one question at a time and saves custom foods and spice restriction', async () => {
  expect(page.data.title).toContain('过敏')
  page.toggle({ currentTarget: { dataset: { label: '花生' } } })
  page.next()
  expect(page.data.title).toContain('不吃')
  page.onCustom({ detail: { value: '韭菜' } })
  page.next()
  expect(page.data.profile.avoidedFoods).toContain('韭菜')
  page.toggle({ currentTarget: { dataset: { label: '完全不吃辣' } } })
  page.next()
  page.toggle({ currentTarget: { dataset: { label: '折耳根' } } })
  page.next()
  await vi.waitFor(() => expect(api.saveDietaryProfile).toHaveBeenCalledWith(expect.objectContaining({
    allergens: ['花生'], avoidedFoods: ['韭菜'], dislikedFoods: ['折耳根'], spicePreference: 'NEVER',
    onboardingStatus: 'COMPLETED',
  })))
  expect(switchTab).toHaveBeenCalledWith({ url: '/pages/chat/index' })
})

it('skips onboarding without writing empty restrictions', async () => {
  await page.close()
  expect(api.skipDietaryProfile).toHaveBeenCalledOnce()
  expect(api.saveDietaryProfile).not.toHaveBeenCalled()
  expect(switchTab).toHaveBeenCalledWith({ url: '/pages/chat/index' })
})
