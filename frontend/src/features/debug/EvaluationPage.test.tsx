import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { AppProvider } from '../../context/AppContext'
import EvaluationPage from './EvaluationPage'

beforeEach(() => { localStorage.clear(); vi.unstubAllGlobals() })

test('submits the evaluation options and shows averages and trace detail JSON', async () => {
  const requests: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (_input: string, init: RequestInit) => {
    requests.push(JSON.parse(String(init.body)))
    return new Response(JSON.stringify({ startAt: '2026-10-10T09:00:00', endAt: '2026-10-11T09:00:00',
      totalTraces: 2, labeledTraces: 1, avgScore: 0.75, metricAverages: { intentAccuracy: 0.8 },
      traceResults: [{ traceId: 't1', sessionId: 's1', createdAt: '2026-10-11T08:00:00', score: 0.75,
        ruleScore: 0.7, llmJudgeScore: 0.9, userFeedbackScore: null,
        metrics: { intentAccuracy: 1 }, detail: { expectedIntent: 'MEAL_RECOMMENDATION' } }] }), { status: 200 })
  }))
  render(<AppProvider><EvaluationPage /></AppProvider>)
  fireEvent.change(screen.getByLabelText('开始时间'), { target: { value: '2026-10-10T09:00' } })
  fireEvent.change(screen.getByLabelText('结束时间'), { target: { value: '2026-10-11T09:00' } })
  fireEvent.change(screen.getByLabelText('数量上限'), { target: { value: '17' } })
  fireEvent.change(screen.getByLabelText('LLM Judge'), { target: { value: 'true' } })
  fireEvent.click(screen.getByRole('button', { name: '生成评估报告' }))
  await waitFor(() => expect(requests).toEqual([{ startAt: '2026-10-10T09:00', endAt: '2026-10-11T09:00',
    limit: 17, includeLlmJudge: true }]))
  expect((await screen.findAllByText('0.75')).length).toBe(2)
  expect(screen.getByText(/intentAccuracy：0.80/)).toBeInTheDocument()
  const row = within(screen.getByRole('table', { name: '评测明细' })).getByRole('row', { name: /t1/ })
  fireEvent.click(within(row).getByText('查看 JSON'))
  expect(within(row).getByText(/"expectedIntent": "MEAL_RECOMMENDATION"/)).toBeInTheDocument()
})

test('a failed evaluation keeps the form and can retry', async () => {
  let calls = 0
  vi.stubGlobal('fetch', vi.fn(async () => {
    calls++
    if (calls === 1) return new Response(JSON.stringify({ message: '服务暂不可用' }), { status: 503 })
    return new Response(JSON.stringify({ startAt: '2026-10-10T09:00:00', endAt: '2026-10-11T09:00:00',
      totalTraces: 0, labeledTraces: 0, avgScore: null, metricAverages: {}, traceResults: [] }), { status: 200 })
  }))
  render(<AppProvider><EvaluationPage /></AppProvider>)
  fireEvent.change(screen.getByLabelText('数量上限'), { target: { value: '9' } })
  fireEvent.click(screen.getByRole('button', { name: '生成评估报告' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用')
  expect(screen.getByLabelText('数量上限')).toHaveValue(9)
  fireEvent.click(screen.getByRole('button', { name: '生成评估报告' }))
  await waitFor(() => expect(calls).toBe(2))
  expect(await screen.findByText('暂无 Trace 明细')).toBeInTheDocument()
})
