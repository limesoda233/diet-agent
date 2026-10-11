import { useCallback, useEffect, useRef, useState } from 'react'
import { useApp } from '../../context/AppContext'
import type { Meal, MealDraft, MealKnowledge, MealRequest, SlotKey, SlotOptions } from '../../api/types'
import MealEditor, { SLOT_KEYS, SLOT_LABELS, type EditorValue } from './MealEditor'
import './meals.css'

const EMPTY_OPTIONS: SlotOptions = {}
const EMPTY_EDITOR: EditorValue = { name: '', mealTime: [], mood: [], scene: [], healthGoal: [],
  cuisine: [], taste: [], convenience: [], ingredientsText: '', description: '', preparation: '' }

function fromMeal(meal: Meal): EditorValue {
  return { name: meal.name, ...Object.fromEntries(SLOT_KEYS.map(key => [key, [...(meal[key] ?? [])]])) as Pick<Meal, SlotKey>,
    ingredientsText: meal.knowledge?.ingredients.join('\n') ?? '', description: meal.knowledge?.description ?? '',
    preparation: meal.knowledge?.preparation ?? '' }
}

function fromDraft(draft: MealDraft): EditorValue {
  const data = draft.data
  return { name: data.name ?? '', ...Object.fromEntries(SLOT_KEYS.map(key => [key, [...(data[key] ?? [])]])) as Pick<Meal, SlotKey>,
    ingredientsText: data.knowledge?.ingredients.join('\n') ?? '', description: data.knowledge?.description ?? '',
    preparation: data.knowledge?.preparation ?? '' }
}

function ingredientsOf(text: string) {
  return text.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
}

function knowledgeOf(value: EditorValue): MealKnowledge {
  return { ingredients: ingredientsOf(value.ingredientsText), description: value.description.trim(),
    preparation: value.preparation.trim(), sourceType: 'USER_PROVIDED', sourceReference: '' }
}

function sameKnowledge(knowledge: MealKnowledge, existing: MealKnowledge | null) {
  return JSON.stringify([knowledge.ingredients, knowledge.description, knowledge.preparation]) ===
    JSON.stringify([existing?.ingredients ?? [], existing?.description ?? '', existing?.preparation ?? ''])
}

