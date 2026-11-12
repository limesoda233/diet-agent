/** JSON shapes from the Spring Boot /api/v1/diet controllers. */
export type SourceMode = 'PERSONAL' | 'PUBLIC'
export type SlotKey = 'mealTime' | 'mood' | 'scene' | 'healthGoal' | 'cuisine' | 'taste' | 'convenience'
export type Slots = Record<SlotKey, string[]>
export type SlotOptions = Record<string, string[]>

export interface MealKnowledge {
  ingredients: string[]
  description: string
  preparation: string
  sourceType: 'USER_PROVIDED' | 'IMPORTED' | 'SYNTHETIC_EVALUATION'
  sourceReference: string
}

export interface Meal extends Slots {
  id: number
  sourceType: SourceMode
  name: string
  matchScore: number
  knowledge: MealKnowledge | null
}

/** Omitting knowledge preserves stored knowledge on edit; null explicitly clears it. */
export interface MealRequest extends Slots {
  name: string
  knowledge?: MealKnowledge | null
}

export interface MealDraftData extends Slots {
  name: string
  knowledge: MealKnowledge | null
}

export interface MealDraft {
  draftId: string
  originalText: string
  data: MealDraftData
  missingFields: string[]
  warnings: string[]
  evidence: Record<string, string[]>
  status: 'NEEDS_INPUT' | 'READY' | 'AWAITING_MERGE' | 'SAVED' | 'CANCELLED'
  savedMealId: number | null
}

export interface DietaryProfile {
  allergens: string[]
  avoidedFoods: string[]
  dislikedFoods: string[]
  spicePreference: 'UNSET' | 'NEVER' | 'MILD' | 'OK'
  spiceNote: string
  onboardingStatus: 'NEW' | 'COMPLETED' | 'SKIPPED'
}

export interface ChatRequest {
  sessionId: string | null
  message: string
  sourceMode: SourceMode
  context: Record<string, unknown>
}

export interface MealPlanBlock {
  mealTime: string
  summary: string
  meals: Meal[]
}

export interface PlanningCandidate {
  mealId: number
  sourceMode: SourceMode
  ownerUserId: number | null
  name: string
  mealTimes: string[]
  ingredients: string[]
  ingredientsKnown: boolean
  tags: Record<string, string[]>
  facts: Record<string, string>
  fingerprint: string
}

export interface PlanningBlock {
  taskId: string
  mealTime: string
  selectedMeal: PlanningCandidate | null
  reason: string
  repeated: boolean
}

export interface MealPlanningResult {
  planId: string | null
  version: number
  status: 'COMPLETE' | 'PARTIAL' | 'DEGRADED' | 'NEEDS_INPUT' | 'UNAVAILABLE'
  strategy: string
  reviewed: boolean
  days: { date: string; blocks: PlanningBlock[] }[]
  gaps: { taskId: string; code: string }[]
  unsupportedConstraints: string[]
  warnings: string[]
}

export interface VideoResponse {
  title: string
  url: string
  platform: string
  creator: string
  durationSeconds: number | null
}

export interface ChatResponse {
  sessionId: string
  traceId: string | null
  responseType: 'ANSWER' | 'PLAN' | 'VIDEOS' | 'CLARIFY' | 'MEAL_DRAFT'
  speechText: string
  displayBlocks: Meal[]
  planBlocks: MealPlanBlock[]
  videoBlocks: VideoResponse[]
  nextAction: string
  clarifyQuestion: string | null
  missingSlots: string[]
  mealDraft: MealDraft | null
  mealPlan?: MealPlanningResult | null
  memoryNotices?: string[]
}

export type MemoryType = 'ALLERGEN' | 'AVOID_INGREDIENT' | 'TASTE' | 'CUISINE' | 'EQUIPMENT' | 'MAX_MINUTES'
export type MemoryStance = 'LIKE' | 'DISLIKE' | 'NEUTRAL'
export interface MemoryItem {
  id: string
  userId: number
  type: MemoryType
  value: string
  stance: MemoryStance
  status: 'PENDING' | 'ACTIVE' | 'DELETED'
  sourceType: 'EXPLICIT' | 'INFERRED'
  evidence: string
  sourceMessageId: number | null
  lastJobId: number
  version: number
  expiresAt: number | null
  updatedAt: number
}
export interface MemorySnapshot {
  enabled: boolean
  collectionEnabled: boolean
  items: MemoryItem[]
}
export type MemoryPatch = { version: number } & (
  { action: 'CONFIRM' | 'DELETE' } | { action: 'UPDATE'; value: string; stance: MemoryStance }
)

export interface CreateSessionResponse {
  sessionId: string
}

export interface FeedbackRequest {
  sessionId: string
  traceId?: string
  itemId: number
  action: string
  rating?: number | null
  reason?: string | null
  planId?: string
  planVersion?: number
  taskId?: string
}

export type Intent = 'MEAL_RECOMMENDATION' | 'CLARIFY_NEEDED' | 'MEAL_ADJUST' | 'MEAL_PLAN' | 'RECIPE_QUERY' | 'HEALTH_RISK' | 'OTHER'
export type ClarifyAction = 'ASK' | 'READY'

export interface Trace {
  id: number
  traceId: string
  sessionId: string
  userId: number
  status: string
  eventCount: number
  durationMs: number | null
  errorMessage: string | null
  traceJson: string | null
  expectedIntent: Intent | null
  expectedSlots: string | null
  expectedClarifyAction: ClarifyAction | null
  labeledBy: number | null
  labeledAt: string | null
  labelNote: string | null
  createdAt: string
  updatedAt: string
}

export interface TraceFilters {
  startAt: string
  endAt: string
  onlyUnlabeled?: boolean
  limit?: number
}

export interface TraceLabelRequest {
  expectedIntent: Intent | null
  expectedSlots: Partial<Slots> | null
  expectedClarifyAction: ClarifyAction | null
  labelNote: string
}

export interface EvaluationRequest {
  startAt: string
  endAt: string
  includeLlmJudge?: boolean
  limit?: number
}

export interface TraceEvaluationResult {
  traceId: string
  sessionId: string
  createdAt: string
  score: number | null
  ruleScore: number | null
  llmJudgeScore: number | null
  userFeedbackScore: number | null
  metrics: Record<string, number>
  detail: Record<string, unknown>
}

export interface EvaluationReport {
  startAt: string
  endAt: string
  totalTraces: number
  labeledTraces: number
  avgScore: number | null
  metricAverages: Record<string, number>
  traceResults: TraceEvaluationResult[]
}
