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
  mergeMeal: (body: MealInput) => request<Meal>('/api/v1/diet/meals/personal/merge', 'POST', body),
  updateMeal: (id: number, body: MealInput) => request<Meal>(`/api/v1/diet/meals/personal/${id}`, 'PUT', body),
  deleteMeal: (id: number) => request(`/api/v1/diet/meals/personal/${id}`, 'DELETE'),
  slotOptions: () => request<Record<string, string[]>>('/api/v1/diet/slot-options'),
  generateMealDraft: (text: string) => request<MealDraft>('/api/v1/diet/meals/personal/draft', 'POST', { text }),
  suggestNutrition: (name: string, text: string) => request<NutritionDraft>('/api/v1/diet/meals/nutrition/suggest','POST',{ name,text }),
  previewNutrition: (templateCode: string, selections: Record<string,string>) =>
    request<NutritionEstimate>('/api/v1/diet/meals/nutrition/preview','POST',{ templateCode,selections }),
  personalVariants: (id: number) => request<NutritionVariant[]>(`/api/v1/diet/meals/personal/${id}/variants`),
  publicVariants: (id: number) => request<NutritionVariant[]>(`/api/v1/diet/meals/public/${id}/variants`),
  saveVariant: (id: number, variant: NutritionVariantInput) =>
    request<NutritionVariant>(`/api/v1/diet/meals/personal/${id}/variants`,'POST',variant),
  deleteVariant: (id: number, variantId: number) =>
    request(`/api/v1/diet/meals/personal/${id}/variants/${variantId}`,'DELETE'),
  dietaryProfile: () => request<DietaryProfile>('/api/v1/diet/profile'),
  saveDietaryProfile: (body: DietaryProfile) => request<DietaryProfile>('/api/v1/diet/profile', 'PUT', body),
  skipDietaryProfile: () => request<DietaryProfile>('/api/v1/diet/profile/skip', 'POST'),
  consent: () => request<{ id: string; token: string; expiresAt: string }>('/api/v1/diagnostics/consents', 'POST'),
  createInvitation: () => request<{ invitationCode: string }>('/api/v1/admin/invitations', 'POST'),
}

export interface Meal {
  id: number; name: string; sourceType: 'PUBLIC' | 'PERSONAL';
  mealTime: string[]; mood: string[]; scene: string[]; healthGoal: string[];
  cuisine: string[]; taste: string[]; convenience: string[];
  nutritionVariant?: NutritionVariant | null
}
export type MealInput = Omit<Meal, 'id' | 'sourceType' | 'nutritionVariant'> & { nutritionVariant?: NutritionVariantInput }
export interface NutritionEstimate {
  kcalLow: number; kcalHigh: number; carbsLow: number; carbsHigh: number;
  proteinLow: number; proteinHigh: number; fatLow: number; fatHigh: number;
  fiberLow: number; fiberHigh: number; method: string; assumptions: string[]
}
export interface NutritionVariantInput { label: string; templateCode: string; selections: Record<string,string> }
export interface NutritionVariant extends NutritionVariantInput { id: number; mealId: number; estimate: NutritionEstimate }
export interface NutritionDraft { templateCode: string; questions: {
  key: string; label: string; options: { value: string; label: string }[] }[];
  selections: Record<string,string>; estimate: NutritionEstimate | null }
export interface DietaryProfile {
  allergens: string[]; avoidedFoods: string[]; dislikedFoods: string[];
  spicePreference: 'UNSET' | 'NEVER' | 'MILD' | 'OK';
  spiceNote: string; onboardingStatus: 'NEW' | 'COMPLETED' | 'SKIPPED'
}
export interface ChatResponse {
  sessionId: string; traceId: string; speechText: string;
  displayBlocks: Meal[]; planBlocks?: { mealTime: string; summary: string; meals: Meal[] }[];
  videoBlocks?: { title: string; url: string }[];
  mealPlan?: { status: string; days: { date: string; blocks: { mealTime: string;
    selectedMeal: { name: string } | null; reason: string }[] }[] };
  clarifyQuestion?: string; responseType: string; mealDraft?: MealDraft | null
}

export interface MealDraft {
  draftId: string; originalText: string; status: 'READY' | 'NEEDS_INPUT' | 'AWAITING_MERGE' | 'SAVED' | 'CANCELLED';
  savedMealId?: number | null; missingFields: string[]; warnings: string[];
  data: MealInput & { knowledge?: { ingredients?: string[]; description?: string; preparation?: string } }
}
