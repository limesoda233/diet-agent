import type { NutritionDraft } from '../../api/types'

export function nutritionVariantOf(draft: NutritionDraft) {
  const label = draft.questions.filter(question => ['size','starch','spice'].includes(question.key))
    .map(question => question.options.find(option => option.value === draft.selections[question.key])?.label)
    .filter(Boolean).join(' · ').slice(0,64) || '常吃搭配'
  return { label, templateCode: draft.templateCode, selections: draft.selections }
}

export function NutritionChoices({ draft, onChoose, disabled = false }: {
  draft: NutritionDraft | null
  onChoose: (key: string,value: string) => void
  disabled?: boolean
}) {
  if (!draft?.questions?.length) return null
  return <section className="nutrition-choices" aria-label="这顿怎么搭配">
    <h3>这顿怎么搭配？</h3><p>按平时吃的一份选择，热量是粗估范围。</p>
    {draft.questions.map(question => <div key={question.key} className="nutrition-question" role="group" aria-label={question.label}>
      <strong>{question.label}</strong><div className="meal-option-list">{question.options.map(option =>
        <button key={option.value} type="button" className={`meal-option${draft.selections[question.key] === option.value ? ' is-selected' : ''}`}
          aria-pressed={draft.selections[question.key] === option.value} disabled={disabled}
          onClick={() => onChoose(question.key,option.value)}>{option.label}</button>)}</div>
    </div>)}
    {draft.estimate && <p className="nutrition-estimate">每份约 {draft.estimate.kcalLow}–{draft.estimate.kcalHigh} 千卡
      <small>碳水 {draft.estimate.carbsLow}–{draft.estimate.carbsHigh} g · 蛋白质 {draft.estimate.proteinLow}–{draft.estimate.proteinHigh} g</small>
      <small>仅供搭配参考，实际分量和做法会改变热量。</small></p>}
  </section>
}
