import type {
  ChatRequest, ChatResponse, CreateSessionResponse, EvaluationReport, EvaluationRequest,
  FeedbackRequest, Meal, MealDraft, MealRequest, SlotOptions, Trace, TraceFilters,
  TraceLabelRequest,
} from './types'

const BASE = '/api/v1/diet'

function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const values = new URLSearchParams()
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') values.set(name, String(value))
  }
  const encoded = values.toString()
  return encoded ? `?${encoded}` : ''
}

async function responseError(response: Response): Promise<string> {
  const body = await response.text()
  if (!body) return `请求失败：${response.status}`
  try {
    const parsed: unknown = JSON.parse(body)
    if (parsed && typeof parsed === 'object') {
      const fields = parsed as Record<string, unknown>
      if (typeof fields.message === 'string' && fields.message) return fields.message
      if (typeof fields.error === 'string' && fields.error) return fields.error
    }
  } catch {
    // Plain text errors are useful as returned by the server.
  }
  return body
}

/** Creates a request client bound to one user, so later identity changes cannot redirect pending work. */
export function createApi(userId: string) {
  async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const headers = new Headers({ 'X-User-Id': userId })
    if (body !== undefined) headers.set('Content-Type', 'application/json')
    const response = await fetch(`${BASE}${path}`, {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    if (!response.ok) throw new Error(await responseError(response))
    if (response.status === 204) return null as T
    const text = await response.text()
    if (!text) return null as T
    try {
      return JSON.parse(text) as T
    } catch {
      return text as T
    }
  }

  return {
    createSession: () => request<CreateSessionResponse>('/sessions', 'POST'),
    chat: (payload: ChatRequest) => request<ChatResponse>('/chat', 'POST', payload),
    listPersonalMeals: () => request<Meal[]>('/meals/personal'),
    generateMealDraft: (text: string) => request<MealDraft>('/meals/personal/draft', 'POST', { text }),
    createPersonalMeal: (payload: MealRequest) => request<Meal>('/meals/personal', 'POST', payload),
    updatePersonalMeal: (mealId: number | string, payload: MealRequest) => request<Meal>(`/meals/personal/${encodeURIComponent(mealId)}`, 'PUT', payload),
    deletePersonalMeal: (mealId: number | string) => request<null>(`/meals/personal/${encodeURIComponent(mealId)}`, 'DELETE'),
    listPublicMeals: () => request<Meal[]>('/meals/public'),
    slotOptions: () => request<SlotOptions>('/slot-options'),
    saveFeedback: (payload: FeedbackRequest) => request<null>('/feedback', 'POST', payload),
    listTraces: (params: TraceFilters) => request<Trace[]>(`/debug/traces${query({ ...params })}`),
    getTrace: (traceId: string) => request<Trace>(`/debug/traces/${encodeURIComponent(traceId)}`),
    listSessionTraces: (sessionId: string, limit?: number) => request<Trace[]>(`/debug/sessions/${encodeURIComponent(sessionId)}/traces${query({ limit })}`),
    labelTrace: (traceId: string, payload: TraceLabelRequest) => request<null>(`/debug/traces/${encodeURIComponent(traceId)}/label`, 'PUT', payload),
    evaluate: (payload: EvaluationRequest) => request<EvaluationReport>('/evaluations', 'POST', payload),
  }
}

export type Api = ReturnType<typeof createApi>
