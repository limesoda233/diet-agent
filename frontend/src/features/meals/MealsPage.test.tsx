import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import MealsPage from './MealsPage'

const api = {
  listPersonalMeals: vi.fn(), listPublicMeals: vi.fn(), slotOptions: vi.fn(),
  generateMealDraft: vi.fn(), createPersonalMeal: vi.fn(), updatePersonalMeal: vi.fn(), deletePersonalMeal: vi.fn(),
}
const refreshMeals = vi.fn()
vi.mock('../../context/AppContext', () => ({ useApp: () => ({ api, userId: '1', mealVersion: 0, refreshMeals }) }))

const slots = { mealTime: ['早餐', '午餐', '晚餐'], mood: [], scene: [], healthGoal: [], cuisine: [], taste: [], convenience: [] }
const seed = {
  id: 8, sourceType: 'PERSONAL', name: '番茄面', mealTime: ['午餐'], mood: [], scene: [],
  healthGoal: [], cuisine: [], taste: [], convenience: [], matchScore: 0,
  knowledge: { ingredients: ['番茄'], description: '清爽', preparation: '水煮', sourceType: 'SYNTHETIC_EVALUATION', sourceReference: 'seed' },
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listPersonalMeals.mockResolvedValue([seed])
  api.listPublicMeals.mockResolvedValue([seed])
  api.slotOptions.mockResolvedValue(slots)
  api.createPersonalMeal.mockResolvedValue(seed)
  api.updatePersonalMeal.mockResolvedValue(seed)
  api.deletePersonalMeal.mockResolvedValue(null)
})

test('draft fills a preview and editable form without saving; a new save includes knowledge even unchanged', async () => {
  api.generateMealDraft.mockResolvedValue({ draftId: 'd1', originalText: '番茄面中午吃', status: 'READY',
    missingFields: [], warnings: [], evidence: {}, savedMealId: null,
    data: { name: '番茄面', ...slots, mealTime: ['午餐'], knowledge: { ingredients: ['番茄'], description: '番茄面中午吃', preparation: '水煮', sourceType: 'USER_PROVIDED', sourceReference: '' } },
  })
  render(<MealsPage />)
  fireEvent.change(await screen.findByLabelText('用一句话描述餐食'), { target: { value: '番茄面中午吃' } })
  fireEvent.click(screen.getByRole('button', { name: 'AI 整理' }))
  expect(await screen.findByText('整理预览')).toBeInTheDocument()
  expect(screen.getByLabelText('餐食名称')).toHaveValue('番茄面')
  expect(api.createPersonalMeal).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '保存到我的餐食库' }))
  await waitFor(() => expect(api.createPersonalMeal).toHaveBeenCalledWith(expect.objectContaining({
    name: '番茄面', mealTime: ['午餐'], knowledge: { ingredients: ['番茄'], description: '番茄面中午吃', preparation: '水煮', sourceType: 'USER_PROVIDED', sourceReference: '' },
  })))
  expect(refreshMeals).toHaveBeenCalled()
})

