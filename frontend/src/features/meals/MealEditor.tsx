import type { FormEvent } from 'react'
import type { MealDraft, SlotKey, SlotOptions, Slots } from '../../api/types'

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
  error: string
}

export default function MealEditor({ value, onChange, options, draft, text, onTextChange, onGenerate,
  onSave, onClear, editing, generating, saving, error }: Props) {
  const busy = generating || saving
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
        <p className="meal-hint">整理结果只回填下方表单，核对后再保存。</p>
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
      <p className="meal-hint">标签可多选：按住 Ctrl 或 Command 点击选项。</p>
      <div className="meal-slot-grid">{SLOT_KEYS.map(key => <div className="meal-field" key={key}>
        <label htmlFor={`meal-${key}`}>{SLOT_LABELS[key]}{key === 'mealTime' ? '（必选）' : ''}</label>
        <select id={`meal-${key}`} multiple size={Math.min(Math.max(options[key]?.length ?? 3, 3), 5)}
          value={value[key]} onChange={event => onChange({ ...value,
            [key]: Array.from(event.currentTarget.selectedOptions, option => option.value),
          })}>
          {(options[key] ?? []).map(option => <option key={option} value={option}>{option}</option>)}
        </select>
      </div>)}</div>
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
