import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, test, vi } from 'vitest'
import { AppProvider } from '../../context/AppContext'
import { useApp } from '../../context/AppContext'
import TracePage from './TracePage'

const trace = (traceId: string, overrides: Record<string, unknown> = {}) => ({
  id: traceId === 't1' ? 1 : 2, traceId, sessionId: 's1', userId: 1, status: 'SUCCESS',
  eventCount: 1, durationMs: 25, errorMessage: null,
  traceJson: JSON.stringify({ events: [{ stepOrder: 1, eventType: 'INTENT_CLASSIFIED', phase: 'ROUTE',
    agentName: 'router', modelName: null, inputPayload: '{"text":"晚饭"}', outputPayload: '{"intent":"MEAL_RECOMMENDATION"}',
    latencyMs: 4, inputTokens: null, outputTokens: null, totalTokens: null, errorMessage: null,
    createdAt: '2026-10-11T10:01:00' }] }),
  expectedIntent: null, expectedSlots: null, expectedClarifyAction: null, labeledBy: null,
  labeledAt: null, labelNote: null, createdAt: '2026-10-11T10:00:00', updatedAt: '2026-10-11T10:00:00',
  ...overrides,
})

function reply(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status,
    headers: { 'Content-Type': 'application/json' } })
}

function mount(path = '/admin/traces') {
  return render(<AppProvider><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/admin/traces" element={<TracePage />} />
  </Routes></MemoryRouter></AppProvider>)
}

beforeEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

test('filters traces by time or session and opens event payloads', async () => {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = String(input)
    requests.push(url)
    if (url.includes('/debug/traces/t1')) return reply(trace('t1'))
    if (url.includes('/debug/sessions/s1/traces')) return reply([trace('t1')])
    return reply([trace('t1')])
  }))
  mount()
  await screen.findByRole('button', { name: '查询 Trace' })
  fireEvent.change(screen.getByLabelText('开始时间'), { target: { value: '2026-10-10T09:00' } })
  fireEvent.change(screen.getByLabelText('结束时间'), { target: { value: '2026-10-11T09:00' } })
  fireEvent.change(screen.getByLabelText('数量上限'), { target: { value: '12' } })
  fireEvent.change(screen.getByLabelText('标注状态'), { target: { value: 'true' } })
  fireEvent.click(screen.getByRole('button', { name: '查询 Trace' }))
  await waitFor(() => expect(requests.some(url => url.includes('startAt=2026-10-10T09%3A00') &&
    url.includes('endAt=2026-10-11T09%3A00') && url.includes('onlyUnlabeled=true') && url.includes('limit=12'))).toBe(true))
  await screen.findByRole('button', { name: '查询 Trace' })
  fireEvent.change(screen.getByLabelText('会话 ID（可选）'), { target: { value: ' s1 ' } })
  fireEvent.click(screen.getByRole('button', { name: '查询 Trace' }))
  await waitFor(() => expect(requests.some(url => url.includes('/debug/sessions/s1/traces?limit=12'))).toBe(true))
  fireEvent.click(within(await screen.findByRole('table', { name: 'Trace 列表' })).getByRole('button', { name: '查看 t1' }))
  const detail = await screen.findByRole('region', { name: 'Trace 详情' })
  expect(within(detail).getByRole('heading', { name: /INTENT_CLASSIFIED/ })).toBeInTheDocument()
  fireEvent.click(within(detail).getByText('输入'))
  expect(within(detail).getByText(/"text": "晚饭"/)).toBeInTheDocument()
})

test('chat trace deep link loads its detail even outside the current list', async () => {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    requests.push(String(input))
    if (String(input).includes('/debug/traces/deep-id')) return reply(trace('deep-id'))
    return reply([])
  }))
  mount('/admin/traces?traceId=deep-id')
  expect(await screen.findByRole('region', { name: 'Trace 详情' })).toHaveTextContent('deep-id')
  expect(requests.some(url => url.endsWith('/debug/traces/deep-id'))).toBe(true)
})

