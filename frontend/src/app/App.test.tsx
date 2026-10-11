import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { App } from './App';

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '#/diet/chat';
  vi.stubGlobal('fetch', vi.fn(async () => new Response('[]', { status: 200 })));
});

test('navigation exposes all existing functional pages and mobile menu closes after navigation', async () => {
  render(<App />);
  fireEvent.click(screen.getByRole('button', { name: '打开导航' }));
  fireEvent.click(screen.getByRole('link', { name: '个人餐食' }));
  expect(await screen.findByRole('heading', { name: '个人餐食' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '打开导航' })).toHaveAttribute('aria-expanded', 'false');
  expect(screen.getByRole('link', { name: '公共餐食' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Trace 调试' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '意图评测' })).toBeInTheDocument();
});

test('identity switch updates captured API identity and resets chat', async () => {
  render(<App />);
  const field = screen.getByLabelText('当前用户编号');
  fireEvent.change(field, { target: { value: '7' } });
  fireEvent.click(screen.getByRole('button', { name: '切换用户' }));
  expect(localStorage.getItem('diet.userId')).toBe('7');
  expect(screen.getByLabelText('当前用户编号')).toHaveValue('7');
});

test('visiting the meal library and returning keeps the current conversation', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(
    url.endsWith('/sessions') ? { sessionId: 'kept-session' } : url.endsWith('/chat')
      ? { sessionId: 'kept-session', speechText: '这条回答应保留', displayBlocks: [], planBlocks: [], videoBlocks: [] }
      : []), { status: 200 })));
  render(<App />);
  fireEvent.change(screen.getByRole('textbox', { name: '消息' }), { target: { value: '晚餐推荐' } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
  expect(await screen.findByText('这条回答应保留')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('link', { name: '公共餐食' }));
  expect(await screen.findByRole('heading', { name: '公共餐食' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('link', { name: '聊天助手' }));
  expect(await screen.findByText('这条回答应保留')).toBeInTheDocument();
});

test('switching to the public library discards a pending personal-library response', async () => {
  window.location.hash = '#/diet/meals/personal';
  let finishPersonal!: (response: Response) => void;
  const personal = new Promise<Response>(resolve => { finishPersonal = resolve; });
  const meal = { id: 2, name: '公共面', sourceType: 'PUBLIC', mealTime: ['午餐'], mood: [], scene: [], healthGoal: [], cuisine: [], taste: [], convenience: [], knowledge: null };
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/meals/personal') ? personal : new Response(JSON.stringify(url.endsWith('/meals/public') ? [meal] : {}), { status: 200 })));
  render(<App />);
  fireEvent.click(screen.getByRole('link', { name: '公共餐食' }));
  expect(await screen.findByText('公共面')).toBeInTheDocument();
  finishPersonal(new Response(JSON.stringify([{ ...meal, id: 1, name: '私有面', sourceType: 'PERSONAL' }]), { status: 200 }));
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(screen.queryByText('私有面')).not.toBeInTheDocument();
  expect(screen.getByText('公共面')).toBeInTheDocument();
});

test('keeps the app inside the usable visual viewport when a mobile keyboard opens', () => {
  const viewport = Object.assign(new EventTarget(), { height: 800, offsetTop: 0, scale: 1 });
  vi.stubGlobal('visualViewport', viewport);
  const { container } = render(<App />);
  act(() => { viewport.height = 420; viewport.offsetTop = 12; viewport.dispatchEvent(new Event('resize')); });
  expect(container.querySelector('.app-shell')).toHaveStyle({ height: '420px', top: '12px' });
  act(() => { viewport.height = 800; viewport.offsetTop = 0; viewport.dispatchEvent(new Event('resize')); });
  expect(container.querySelector('.app-shell')).toHaveStyle({ height: '800px', top: '0px' });
});
