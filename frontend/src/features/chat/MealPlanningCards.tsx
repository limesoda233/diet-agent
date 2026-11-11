import type { ReactNode } from 'react'
import type { Meal, MealPlanningResult, PlanningCandidate } from '../../api/types'

const statusLabels: Record<MealPlanningResult['status'], string> = {
  COMPLETE: '计划已完成', PARTIAL: '计划尚有缺口', DEGRADED: '计划以降级方式生成',
  NEEDS_INPUT: '需要补充或调整规划要求', UNAVAILABLE: '暂时无法生成计划',
}
const strategyLabels: Record<string, string> = {
  MULTI_AGENT: '由规划、配餐和复核流程生成',
  DETERMINISTIC_SPEC: '使用确定性任务分解',
  DETERMINISTIC_MENU: '使用确定性备用选择',
}
const notices: Record<string, string> = {
  NO_DISTINCT_CANDIDATE: '暂无符合不重复要求的餐食',
  NO_MATCH: '暂无符合要求的餐食', RETRIEVAL_FAILED: '该餐次检索暂时失败，请稍后重试',
  REVIEW_UNRESOLVED: '该餐次仍有未解决的需求，请调整要求后重新规划',
  REVIEW_UNAVAILABLE: '需求复核暂时不可用，请结合已展示的限制查看计划',
  PLANNER_FALLBACK: '任务分解使用了备用方式', MENU_FALLBACK: '餐食选择使用了备用方式',
  MENU_REPAIR_FALLBACK: '计划修订使用了备用方式',
  REPAIR_BUDGET_INSUFFICIENT: '本次未能完成需求修订，请重新规划',
  CANDIDATES_EXPANDED: '已扩大候选检索范围', CANDIDATES_TRUNCATED: '本次仅在有限候选范围内进行选择',
  CANDIDATE_SNAPSHOT_CONFLICT: '部分餐食信息不一致，已排除相关候选',
  CONTEXT_CHANGED: '饮食限制或餐食信息已变化，请重新规划',
  REVIEW_NEEDS_INPUT: '复核发现需要确认的要求，请补充后重新规划',
  ALL_RETRIEVAL_FAILED: '餐食检索暂时不可用，请稍后重试',
  PLANNING_DEADLINE: '规划超时，请稍后重试', PLANNING_UNAVAILABLE: '规划服务暂时不可用，请稍后重试',
  MAX_BUDGET: '预算要求暂时无法核验，请明确放宽后重新规划',
}

function friendlyNotice(value: string, fallback: string) {
  if (notices[value]) return notices[value]
  // Server may return user-facing Chinese clarifications; unknown internal codes stay out of the UI.
  const prose = value.replace(/^[A-Z][A-Z0-9_]*\s*[:：]\s*/, '')
  return /[\u3400-\u9fff]/.test(prose) ? prose : fallback
}

function candidateMeal(candidate: PlanningCandidate): Meal {
  return {
    id: candidate.mealId, name: candidate.name, sourceType: candidate.sourceMode,
    mealTime: candidate.mealTimes, mood: candidate.tags.mood ?? [], scene: candidate.tags.scene ?? [],
    healthGoal: candidate.tags.healthGoal ?? [], cuisine: candidate.tags.cuisine ?? [],
    taste: candidate.tags.taste ?? [], convenience: candidate.tags.convenience ?? [], matchScore: 0, knowledge: null,
  }
}

export function MealPlanningCards({ plan, canFeedback, renderMeal }: {
  plan: MealPlanningResult
  canFeedback: boolean
  renderMeal: (meal: Meal, taskId: string, actionable: boolean) => ReactNode
}) {
  const persisted = Boolean(plan.planId?.trim()) && Number.isInteger(plan.version) && plan.version > 0
  const warningText = [...new Set([
    ...plan.unsupportedConstraints.map(value => friendlyNotice(value, '部分要求暂时无法核验，请明确放宽后重新规划')),
    ...plan.warnings.map(value => friendlyNotice(value, '本次规划存在限制，请结合需求检查计划')),
  ])]
  return <div className="chat-planning-result">
    <div className="chat-plan-status" role="status" aria-label="餐食规划状态">
      <strong>{statusLabels[plan.status] ?? '请查看规划结果'}</strong>
      <p>{strategyLabels[plan.strategy] ?? '使用备用规划方式'}</p>
      <p>{plan.reviewed ? '已完成需求复核' : '尚未完成需求复核'}</p>
      {!persisted && plan.days.length > 0 && <p className="chat-warning">计划尚未保存，暂不能反馈</p>}
      {warningText.map(text => <p className="chat-warning" key={text}>{text}</p>)}
    </div>
    <div className="chat-dated-plan">{plan.days.map(day =>
      <section key={day.date} aria-label={`${day.date} 餐食计划`} className="chat-plan-day">
        <h3><time dateTime={day.date}>{day.date}</time></h3>
        <div className="chat-plan-day-blocks">{day.blocks.map(block => {
          const gaps = plan.gaps.filter(gap => gap.taskId === block.taskId)
          return <div className="chat-plan-task" key={block.taskId}>
            <h4>{block.mealTime}</h4>
            {block.selectedMeal ? <>
              {renderMeal(candidateMeal(block.selectedMeal), block.taskId, persisted && canFeedback)}
              {block.reason && <p className="chat-plan-reason">{block.reason}</p>}
              {block.repeated && <p className="chat-warning">与计划中其他餐次重复</p>}
            </> : <p className="chat-plan-gap">{gaps.length ? null : '该餐次暂未安排餐食'}</p>}
            {gaps.map((gap, index) => <p className="chat-plan-gap" key={`${gap.code}-${index}`}>
              {friendlyNotice(gap.code, '该餐次仍有缺口，请调整要求后重新规划')}
            </p>)}
          </div>
        })}</div>
      </section>,
    )}</div>
  </div>
}