test('required fields prevent save and leave the form editable', async () => {
  api.listPersonalMeals.mockResolvedValue([])
  render(<MealsPage />)
  await screen.findByLabelText('餐食名称')
  fireEvent.click(screen.getByRole('button', { name: '保存到我的餐食库' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('餐食名称')
  expect(api.createPersonalMeal).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('餐食名称'), { target: { value: '米饭' } })
  fireEvent.click(screen.getByRole('button', { name: '保存到我的餐食库' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('用餐时间')
  expect(api.createPersonalMeal).not.toHaveBeenCalled()
})

test('editing an unchanged seed omits knowledge, and changing it sends user provenance', async () => {
  render(<MealsPage />)
  const card = await screen.findByRole('article', { name: '番茄面' })
  fireEvent.click(within(card).getByRole('button', { name: '编辑' }))
  fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
  await waitFor(() => expect(api.updatePersonalMeal).toHaveBeenCalled())
  expect(api.updatePersonalMeal.mock.calls[0][1]).not.toHaveProperty('knowledge')
  fireEvent.click(within(card).getByRole('button', { name: '编辑' }))
  fireEvent.change(screen.getByLabelText('我的做法'), { target: { value: '慢煮' } })
  fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
  await waitFor(() => expect(api.updatePersonalMeal).toHaveBeenCalledTimes(2))
  expect(api.updatePersonalMeal.mock.calls[1][1].knowledge).toMatchObject({ preparation: '慢煮', sourceType: 'USER_PROVIDED' })
})

test('pending generation cannot overwrite a cleared editor', async () => {
  let resolveDraft!: (value: unknown) => void
  api.generateMealDraft.mockReturnValue(new Promise(resolve => { resolveDraft = resolve }))
  render(<MealsPage />)
  fireEvent.change(await screen.findByLabelText('用一句话描述餐食'), { target: { value: '午餐米饭' } })
  fireEvent.click(screen.getByRole('button', { name: 'AI 整理' }))
  fireEvent.click(screen.getByRole('button', { name: '清空' }))
  resolveDraft({ draftId: 'late', originalText: '午餐米饭', data: { name: '旧草稿', mealTime: ['午餐'] }, missingFields: [], warnings: [] })
  await waitFor(() => expect(screen.getByLabelText('餐食名称')).toHaveValue(''))
  expect(screen.queryByText('整理预览')).not.toBeInTheDocument()
})

test('pending generation cannot replace a meal opened for editing', async () => {
  let resolveDraft!: (value: unknown) => void
  api.generateMealDraft.mockReturnValue(new Promise(resolve => { resolveDraft = resolve }))
  render(<MealsPage />)
  fireEvent.change(await screen.findByLabelText('用一句话描述餐食'), { target: { value: '午餐米饭' } })
  fireEvent.click(screen.getByRole('button', { name: 'AI 整理' }))
  fireEvent.click(within(screen.getByRole('article', { name: '番茄面' })).getByRole('button', { name: '编辑' }))
  resolveDraft({ draftId: 'late', originalText: '午餐米饭', data: { name: '旧草稿', mealTime: ['午餐'] }, missingFields: [], warnings: [] })
  await waitFor(() => expect(screen.getByLabelText('餐食名称')).toHaveValue('番茄面'))
  expect(screen.queryByText('整理预览')).not.toBeInTheDocument()
})

test('public library is searchable and read only', async () => {
  render(<MealsPage publicOnly />)
  expect(await screen.findByRole('heading', { name: '公共餐食' })).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('搜索餐食'), { target: { value: '不存在' } })
  expect(screen.queryByRole('article', { name: '番茄面' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '新增餐食' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument()
})

test('tag filter narrows the list and delete requires confirmation', async () => {
  api.listPersonalMeals.mockResolvedValue([seed, { ...seed, id: 9, name: '小米粥', mealTime: ['早餐'] }])
  render(<MealsPage />)
  const filter = await screen.findByLabelText('筛选标签')
  fireEvent.change(filter, { target: { value: 'mealTime:早餐' } })
  expect(screen.getByRole('article', { name: '小米粥' })).toBeInTheDocument()
  expect(screen.queryByRole('article', { name: '番茄面' })).not.toBeInTheDocument()
  fireEvent.click(within(screen.getByRole('article', { name: '小米粥' })).getByRole('button', { name: '删除' }))
  expect(api.deletePersonalMeal).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '确认删除' }))
  await waitFor(() => expect(api.deletePersonalMeal).toHaveBeenCalledWith(9))
  expect(refreshMeals).toHaveBeenCalled()
})

test('a failed save keeps the entered values for retry and blocks duplicate requests', async () => {
  api.listPersonalMeals.mockResolvedValue([])
  let rejectSave!: (reason: Error) => void
  api.createPersonalMeal.mockReturnValueOnce(new Promise((_, reject) => { rejectSave = reject })).mockResolvedValueOnce(seed)
  render(<MealsPage />)
  fireEvent.change(await screen.findByLabelText('餐食名称'), { target: { value: '米饭' } })
  const mealTime = screen.getByLabelText('用餐时间（必选）');
  (within(mealTime).getByRole('option', { name: '午餐' }) as HTMLOptionElement).selected = true
  fireEvent.change(mealTime)
  fireEvent.click(screen.getByRole('button', { name: '保存到我的餐食库' }))
  expect(screen.getByRole('button', { name: '保存中...' })).toBeDisabled()
  expect(api.createPersonalMeal).toHaveBeenCalledTimes(1)
  rejectSave(new Error('网络断开'))
  expect(await screen.findByRole('alert')).toHaveTextContent('网络断开')
  expect(screen.getByLabelText('餐食名称')).toHaveValue('米饭')
  fireEvent.click(screen.getByRole('button', { name: '保存到我的餐食库' }))
  await waitFor(() => expect(api.createPersonalMeal).toHaveBeenCalledTimes(2))
})

test('a save finishing after the page unmounts does not refresh the next identity', async () => {
  let resolveSave!: (value: unknown) => void
  api.createPersonalMeal.mockReturnValue(new Promise(resolve => { resolveSave = resolve }))
  api.listPersonalMeals.mockResolvedValue([])
  const view = render(<MealsPage />)
  fireEvent.change(await screen.findByLabelText('餐食名称'), { target: { value: '米饭' } })
  const mealTime = screen.getByLabelText('用餐时间（必选）');
  (within(mealTime).getByRole('option', { name: '午餐' }) as HTMLOptionElement).selected = true
  fireEvent.change(mealTime)
  fireEvent.click(screen.getByRole('button', { name: '保存到我的餐食库' }))
  view.unmount()
  resolveSave(seed)
  await Promise.resolve()
  expect(refreshMeals).not.toHaveBeenCalled()
})

test('a save response does not clear edits made while it was pending', async () => {
  let resolveSave!: (value: unknown) => void
  api.updatePersonalMeal.mockReturnValue(new Promise(resolve => { resolveSave = resolve }))
  render(<MealsPage />)
  fireEvent.click(within(await screen.findByRole('article', { name: '番茄面' })).getByRole('button', { name: '编辑' }))
  fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
  fireEvent.change(screen.getByLabelText('餐食名称'), { target: { value: '番茄鸡蛋面' } })
  resolveSave(seed)
  await waitFor(() => expect(refreshMeals).toHaveBeenCalled())
  expect(screen.getByLabelText('餐食名称')).toHaveValue('番茄鸡蛋面')
  expect(screen.getByRole('button', { name: '保存修改' })).not.toBeDisabled()
})