test('rejects malformed slot labels and saves a valid object with slot arrays', async () => {
  const payloads: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const url = String(input)
    if (init?.method === 'PUT') { payloads.push(JSON.parse(String(init.body))); return reply(null, 204) }
    if (url.includes('/debug/traces/t1')) return reply(trace('t1'))
    return reply([trace('t1')])
  }))
  mount('/admin/traces?traceId=t1')
  const slots = await screen.findByLabelText('预期槽位 JSON')
  fireEvent.change(slots, { target: { value: '{"mealTime":"晚餐"}' } })
  fireEvent.click(screen.getByRole('button', { name: '保存标注' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('数组')
  expect(payloads).toHaveLength(0)
  fireEvent.change(slots, { target: { value: '{"mealTime":["晚餐"],"taste":["清淡"]}' } })
  fireEvent.change(screen.getByLabelText('预期意图'), { target: { value: 'MEAL_RECOMMENDATION' } })
  fireEvent.change(screen.getByLabelText('澄清动作'), { target: { value: 'READY' } })
  fireEvent.change(screen.getByLabelText('备注'), { target: { value: '人工核对' } })
  fireEvent.click(screen.getByRole('button', { name: '保存标注' }))
  await waitFor(() => expect(payloads).toEqual([{ expectedIntent: 'MEAL_RECOMMENDATION',
    expectedSlots: { mealTime: ['晚餐'], taste: ['清淡'] }, expectedClarifyAction: 'READY', labelNote: '人工核对' }]))
})

test.each([true, false])('reopens saved slots with backend empty=%s and saves a changed note', async empty => {
  const savedSlots = { mealTime: empty ? [] : ['晚餐'], mood: [], scene: [], healthGoal: [],
    cuisine: [], taste: [], convenience: [], empty }
  const payloads: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    if (init?.method === 'PUT') { payloads.push(JSON.parse(String(init.body))); return reply(null, 204) }
    const saved = trace('t1', { expectedIntent: 'MEAL_RECOMMENDATION',
      expectedSlots: JSON.stringify(savedSlots), expectedClarifyAction: 'READY', labelNote: '原备注' })
    return reply(String(input).endsWith('/debug/traces/t1') ? saved : [saved])
  }))
  mount('/admin/traces?traceId=t1')
  const slots = await screen.findByLabelText('预期槽位 JSON')
  expect(JSON.parse((slots as HTMLTextAreaElement).value)).toEqual(savedSlots)
  fireEvent.change(screen.getByLabelText('备注'), { target: { value: '重新核对' } })
  fireEvent.click(screen.getByRole('button', { name: '保存标注' }))
  expect(await screen.findByText('标注已保存')).toBeInTheDocument()
  expect(payloads).toEqual([{ expectedIntent: 'MEAL_RECOMMENDATION', expectedSlots: {
    mealTime: empty ? [] : ['晚餐'], mood: [], scene: [], healthGoal: [], cuisine: [], taste: [], convenience: [],
  }, expectedClarifyAction: 'READY', labelNote: '重新核对' }])
})

test.each([
  { empty: 'false' },
  { empty: null },
  { empty: [] },
  { empty: 0 },
  { empty: true, unknown: [] },
])('blocks malformed computed fields and unknown slots: %j', async invalidSlots => {
  const payloads: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    if (init?.method === 'PUT') { payloads.push(JSON.parse(String(init.body))); return reply(null, 204) }
    return reply(String(input).endsWith('/debug/traces/t1') ? trace('t1') : [trace('t1')])
  }))
  mount('/admin/traces?traceId=t1')
  fireEvent.change(await screen.findByLabelText('预期槽位 JSON'), {
    target: { value: JSON.stringify(invalidSlots) },
  })
  fireEvent.click(screen.getByRole('button', { name: '保存标注' }))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(payloads).toHaveLength(0)
})

test('a late detail request cannot replace a newer selected trace', async () => {
  let resolveFirst!: (value: Response) => void
  const first = new Promise<Response>(resolve => { resolveFirst = resolve })
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = String(input)
    if (url.endsWith('/debug/traces/t1')) return first
    if (url.endsWith('/debug/traces/t2')) return reply(trace('t2', { sessionId: 'new-session' }))
    return reply([trace('t1'), trace('t2')])
  }))
  mount()
  const table = await screen.findByRole('table', { name: 'Trace 列表' })
  fireEvent.click(within(table).getByRole('button', { name: '查看 t1' }))
  fireEvent.click(within(table).getByRole('button', { name: '查看 t2' }))
  expect(await screen.findByRole('region', { name: 'Trace 详情' })).toHaveTextContent('new-session')
  await act(async () => { resolveFirst(reply(trace('t1'))); await first })
  expect(screen.getByRole('region', { name: 'Trace 详情' })).toHaveTextContent('t2')
  expect(screen.getByRole('region', { name: 'Trace 详情' })).not.toHaveTextContent('t1')
})

test('switching users discards a list response from the previous user', async () => {
  let resolveOld!: (value: Response) => void
  const old = new Promise<Response>(resolve => { resolveOld = resolve })
  vi.stubGlobal('fetch', vi.fn(async (_input: string, init: RequestInit) => {
    const userId = new Headers(init.headers).get('X-User-Id')
    return userId === '1' ? old : reply([trace('user-two')])
  }))
  function SwitchUser() {
    const { setUserId } = useApp()
    return <button onClick={() => setUserId('2')}>切换测试用户</button>
  }
  render(<AppProvider><SwitchUser /><MemoryRouter initialEntries={['/admin/traces']}><Routes>
    <Route path="/admin/traces" element={<TracePage />} />
  </Routes></MemoryRouter></AppProvider>)
  fireEvent.click(screen.getByRole('button', { name: '切换测试用户' }))
  expect(await screen.findByRole('table', { name: 'Trace 列表' })).toHaveTextContent('user-two')
  await act(async () => { resolveOld(reply([trace('old-user')])); await old })
  expect(screen.getByRole('table', { name: 'Trace 列表' })).not.toHaveTextContent('old-user')
})
