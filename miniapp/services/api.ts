import { clearAuth, getAuth, getStoredAuth, login } from './auth'
import { callBackend } from './transport'

let nextConsent: string | null = null
export function authorizeNextDiagnostic(token: string): void { nextConsent = token }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}

async function send<T>(path: string, method: string, body: unknown, token: string,
                       diagnostic: string | null): Promise<T> {
  let result: { statusCode: number; data: unknown }
  try {
    result = await callBackend(path, method, body, {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...(diagnostic ? { 'X-Diet-Diagnostic-Consent': diagnostic } : {}),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '网络暂时不可用，请稍后重试')
  }
  if (result.statusCode >= 200 && result.statusCode < 300) return result.data as T
  const error = result.data as { code?: string; message?: string }
  throw new ApiError(result.statusCode, error?.code || 'REQUEST_FAILED',
    error?.message || `请求失败（${result.statusCode}）`)
}

export async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  let current = getAuth()
  if (!current) {
    const cached = getStoredAuth()
    if (!cached) throw new ApiError(401, 'UNAUTHENTICATED', '请先登录')
    current = await login()
    if (current.user.id !== cached.user.id) {
      clearAuth()
      throw new ApiError(401, 'ACCOUNT_CHANGED', '账号已切换，请重新打开页面')
    }
  }
  const traceRequest = path === '/api/v1/diet/chat' ||
    path === '/api/v1/diet/meals/personal/draft'
  const diagnostic = traceRequest ? nextConsent : null
  if (traceRequest) nextConsent = null
  try {
    return await send<T>(path, method, body, current.token, diagnostic)
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
    const updated = await login()
    if (updated.user.id !== current.user.id) {
      clearAuth()
      throw new ApiError(401, 'ACCOUNT_CHANGED', '账号已切换，请重新打开页面')
    }
    return send<T>(path, method, body, updated.token, diagnostic)
  }
}

export const api = {
  me: () => request<{ id: number; displayName: string | null; role: string }>('/api/v1/me'),
  rename: (displayName: string) => request('/api/v1/me', 'PATCH', { displayName }),
  deleteAccount: () => request('/api/v1/me', 'DELETE'),
  logout: () => request('/api/v1/auth/logout', 'POST'),
  chat: (body: unknown) => request<ChatResponse>('/api/v1/diet/chat', 'POST', body),
  personalMeals: () => request<Meal[]>('/api/v1/diet/meals/personal'),
  publicMeals: () => request<Meal[]>('/api/v1/diet/meals/public'),
  createMeal: (body: MealInput) => request<Meal>('/api/v1/diet/meals/personal', 'POST', body),
  updateMeal: (id: number, body: MealInput) => request<Meal>(`/api/v1/diet/meals/personal/${id}`, 'PUT', body),
  deleteMeal: (id: number) => request(`/api/v1/diet/meals/personal/${id}`, 'DELETE'),
  consent: () => request<{ id: string; token: string; expiresAt: string }>('/api/v1/diagnostics/consents', 'POST'),
  createInvitation: () => request<{ invitationCode: string }>('/api/v1/admin/invitations', 'POST'),
}

export interface Meal {
  id: number; name: string; sourceType: 'PUBLIC' | 'PERSONAL';
  mealTime: string[]; mood: string[]; scene: string[]; healthGoal: string[];
  cuisine: string[]; taste: string[]; convenience: string[]
}
export type MealInput = Omit<Meal, 'id' | 'sourceType'>
export interface ChatResponse {
  sessionId: string; traceId: string; speechText: string;
  displayBlocks: Meal[]; planBlocks?: { mealTime: string; summary: string; meals: Meal[] }[];
  videoBlocks?: { title: string; url: string }[];
  mealPlan?: { status: string; days: { date: string; blocks: { mealTime: string;
    selectedMeal: { name: string } | null; reason: string }[] }[] };
  clarifyQuestion?: string; responseType: string
}
