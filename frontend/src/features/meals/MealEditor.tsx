import type { FormEvent } from 'react'
import type { MealDraft, NutritionDraft, NutritionVariant, SlotKey, SlotOptions, Slots } from '../../api/types'
import { NutritionChoices } from './NutritionChoices'

export const SLOT_LABELS: Record<SlotKey, string> = {
  mealTime: '用餐时间', mood: '心情状态', scene: '用餐场景', healthGoal: '健康目标',
  cuisine: '菜系偏好', taste: '口味偏好', convenience: '便利程度',
}
export const SLOT_KEYS = Object.keys(SLOT_LABELS) as SlotKey[]

export interface EditorValue extends Slots {
  name: string
  ingredientsText: string
  description: string
  preparation: string
}

interface Props {
  value: EditorValue
  onChange: (next: EditorValue) => void
  options: SlotOptions
  draft: MealDraft | null
  text: string
  onTextChange: (text: string) => void
  onGenerate: () => void
  onSave: () => void
  onClear: () => void
  editing: boolean
  generating: boolean
  saving: boolean
  mutationLocked: boolean
  error: string
  nutrition: NutritionDraft | null
  variants: NutritionVariant[]
  onSuggestNutrition: () => void
  onChooseNutrition: (key: string,value: string) => void
  onEditVariant: (variant: NutritionVariant) => void
  onDeleteVariant: (variant: NutritionVariant) => void
}

export default function MealEditor({ value, onChange, options, draft, text, onTextChange, onGenerate,
  onSave, onClear, editing, generating, saving, mutationLocked, error,
  nutrition, variants, onSuggestNutrition, onChooseNutrition, onEditVariant, onDeleteVariant }: Props) {
  const busy = generating || saving || mutationLocked
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSave()
  }
  return <section className="meal-panel meal-editor" aria-label={editing ? '编辑餐食' : '新增餐食'}>
    <div className="meal-panel-heading">
      <div><p className="meal-eyebrow">我的餐食记录</p><h2>{editing ? '编辑餐食' : '新增餐食'}</h2></div>
    </div>
    <p className="meal-hint">餐食名称和用餐时间必填，其余标签可以留空。</p>
    <form onSubmit={submit} noValidate>
      {!editing && <div className="meal-field meal-narrative">
        <label htmlFor="meal-text">用一句话描述餐食</label>
        <textarea id="meal-text" value={text} maxLength={4000} onChange={event => onTextChange(event.target.value)}
          placeholder="例如：番茄鸡蛋面，中午吃，先炒再煮" />
        <button className="meal-button meal-button-soft" type="button" onClick={onGenerate} disabled={busy}>
          {generating ? '整理中...' : 'AI 整理'}
        </button>
        <p className="meal-hint">只填餐食名称也能整理标签；结果会回填表单，核对后再保存。</p>
      </div>}
      {draft && !editing && <section className="meal-draft-preview" aria-live="polite">
        <strong>整理预览</strong>
        <p>{draft.originalText}</p>
        {draft.missingFields.length > 0 && <p>待补充：{draft.missingFields.map(key => key === 'name' ? '餐食名称' : key === 'mealTime' ? '用餐时间' : key).join('、')}</p>}
        {draft.warnings.map((warning, index) => <p key={index}>{warning}</p>)}
      </section>}
      <div className="meal-field">
        <label htmlFor="meal-name">餐食名称</label>
        <input id="meal-name" value={value.name} maxLength={128} onChange={event => onChange({ ...value, name: event.target.value })} />
      </div>
      <button className="meal-button meal-button-soft" type="button" onClick={onSuggestNutrition} disabled={busy || !value.name.trim()}>小狗帮我选搭配</button>
      <NutritionChoices draft={nutrition} onChoose={onChooseNutrition} disabled={busy} />
      {variants.length > 0 && <section className="nutrition-saved" aria-label="已保存的搭配版本">
        <h3>已保存的搭配版本</h3>{variants.map(variant => <div key={variant.id}>
          <strong>{variant.label}</strong><span>约 {variant.estimate.kcalLow}–{variant.estimate.kcalHigh} 千卡/份</span>
          <button className="meal-button meal-button-soft" type="button" onClick={() => onEditVariant(variant)} disabled={busy}>按此版本修改</button>
          <button className="meal-button meal-button-ghost" type="button" onClick={() => onDeleteVariant(variant)} disabled={busy}>删除版本</button>
        </div>)}</section>}
      <p className="meal-hint">点击标签即可选择，再点一次可取消。</p>
      <div className="meal-slot-grid">{SLOT_KEYS.map(key => {
        const choices = Array.from(new Set([...(options[key] ?? []), ...value[key]]))
        return <div className="meal-field meal-slot-field" key={key} role="group" aria-labelledby={`meal-${key}-label`}>
          <span className="meal-slot-label" id={`meal-${key}-label`}>{SLOT_LABELS[key]}{key === 'mealTime' ? '（必选）' : ''}</span>
          {choices.length === 0 ? <p className="meal-no-options">暂无可选标签</p> :
            <div className="meal-option-list">{choices.map(option => {
              const selected = value[key].includes(option)
              return <button key={option} type="button" className={`meal-option${selected ? ' is-selected' : ''}`}
                aria-pressed={selected} onClick={() => onChange({ ...value,
                  [key]: selected ? value[key].filter(item => item !== option) : [...value[key], option],
                })}>{option}</button>
            })}</div>}
        </div>
      })}</div>
      <div className="meal-field"><label htmlFor="meal-ingredients">食材（每行一项，最多 64 项）</label>
        <textarea id="meal-ingredients" value={value.ingredientsText} onChange={event => onChange({ ...value, ingredientsText: event.target.value })} />
      </div>
      <div className="meal-field"><label htmlFor="meal-description">描述</label>
        <textarea id="meal-description" maxLength={2000} value={value.description} onChange={event => onChange({ ...value, description: event.target.value })} />
      </div>
      <div className="meal-field"><label htmlFor="meal-preparation">我的做法</label>
        <textarea id="meal-preparation" maxLength={4000} value={value.preparation} onChange={event => onChange({ ...value, preparation: event.target.value })} />
      </div>
      {error && <p className="meal-error" role="alert">{error}</p>}
      <div className="meal-actions">
        <button className="meal-button meal-button-primary" type="submit" disabled={busy}>{saving ? '保存中...' : editing ? '保存修改' : '保存到我的餐食库'}</button>
        <button className="meal-button meal-button-ghost" type="button" onClick={onClear}>清空</button>
      </div>
    </form>
  </section>
}
