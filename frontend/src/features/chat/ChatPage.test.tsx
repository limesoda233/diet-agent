import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatPage from './ChatPage'

const app = vi.hoisted(() => ({
  userId: '1',
  api: {
    createSession: vi.fn(),
    chat: vi.fn(),
    saveFeedback: vi.fn(),
  },
  refreshMeals: vi.fn(),
}))

vi.mock('../../context/AppContext', () => ({ useApp: () => app }))

const answer = (overrides: Record<string, unknown> = {}) => ({
  sessionId: 's1', traceId: null, responseType: 'ANSWER', speechText: '可以试试清蒸鱼。',
  displayBlocks: [], planBlocks: [], videoBlocks: [], nextAction: 'WAIT_USER',
  clarifyQuestion: null, missingSlots: [], mealDraft: null, ...overrides,
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const plannedMeal = {
  mealId: 17, sourceMode: 'PERSONAL', ownerUserId: 1, name: '清蒸鱼', mealTimes: ['晚餐'],
  ingredients: ['鱼'], ingredientsKnown: true, tags: { healthGoal: ['清淡'] },
  facts: { 'meal:17:healthGoal:清淡': '清淡' }, fingerprint: 'meal17-snapshot',
}
const mealPlan = (overrides: Record<string, unknown> = {}) => ({
  planId: 'plan-27', version: 3, status: 'COMPLETE', strategy: 'MULTI_AGENT', reviewed: true,
  days: [
    { date: '2026-10-21', blocks: [{ taskId: 'd0-dinner', mealTime: '晚餐', selectedMeal: plannedMeal, reason: '符合清淡偏好', repeated: false }] },
    { date: '2026-10-22', blocks: [{ taskId: 'd1-dinner', mealTime: '晚餐', selectedMeal: { ...plannedMeal, mealId: 18, name: '番茄炒蛋' }, reason: '', repeated: false }] },
  ], gaps: [], unsupportedConstraints: [], warnings: [], ...overrides,
})

async function requestPlan() {
  render(<ChatPage />)
  await userEvent.type(screen.getByRole('textbox', { name: '消息' }), '安排今天和明天的晚餐')
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
}

beforeEach(() => {
  app.userId = '1'
  app.api.createSession.mockReset().mockResolvedValue({ sessionId: 's1' })
  app.api.chat.mockReset().mockResolvedValue(answer())
  app.api.saveFeedback.mockReset().mockResolvedValue(null)
  app.refreshMeals.mockReset()
})

describe('chat interaction', () => {
  it('keeps planned dinners grouped by date without duplicating legacy meal blocks', async () => {
    app.api.chat.mockResolvedValueOnce(answer({ responseType: 'PLAN', mealPlan: mealPlan(),
      displayBlocks: [{ id: 99, name: '重复旧卡片' }], planBlocks: [{ mealTime: '早餐', meals: [{ id: 98, name: '多余早餐' }] }] }))
    await requestPlan()
    const today = await screen.findByRole('region', { name: '2026-10-21 餐食计划' })
    const tomorrow = screen.getByRole('region', { name: '2026-10-22 餐食计划' })
    expect(within(today).getByRole('heading', { name: '晚餐' })).toBeInTheDocument()
    expect(within(today).getByRole('article', { name: '清蒸鱼' })).toHaveTextContent('健康目标：清淡')
    expect(within(tomorrow).getByRole('article', { name: '番茄炒蛋' })).toBeInTheDocument()
    expect(screen.queryByText('多余早餐')).not.toBeInTheDocument()
    expect(screen.queryByText('重复旧卡片')).not.toBeInTheDocument()
    expect(screen.getByText('计划已完成')).toBeInTheDocument()
    expect(screen.getByText('已完成需求复核')).toBeInTheDocument()
  })

  it('shows a partial plan gap with a useful explanation and no feedback buttons', async () => {
    app.api.chat.mockResolvedValueOnce(answer({ mealPlan: mealPlan({ status: 'PARTIAL', reviewed: false,
      days: [{ date: '2026-10-21', blocks: [{ taskId: 'd0-dinner', mealTime: '晚餐', selectedMeal: null, reason: '', repeated: false }] }],
      gaps: [{ taskId: 'd0-dinner', code: 'NO_DISTINCT_CANDIDATE' }],
      unsupportedConstraints: ['MAX_BUDGET'], warnings: ['REVIEW_UNAVAILABLE', 'INTERNAL_NEW_CODE'],
    }) }))
    await requestPlan()
    const plan = await screen.findByRole('region', { name: '2026-10-21 餐食计划' })
    expect(plan).toHaveTextContent('晚餐')
    expect(plan).toHaveTextContent('暂无符合不重复要求的餐食')
    expect(within(plan).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('计划尚有缺口')).toBeInTheDocument()
    expect(screen.getByText('尚未完成需求复核')).toBeInTheDocument()
    expect(screen.getByText(/预算要求暂时无法核验/)).toBeInTheDocument()
    expect(screen.queryByText(/REVIEW_UNAVAILABLE|INTERNAL_NEW_CODE|MAX_BUDGET/)).not.toBeInTheDocument()
  })

  it('sends plan, version, task and trace identity for the exact displayed task', async () => {
    app.api.chat.mockResolvedValueOnce(answer({ traceId: 'plan-trace', mealPlan: mealPlan({
      days: [
        { date: '2026-10-21', blocks: [{ taskId: 'd0-dinner', mealTime: '晚餐', selectedMeal: plannedMeal, reason: '', repeated: false }] },
        { date: '2026-10-22', blocks: [{ taskId: 'd1-dinner', mealTime: '晚餐', selectedMeal: plannedMeal, reason: '', repeated: true }] },
      ],
    }) }))
    await requestPlan()
    const tomorrow = await screen.findByRole('region', { name: '2026-10-22 餐食计划' })
    expect(tomorrow).toHaveTextContent('与计划中其他餐次重复')
    await userEvent.click(within(tomorrow).getByRole('button', { name: '采纳' }))
    await waitFor(() => expect(app.api.saveFeedback).toHaveBeenCalledWith({
      sessionId: 's1', itemId: 17, action: 'ADOPT', rating: 5, reason: '', traceId: 'plan-trace',
      planId: 'plan-27', planVersion: 3, taskId: 'd1-dinner',
    }))
    expect(await within(tomorrow).findByText('反馈已记录')).toBeInTheDocument()
    const today = screen.getByRole('region', { name: '2026-10-21 餐食计划' })
    expect(within(today).getByRole('button', { name: '采纳' })).toBeEnabled()
    expect(within(today).queryByText('反馈已记录')).not.toBeInTheDocument()
  })

  it('explains deterministic degraded output without claiming semantic review succeeded', async () => {
    app.api.chat.mockResolvedValueOnce(answer({ mealPlan: mealPlan({ status: 'DEGRADED', strategy: 'DETERMINISTIC_MENU',
      reviewed: false, warnings: ['MENU_FALLBACK', 'REVIEW_UNAVAILABLE'] }) }))
    await requestPlan()
    expect(await screen.findByText('计划以降级方式生成')).toBeInTheDocument()
    expect(screen.getByText('使用确定性备用选择')).toBeInTheDocument()
    expect(screen.getByText('尚未完成需求复核')).toBeInTheDocument()
    expect(screen.queryByText('已完成需求复核')).not.toBeInTheDocument()
  })

  it.each([{ planId: null, version: 0 }, { planId: 'plan-27', version: undefined }])(
    'does not offer feedback for a plan without persisted identity: %j', async identity => {
      app.api.chat.mockResolvedValueOnce(answer({ mealPlan: mealPlan(identity) }))
      await requestPlan()
      const plan = await screen.findByRole('region', { name: '2026-10-21 餐食计划' })
      expect(within(plan).queryByRole('button')).not.toBeInTheDocument()
      expect(screen.getByText('计划尚未保存，暂不能反馈')).toBeInTheDocument()
    },
  )

  it('shows server memory notices separately as safe text with a management link', async () => {
    app.api.chat.mockResolvedValueOnce(answer({ memoryNotices: ['过敏记忆待确认', '<img src=x onerror=alert(1)>'] }))
    render(<ChatPage />)
    await userEvent.type(screen.getByRole('textbox', { name: '消息' }), '晚饭')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const status = await screen.findByRole('status', { name: '饮食记忆提示' })
    expect(status).toHaveTextContent('过敏记忆待确认')
    expect(status).toHaveTextContent('<img src=x onerror=alert(1)>')
    expect(status.querySelector('img')).toBeNull()
    expect(within(status).getByRole('link', { name: '管理饮食记忆' })).toHaveAttribute('href', '#/diet/memories')
    expect(screen.getByText('可以试试清蒸鱼。').closest('.chat-bubble')).not.toContainElement(status)
  })
  it('sends with Enter, inserts a newline with Shift+Enter, and ignores composition Enter', async () => {
    render(<ChatPage />)
    const input = screen.getByRole('textbox', { name: '消息' })
    fireEvent.change(input, { target: { value: '早餐' } })
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(app.api.chat).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true })
    expect(app.api.chat).not.toHaveBeenCalled()
    expect((input as HTMLTextAreaElement).value).toBe('早餐')
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(app.api.chat).toHaveBeenCalledWith({
      sessionId: 's1', message: '早餐', sourceMode: 'PERSONAL', context: {},
    }))
    expect(await screen.findByText('可以试试清蒸鱼。')).toBeInTheDocument()
  })

  it('keeps the input on failure, then retries without parallel submissions', async () => {
    const pending = deferred<ReturnType<typeof answer>>()
    app.api.chat.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(answer())
    render(<ChatPage />)
    const input = screen.getByRole('textbox', { name: '消息' })
    await userEvent.type(input, '晚饭吃什么')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(screen.getByRole('button', { name: '发送中…' })).toBeDisabled()
    expect((input as HTMLTextAreaElement).value).toBe('晚饭吃什么')
    pending.reject(new Error('网络中断'))
    expect(await screen.findByRole('alert')).toHaveTextContent('网络中断')
    expect((input as HTMLTextAreaElement).value).toBe('晚饭吃什么')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(app.api.chat).toHaveBeenCalledTimes(2))
    expect((input as HTMLTextAreaElement).value).toBe('')
  })

  it('discards a response after a new session or source switch', async () => {
    const first = deferred<ReturnType<typeof answer>>()
    const second = deferred<ReturnType<typeof answer>>()
    app.api.chat.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    render(<ChatPage />)
    const input = screen.getByRole('textbox', { name: '消息' })
    await userEvent.type(input, '第一轮')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await userEvent.click(screen.getByRole('button', { name: '新对话' }))
    await act(async () => { first.resolve(answer({ speechText: '过期回答' })); await first.promise })
    expect(screen.queryByText('过期回答')).not.toBeInTheDocument()
    await userEvent.type(input, '公共库问题')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await userEvent.click(screen.getByRole('button', { name: '公共库' }))
    await act(async () => { second.resolve(answer({ speechText: '也是过期回答' })); await second.promise })
    expect(screen.queryByText('也是过期回答')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '公共库' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('only permits the latest ready draft to save and refreshes meals when saved', async () => {
    const draft = { draftId: 'd1', originalText: '我吃番茄炒蛋', data: { name: '番茄炒蛋' },
      missingFields: [], warnings: [], evidence: {}, status: 'READY', savedMealId: null }
    app.api.chat.mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', mealDraft: draft }))
      .mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', speechText: '已保存',
        mealDraft: { ...draft, status: 'SAVED', savedMealId: 42 } }))
    render(<ChatPage />)
    const input = screen.getByRole('textbox', { name: '消息' })
    await userEvent.type(input, '记下番茄炒蛋')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const draftCard = await screen.findByRole('region', { name: '餐食录入草稿' })
    await userEvent.click(within(draftCard).getByRole('button', { name: '确认保存' }))
    await waitFor(() => expect(app.api.chat).toHaveBeenLastCalledWith({
      sessionId: 's1', message: '确认保存', sourceMode: 'PERSONAL',
      context: { mealDraftAction: { draftId: 'd1', action: 'SAVE' } },
    }))
    expect(await screen.findByText(/已保存，餐食 ID：42/)).toBeInTheDocument()
    expect(app.refreshMeals).toHaveBeenCalledOnce()
    expect(within(draftCard).getByRole('button', { name: '确认保存' })).toBeDisabled()
  })

  it('lets the user inspect original words and the complete saved knowledge before confirming', async () => {
    const draft = { draftId: 'd-preview', originalText: '周末做的番茄炒蛋，先炒蛋再下番茄',
      data: { name: '番茄炒蛋', mealTime: ['午餐'], knowledge: {
        ingredients: ['番茄', '鸡蛋'], description: '酸甜家常菜 <img src=x onerror=alert(1)>',
        preparation: '先炒蛋，再加入番茄焖两分钟。', sourceType: 'USER_PROVIDED', sourceReference: '',
      } }, missingFields: [], warnings: [], evidence: {}, status: 'READY', savedMealId: null }
    app.api.chat.mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', mealDraft: draft }))
    render(<ChatPage />)
    await userEvent.type(screen.getByRole('textbox', { name: '消息' }), '记下这道菜')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const card = await screen.findByRole('region', { name: '餐食录入草稿' })
    expect(within(card).getByText('周末做的番茄炒蛋，先炒蛋再下番茄')).toBeInTheDocument()
    expect(within(card).getByText('酸甜家常菜 <img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(within(card).getByText('先炒蛋，再加入番茄焖两分钟。')).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: '确认保存' })).toBeEnabled()
    expect(within(card).queryByRole('img')).not.toBeInTheDocument()
  })

  it('explicitly shows missing knowledge fields in the draft review', async () => {
    const draft = { draftId: 'd-empty', originalText: '午餐米饭', data: { name: '米饭', mealTime: ['午餐'] },
      missingFields: [], warnings: [], evidence: {}, status: 'READY', savedMealId: null }
    app.api.chat.mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', mealDraft: draft }))
    render(<ChatPage />)
    await userEvent.type(screen.getByRole('textbox', { name: '消息' }), '记下午餐米饭')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const card = await screen.findByRole('region', { name: '餐食录入草稿' })
    expect(within(card).getByText('描述').parentElement).toHaveTextContent('未提供')
    expect(within(card).getByText('做法').parentElement).toHaveTextContent('未提供')
  })

  it('records feedback for the displayed meal and session', async () => {
    app.api.chat.mockResolvedValueOnce(answer({ traceId: 'feedback-trace', displayBlocks: [{ id: 7, sourceType: 'PERSONAL',
      name: '小米粥', mealTime: ['早餐'], mood: [], scene: [], healthGoal: [], cuisine: [],
      taste: [], convenience: [], matchScore: 0.8, knowledge: null }] }))
    render(<ChatPage />)
    await userEvent.type(screen.getByRole('textbox', { name: '消息' }), '早餐')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const card = await screen.findByRole('article', { name: '小米粥' })
    await userEvent.click(within(card).getByRole('button', { name: '喜欢' }))
    await waitFor(() => expect(app.api.saveFeedback).toHaveBeenCalledWith({
      sessionId: 's1', itemId: 7, action: 'LIKE', rating: 5, reason: '', traceId: 'feedback-trace',
    }))
    expect(await within(card).findByText('反馈已记录')).toBeInTheDocument()
  })

  it('refreshes meals after a typed save acknowledgement', async () => {
    const draft = { draftId: 'd2', originalText: '小米粥', data: { name: '小米粥' },
      missingFields: [], warnings: [], evidence: {}, status: 'READY', savedMealId: null }
    app.api.chat.mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', mealDraft: draft }))
      .mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', speechText: '已保存',
        mealDraft: { ...draft, status: 'SAVED', savedMealId: 8 } }))
    render(<ChatPage />)
    const input = screen.getByRole('textbox', { name: '消息' })
    await userEvent.type(input, '记下小米粥')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByRole('button', { name: '确认保存' })
    await userEvent.type(input, '确认保存')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText(/已保存，餐食 ID：8/)).toBeInTheDocument()
    expect(app.refreshMeals).toHaveBeenCalledOnce()
  })

  it('offers return to latest while reading history', async () => {
    render(<ChatPage />)
    const log = screen.getByRole('log')
    Object.defineProperties(log, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 300 },
      scrollTop: { configurable: true, writable: true, value: 200 },
    })
    fireEvent.scroll(log)
    await userEvent.type(screen.getByRole('textbox', { name: '消息' }), '今天吃什么')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('可以试试清蒸鱼。')
    expect(log.scrollTop).toBe(200)
    await userEvent.click(screen.getByRole('button', { name: '回到最新' }))
    expect(log.scrollTop).toBe(1200)
  })

  it('fills a recipe quick question and keeps this turn’s citations and safe video', async () => {
    app.api.chat.mockResolvedValueOnce(answer({
      speechText: '### 食材\n- 番茄 [S1]\n\n### 来源\n- [S1] 番茄炒蛋 · HowToCook',
      videoBlocks: [
        { title: '番茄炒蛋教程', url: 'https://www.bilibili.com/video/BV1abc234', platform: 'BILIBILI', creator: '厨师', durationSeconds: 100 },
        { title: '不可信视频', url: 'https://evil.test/video/BV1abc234', platform: 'BILIBILI', creator: '未知', durationSeconds: 10 },
      ],
    }))
    render(<ChatPage />)
    await userEvent.click(screen.getByRole('button', { name: '宫保鸡丁怎么做？' }))
    const input = screen.getByRole('textbox', { name: '消息' })
    expect(input).toHaveValue('宫保鸡丁怎么做？')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByRole('heading', { name: '食材' })).toBeInTheDocument()
    expect(screen.getByText(/番茄 \[S1\]/)).toBeInTheDocument()
    expect(screen.getByText(/番茄炒蛋 · HowToCook/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /番茄炒蛋教程/ })).toHaveAttribute('target', '_blank')
    expect(screen.queryByRole('link', { name: /不可信视频/ })).not.toBeInTheDocument()
  })

  it('disables an older draft after a later turn and ignores a saved result after reset', async () => {
    const draft = { draftId: 'd3', originalText: '粥', data: { name: '粥' },
      missingFields: [], warnings: [], evidence: {}, status: 'READY', savedMealId: null }
    const saving = deferred<ReturnType<typeof answer>>()
    app.api.chat.mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', mealDraft: draft }))
      .mockResolvedValueOnce(answer({ speechText: '还有别的建议' }))
      .mockResolvedValueOnce(answer({ responseType: 'MEAL_DRAFT', mealDraft: draft }))
      .mockReturnValueOnce(saving.promise)
    render(<ChatPage />)
    const input = screen.getByRole('textbox', { name: '消息' })
    await userEvent.type(input, '记下粥')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const oldDraft = await screen.findByRole('region', { name: '餐食录入草稿' })
    await userEvent.type(input, '还有呢')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('还有别的建议')
    expect(within(oldDraft).getByRole('button', { name: '确认保存' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '新对话' }))
    await userEvent.type(input, '重新记下粥')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    const currentDraft = await screen.findByRole('region', { name: '餐食录入草稿' })
    await userEvent.click(within(currentDraft).getByRole('button', { name: '确认保存' }))
    await userEvent.click(screen.getByRole('button', { name: '新对话' }))
    await act(async () => {
      saving.resolve(answer({ responseType: 'MEAL_DRAFT', mealDraft: { ...draft, status: 'SAVED', savedMealId: 81 } }))
      await saving.promise
    })
    expect(screen.queryByText(/已保存，餐食 ID：81/)).not.toBeInTheDocument()
    expect(app.refreshMeals).not.toHaveBeenCalled()
  })
})
