import { beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ slotOptions: vi.fn(), personalMeals: vi.fn(), generateMealDraft: vi.fn(),
  suggestNutrition: vi.fn(), previewNutrition: vi.fn(), createMeal: vi.fn(), updateMeal: vi.fn(), mergeMeal: vi.fn(),
  personalVariants: vi.fn(), deleteVariant: vi.fn() }))
vi.mock('../../services/api', () => ({ api, ApiError: class extends Error {} }))
let page: any

beforeEach(async () => {
  vi.resetModules(); vi.clearAllMocks()
  api.slotOptions.mockResolvedValue({ mealTime: ['午餐'], mood: [], scene: [], healthGoal: [],
    cuisine: [], taste: [], convenience: [] })
  api.generateMealDraft.mockResolvedValue({ data: { mealTime: ['午餐'], mood: [], scene: [],
    healthGoal: [], cuisine: [], taste: [], convenience: [] } })
  api.suggestNutrition.mockResolvedValue({ templateCode: 'MALATANG',
    questions: [{ key: 'size', label: '份量', options: [{ value: 'SMALL', label: '小份' }, { value: 'LARGE', label: '大份' }] }],
    selections: { size: 'SMALL' }, estimate: { kcalLow: 300, kcalHigh: 500 } })
  api.previewNutrition.mockResolvedValue({ kcalLow: 500, kcalHigh: 800 })
  api.createMeal.mockResolvedValue({ id: 9 })
  vi.stubGlobal('wx', { switchTab: vi.fn(), showModal: vi.fn() })
  vi.stubGlobal('Page', (options: object) => { page = { ...options, data: { ...(options as { data: object }).data },
    setData(update: object) { Object.assign(this.data, update) } } })
  await import('./index')
  await page.onLoad({})
})

it('lets a person review meal-specific buttons and saves one composition version', async () => {
  page.onName({ detail: { value: '麻辣烫' } })
  await page.generate()
  expect(page.data.nutritionGroups).toHaveLength(1)
  page.chooseNutrition({ currentTarget: { dataset: { key: 'size', value: 'LARGE' } } })
  await vi.waitFor(() => expect(api.previewNutrition).toHaveBeenCalledWith('MALATANG', { size: 'LARGE' }))
  await page.save()
  expect(api.createMeal).toHaveBeenCalledWith(expect.objectContaining({ nutritionVariant: expect.objectContaining({
    templateCode: 'MALATANG', selections: { size: 'LARGE' },
  }) }))
})

it('shows the draft parsing error even when an unsupported nutrition template returns successfully', async () => {
  api.generateMealDraft.mockRejectedValueOnce(new Error('餐食整理失败或超时，请重试或手动录入'))
  api.suggestNutrition.mockResolvedValueOnce({ templateCode: 'UNKNOWN', questions: [], selections: {}, estimate: null })
  page.onName({ detail: { value: '番茄鸡蛋面' } })
  await page.generate()
  expect(page.data.error).toContain('餐食整理失败或超时')
  expect(page.data.analysisResult).toBe('')
})

it('explains when parsing succeeds but adds no selectable tags', async () => {
  api.generateMealDraft.mockResolvedValueOnce({ data: { mealTime: [], mood: [], scene: [],
    healthGoal: [], cuisine: [], taste: [], convenience: [] } })
  api.suggestNutrition.mockResolvedValueOnce({ templateCode: 'UNKNOWN', questions: [], selections: {}, estimate: null })
  page.onName({ detail: { value: '番茄鸡蛋面' } })
  await page.generate()
  expect(page.data.analysisResult).toContain('没有新增标签')
})
