import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { App } from '../../app/App'

const item = { id: 'm1', userId: 1, type: 'ALLERGEN', value: '花生', stance: 'NEUTRAL', status: 'PENDING',
  sourceType: 'EXPLICIT', evidence: '我对花生过敏', sourceMessageId: 10, lastJobId: 2, version: 3,
  expiresAt: null, updatedAt: 1792454400000 }
const inferred = { ...item, id: 'm2', type: 'TASTE', value: '清淡', stance: 'LIKE', status: 'ACTIVE',
  sourceType: 'INFERRED', evidence: '最近几次都想吃清淡的' }
const snapshot = { enabled: true, collectionEnabled: true, items: [item, inferred] }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
let fetcher: ReturnType<typeof vi.fn>

function switchUser() {
  fireEvent.change(screen.getByLabelText('当前用户编号'), { target: { value: '2' } })
  fireEvent.click(screen.getByRole('button', { name: '切换用户' }))
}

beforeEach(() => {
  localStorage.clear()
  window.location.hash = '#/diet/memories'
  fetcher = vi.fn(async () => json(snapshot))
  vi.stubGlobal('fetch', fetcher)
})

test('memory navigation lists pending facts, inferred preferences and original evidence safely', async () => {
  fetcher.mockResolvedValue(json({ ...snapshot, items: [item, { ...inferred, evidence: '<img src=x onerror=alert(1)>' }] }))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '饮食记忆' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '饮食记忆' })).toHaveAttribute('href', '#/diet/memories')
  const allergy = await screen.findByRole('article', { name: '花生' })
  expect(allergy).toHaveTextContent('过敏原')
  expect(allergy).toHaveTextContent('待确认')
  expect(allergy).toHaveTextContent('本人明确表达')
  expect(allergy).toHaveTextContent('我对花生过敏')
  const taste = screen.getByRole('article', { name: '清淡' })
  expect(taste).toHaveTextContent('推断偏好')
  expect(taste).toHaveTextContent('<img src=x onerror=alert(1)>')
  expect(taste.querySelector('img')).toBeNull()
})

test('confirms pending allergy with its current version and updates the displayed status', async () => {
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PATCH'
    ? json({ ...item, status: 'ACTIVE', version: 4 }) : json(snapshot))
  render(<App />)
  const card = await screen.findByRole('article', { name: '花生' })
  fireEvent.click(within(card).getByRole('button', { name: '确认长期记忆' }))
  await waitFor(() => expect(card).toHaveTextContent('已生效'))
  expect(fetcher.mock.calls.find(([, init]) => init.method === 'PATCH')?.[1].body).toBe('{"version":3,"action":"CONFIRM"}')
})

test('editing allergy requires explicit save, validates empty values and sends only allowed fields', async () => {
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PATCH'
    ? json({ ...item, value: '虾', status: 'ACTIVE', version: 4 }) : json(snapshot))
  render(<App />)
  const card = await screen.findByRole('article', { name: '花生' })
  fireEvent.click(within(card).getByRole('button', { name: '编辑' }))
  const field = screen.getByLabelText('记忆内容')
  fireEvent.change(field, { target: { value: ' ' } })
  fireEvent.click(screen.getByRole('button', { name: '确认并保存修改' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('请输入记忆内容')
  expect(fetcher.mock.calls.filter(([, init]) => init.method === 'PATCH')).toHaveLength(0)
  fireEvent.change(field, { target: { value: '虾' } })
  expect(fetcher.mock.calls.filter(([, init]) => init.method === 'PATCH')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: '确认并保存修改' }))
  expect(await screen.findByRole('article', { name: '虾' })).toHaveTextContent('已生效')
  expect(fetcher.mock.calls.find(([, init]) => init.method === 'PATCH')?.[1].body).toBe('{"version":3,"action":"UPDATE","value":"虾","stance":"NEUTRAL"}')
})

test('revocation requires confirmation and removes only the selected memory', async () => {
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PATCH'
    ? json({ ...item, status: 'DELETED', version: 4 }) : json(snapshot))
  render(<App />)
  const card = await screen.findByRole('article', { name: '花生' })
  fireEvent.click(within(card).getByRole('button', { name: '撤销' }))
  expect(fetcher.mock.calls.filter(([, init]) => init.method === 'PATCH')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: '取消撤销' }))
  expect(screen.getByRole('article', { name: '花生' })).toBeInTheDocument()
  fireEvent.click(within(card).getByRole('button', { name: '撤销' }))
  fireEvent.click(screen.getByRole('button', { name: '确认撤销' }))
  await waitFor(() => expect(screen.queryByRole('article', { name: '花生' })).not.toBeInTheDocument())
  expect(screen.getByRole('article', { name: '清淡' })).toBeInTheDocument()
  expect(fetcher.mock.calls.find(([, init]) => init.method === 'PATCH')?.[1].body).toBe('{"version":3,"action":"DELETE"}')
})

test('collection toggle keeps existing records and changes only after successful response', async () => {
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PUT'
    ? json({ ...snapshot, collectionEnabled: false }) : json(snapshot))
  render(<App />)
  const toggle = await screen.findByRole('checkbox', { name: '收集新的饮食记忆' })
  expect(toggle).toBeChecked()
  fireEvent.click(toggle)
  await waitFor(() => expect(toggle).not.toBeChecked())
  expect(screen.getByRole('article', { name: '花生' })).toBeInTheDocument()
  expect(screen.getByText(/关闭收集不会删除已有记忆/)).toBeInTheDocument()
})

test('mutation failure preserves the fact and allows a retry without claiming success', async () => {
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PATCH'
    ? json({ message: '版本已更新，请刷新后重试' }, 409) : json(snapshot))
  render(<App />)
  const card = await screen.findByRole('article', { name: '花生' })
  fireEvent.click(within(card).getByRole('button', { name: '确认长期记忆' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('版本已更新')
  expect(card).toHaveTextContent('待确认')
  expect(within(card).getByRole('button', { name: '确认长期记忆' })).toBeEnabled()
})

test('load failure can be retried and disabled feature has a plain unavailable state', async () => {
  fetcher.mockResolvedValueOnce(json({ message: '暂时无法加载' }, 503)).mockImplementation(async () => json({ enabled: false, collectionEnabled: false, items: [] }))
  render(<App />)
  expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法加载')
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }))
  expect(await screen.findByText('饮食记忆暂未启用')).toBeInTheDocument()
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByText(/memory.enabled/)).not.toBeInTheDocument()
})

test('new user gets empty state and late prior-user list cannot overwrite it', async () => {
  let finish!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { finish = resolve })
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => new Headers(init.headers).get('X-User-Id') === '1'
    ? pending : json({ ...snapshot, items: [] }))
  render(<App />)
  switchUser()
  expect(await screen.findByText('还没有饮食记忆')).toBeInTheDocument()
  await act(async () => { finish(json(snapshot)); await pending })
  expect(screen.queryByRole('article', { name: '花生' })).not.toBeInTheDocument()
})

test('late prior-user mutation error does not appear in the new user page', async () => {
  let finish!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { finish = resolve })
  fetcher.mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PATCH' ? pending
    : json({ ...snapshot, items: new Headers(init.headers).get('X-User-Id') === '1' ? [item] : [] }))
  render(<App />)
  const card = await screen.findByRole('article', { name: '花生' })
  fireEvent.click(within(card).getByRole('button', { name: '确认长期记忆' }))
  switchUser()
  expect(await screen.findByText('还没有饮食记忆')).toBeInTheDocument()
  await act(async () => { finish(json({ message: '旧用户失败' }, 409)); await pending })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