function requestOf(value: EditorValue, existing: Meal | null, drafted: boolean): MealRequest {
  const knowledge = knowledgeOf(value)
  const payload: MealRequest = { name: value.name.trim(), ...Object.fromEntries(SLOT_KEYS.map(key => [key, value[key]])) as Pick<Meal, SlotKey> }
  const hasKnowledge = knowledge.ingredients.length > 0 || knowledge.description !== '' || knowledge.preparation !== ''
  if (existing ? !sameKnowledge(knowledge, existing.knowledge) : drafted || hasKnowledge) payload.knowledge = knowledge
  return payload
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

export function MealsPage({ publicOnly = false }: { publicOnly?: boolean }) {
  const { api, mealVersion, refreshMeals } = useApp()
  const [meals, setMeals] = useState<Meal[]>([])
  const [options, setOptions] = useState<SlotOptions>(EMPTY_OPTIONS)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [query, setQuery] = useState('')
  const [tag, setTag] = useState('')
  const [editor, setEditor] = useState<EditorValue>(EMPTY_EDITOR)
  const [editing, setEditing] = useState<Meal | null>(null)
  const [draft, setDraft] = useState<MealDraft | null>(null)
  const [text, setText] = useState('')
  const [formError, setFormError] = useState('')
  const [generating, setGenerating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<Meal | null>(null)
  const [deleting, setDeleting] = useState(false)
  const mounted = useRef(true)
  const editorEpoch = useRef(0)
  const operationEpoch = useRef(0)
  const loadEpoch = useRef(0)
  const busy = useRef(false)
  const deleteBusy = useRef(false)

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; editorEpoch.current++; loadEpoch.current++ } }, [])

  const reload = useCallback(async () => {
    const epoch = ++loadEpoch.current
    setLoading(true)
    setLoadError('')
    try {
      const result = await (publicOnly ? api.listPublicMeals() : api.listPersonalMeals())
      if (mounted.current && epoch === loadEpoch.current) setMeals(result)
    } catch (error) {
      if (mounted.current && epoch === loadEpoch.current) setLoadError(errorText(error, '餐食加载失败，请重试'))
    } finally {
      if (mounted.current && epoch === loadEpoch.current) setLoading(false)
    }
  }, [api, publicOnly])

  useEffect(() => { void reload() }, [reload, mealVersion])
  useEffect(() => {
    let active = true
    api.slotOptions().then(result => { if (active) setOptions(result) })
      .catch(error => { if (active) setLoadError(errorText(error, '标签加载失败，请重试')) })
    return () => { active = false }
  }, [api])

  function clearEditor() {
    editorEpoch.current++
    operationEpoch.current++
    busy.current = false
    setEditor(EMPTY_EDITOR)
    setEditing(null)
    setDraft(null)
    setText('')
    setFormError('')
    setGenerating(false)
    setSaving(false)
  }

  function editMeal(meal: Meal) {
    clearEditor()
    setEditing(meal)
    setEditor(fromMeal(meal))
  }

  function changeEditor(next: EditorValue) {
    editorEpoch.current++
    setEditor(next)
  }

  function changeText(next: string) {
    editorEpoch.current++
    setText(next)
  }

  async function generate() {
    if (busy.current || editing) return
    const description = text.trim()
    if (!description || description.length > 4000) { setFormError('请描述一道餐食，最多 4000 字'); return }
    const epoch = editorEpoch.current
    const operation = ++operationEpoch.current
    busy.current = true
    setGenerating(true)
    setFormError('')
    setDraft(null)
    try {
      const result = await api.generateMealDraft(description)
      if (mounted.current && epoch === editorEpoch.current) { setDraft(result); setEditor(fromDraft(result)) }
    } catch (error) {
      if (mounted.current && epoch === editorEpoch.current) setFormError(errorText(error, '整理失败，已保留表单内容'))
    } finally {
      if (mounted.current && operation === operationEpoch.current) { busy.current = false; setGenerating(false) }
    }
  }

  async function save() {
    if (busy.current) return
    if (!editor.name.trim()) { setFormError('请填写餐食名称'); return }
    if (editor.mealTime.length === 0) { setFormError('请至少选择一个用餐时间标签'); return }
    const ingredients = ingredientsOf(editor.ingredientsText)
    if (ingredients.length > 64 || ingredients.some(item => item.length > 64)) {
      setFormError('食材最多 64 项，每项最多 64 字'); return
    }
    if (editor.description.trim().length > 2000 || editor.preparation.trim().length > 4000) {
      setFormError('描述最多 2000 字，我的做法最多 4000 字'); return
    }
    const payload = requestOf(editor, editing, draft !== null)
    const epoch = editorEpoch.current
    const operation = ++operationEpoch.current
    busy.current = true
    setSaving(true)
    setFormError('')
    try {
      if (editing) await api.updatePersonalMeal(editing.id, payload)
      else await api.createPersonalMeal(payload)
      if (!mounted.current) return
      refreshMeals()
      if (epoch === editorEpoch.current) clearEditor()
      await reload()
    } catch (error) {
      if (mounted.current && epoch === editorEpoch.current) setFormError(errorText(error, '保存失败，请重试'))
    } finally {
      if (mounted.current && operation === operationEpoch.current) { busy.current = false; setSaving(false) }
    }
  }

  async function confirmDelete() {
    if (!deleteTarget || deleteBusy.current) return
    const target = deleteTarget
    deleteBusy.current = true
    setDeleting(true)
    setLoadError('')
    try {
      await api.deletePersonalMeal(target.id)
      if (!mounted.current) return
      if (editing?.id === target.id) clearEditor()
      setDeleteTarget(null)
      refreshMeals()
      await reload()
    } catch (error) {
      if (mounted.current) setLoadError(errorText(error, '删除失败，请重试'))
    } finally {
      deleteBusy.current = false
      if (mounted.current) setDeleting(false)
    }
  }

  const search = query.trim().toLocaleLowerCase()
  const [tagKey, tagValue] = tag ? tag.split(':', 2) as [SlotKey, string] : [null, null]
  const visible = meals.filter(meal => (!search || meal.name.toLocaleLowerCase().includes(search) ||
    SLOT_KEYS.some(key => meal[key]?.some(value => value.toLocaleLowerCase().includes(search)))) &&
    (!tagKey || meal[tagKey]?.includes(tagValue)))

  return <div className="meals-page">
    <section className="meal-panel meal-library">
      <div className="meal-panel-heading"><div><p className="meal-eyebrow">{publicOnly ? '系统预置 · 只读' : '由你维护 · 供推荐使用'}</p>
        <h1>{publicOnly ? '公共餐食' : '个人餐食'}</h1>
        <p className="meal-hint">{publicOnly ? '查看系统预置餐食，可在聊天页选择公共库。' : '记录常吃餐食，推荐会从你的个人库检索。'}</p></div>
        {!publicOnly && <button className="meal-button meal-button-primary" onClick={clearEditor}>新增餐食</button>}
      </div>
      <div className="meal-filters"><div className="meal-field"><label htmlFor="meal-search">搜索餐食</label>
        <input id="meal-search" value={query} onChange={event => setQuery(event.target.value)} placeholder="名称或标签" /></div>
        <div className="meal-field"><label htmlFor="meal-tag">筛选标签</label>
          <select id="meal-tag" value={tag} onChange={event => setTag(event.target.value)}><option value="">全部标签</option>
            {SLOT_KEYS.map(key => <optgroup key={key} label={SLOT_LABELS[key]}>{(options[key] ?? []).map(value =>
              <option key={`${key}:${value}`} value={`${key}:${value}`}>{value}</option>)}</optgroup>)}</select></div></div>
      {loadError && <p className="meal-error" role="alert">{loadError} <button type="button" onClick={() => void reload()}>重试</button></p>}
      {loading ? <p className="meal-empty" role="status">餐食加载中...</p> : visible.length === 0 ?
        <p className="meal-empty">{query || tag ? '没有符合条件的餐食，请调整筛选。' : publicOnly ? '公共餐食暂无数据。' : '暂无餐食，可以先新增一道常吃的菜。'}</p> :
        <div className="meal-list">{visible.map(meal => <article className="meal-card" key={meal.id} aria-label={meal.name}>
          <div className="meal-card-top"><h2>{meal.name}</h2><span>{meal.sourceType === 'PUBLIC' ? '公共' : '个人'}</span></div>
          <div className="meal-chips">{SLOT_KEYS.flatMap(key => (meal[key] ?? []).map(value => <span key={`${key}:${value}`} className="meal-chip">{SLOT_LABELS[key]} · {value}</span>))}</div>
          {!publicOnly && <div className="meal-actions"><button className="meal-button meal-button-soft" onClick={() => editMeal(meal)}>编辑</button>
            <button className="meal-button meal-button-ghost" onClick={() => setDeleteTarget(meal)}>删除</button></div>}
        </article>)}</div>}
      {deleteTarget && !publicOnly && <div className="meal-confirm" role="dialog" aria-modal="true" aria-label="删除餐食确认">
        <p>确定删除“{deleteTarget.name}”？</p><div className="meal-actions">
          <button className="meal-button meal-button-ghost" onClick={() => setDeleteTarget(null)} disabled={deleting}>取消</button>
          <button className="meal-button meal-button-danger" onClick={() => void confirmDelete()} disabled={deleting}>{deleting ? '删除中...' : '确认删除'}</button>
        </div></div>}
    </section>
    {!publicOnly && <MealEditor value={editor} onChange={changeEditor} options={options} draft={draft} text={text}
      onTextChange={changeText} onGenerate={() => void generate()} onSave={() => void save()} onClear={clearEditor}
      editing={editing !== null} generating={generating} saving={saving} error={formError} />}
  </div>
}

export default MealsPage
