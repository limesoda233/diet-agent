import { api, ApiError, type MealInput, type NutritionDraft, type NutritionVariant } from '../../services/api'

const fields = ['mealTime', 'mood', 'scene', 'healthGoal', 'cuisine', 'taste', 'convenience'] as const
type Field = typeof fields[number]
const labels: Record<Field, string> = { mealTime: '餐次', mood: '心情', scene: '场景',
  healthGoal: '健康目标', cuisine: '菜系', taste: '口味', convenience: '便利程度' }
const empty = (): Record<Field, string[]> => ({ mealTime: [], mood: [], scene: [],
  healthGoal: [], cuisine: [], taste: [], convenience: [] })

Page({
  data: { id: 0, name: '', description: '', selected: empty(),
    groups: [] as { field: Field; label: string; choices: { value: string; selected: boolean }[] }[],
    options: {} as Record<string, string[]>, saving: false, generating: false, error: '',
    nutritionDraft: null as NutritionDraft | null,
    nutritionGroups: [] as { key: string; label: string; choices: { value: string; label: string; selected: boolean }[] }[],
    savedVariants: [] as NutritionVariant[] },
  async onLoad(options: { id?: string }) {
    try {
      const choices = await api.slotOptions()
      this.setData({ options: choices })
      if (options.id) {
        const id = Number(options.id)
        const meal = (await api.personalMeals()).find(item => item.id === id)
        if (!meal) throw new Error('餐食不存在')
        const selected = empty()
        for (const field of fields) selected[field] = meal[field]
        const savedVariants = await api.personalVariants(id)
        this.setData({ id, name: meal.name, selected, savedVariants })
      }
      this.renderGroups()
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '标签加载失败' }) }
  },
  renderGroups() {
    const groups = fields.map(field => ({ field, label: labels[field],
      choices: [...new Set([...(this.data.options[field] || []), ...this.data.selected[field]])]
        .map(value => ({ value, selected: this.data.selected[field].includes(value) })) }))
    this.setData({ groups })
  },
  renderNutrition() {
    const draft = this.data.nutritionDraft
    this.setData({ nutritionGroups: draft ? draft.questions.map(question => ({ key: question.key,
      label: question.label, choices: question.options.map(choice => ({ ...choice,
        selected: draft.selections[question.key] === choice.value })) })) : [] })
  },
  onName(event: { detail: { value: string } }) {
    if (event.detail.value !== this.data.name) this.setData({ nutritionDraft: null, nutritionGroups: [] })
    this.setData({ name: event.detail.value })
  },
  onDescription(event: { detail: { value: string } }) { this.setData({ description: event.detail.value }) },
  toggleTag(event: { currentTarget: { dataset: { field: Field; value: string } } }) {
    const { field, value } = event.currentTarget.dataset
    const current = this.data.selected[field]
    this.setData({ selected: { ...this.data.selected, [field]: current.includes(value) ?
      current.filter(item => item !== value) : [...current, value] } })
    this.renderGroups()
  },
  async chooseNutrition(event: { currentTarget: { dataset: { key: string; value: string } } }) {
    const old = this.data.nutritionDraft
    if (!old || this.data.saving) return
    const selections = { ...old.selections, [event.currentTarget.dataset.key]: event.currentTarget.dataset.value }
    this.setData({ nutritionDraft: { ...old, selections } })
    this.renderNutrition()
    try {
      const estimate = await api.previewNutrition(old.templateCode, selections)
      if (JSON.stringify(this.data.nutritionDraft?.selections) === JSON.stringify(selections))
        this.setData({ nutritionDraft: { ...this.data.nutritionDraft!, estimate } })
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '热量估算暂不可用' }) }
  },
  async editVariant(event: { currentTarget: { dataset: { id: number } } }) {
    const variant = this.data.savedVariants.find(item => item.id === event.currentTarget.dataset.id)
    if (!variant) return
    try {
      const draft = await api.suggestNutrition(this.data.name, this.data.description || this.data.name)
      this.setData({ nutritionDraft: { ...draft, selections: variant.selections, estimate: variant.estimate } })
      this.renderNutrition()
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '搭配版本加载失败' }) }
  },
  async removeVariant(event: { currentTarget: { dataset: { id: number } } }) {
    const confirmed = await new Promise<boolean>(resolve => wx.showModal({ title: '删除搭配版本？',
      content: '餐食本身会保留。', success: result => resolve(result.confirm) }))
    if (!confirmed) return
    try {
      await api.deleteVariant(this.data.id, event.currentTarget.dataset.id)
      this.setData({ savedVariants: await api.personalVariants(this.data.id) })
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '删除失败' }) }
  },
  async generate() {
    if (this.data.generating || this.data.saving) return
    const name = this.data.name.trim()
    if (!name) { this.setData({ error: '请先填写餐食名称' }); return }
    this.setData({ generating: true, error: '' })
    try {
      const text = `餐食名称：${name}${this.data.description.trim() ? '；' + this.data.description.trim() : ''}`
      const [mealResult,nutritionResult] = await Promise.allSettled([
        api.generateMealDraft(text),api.suggestNutrition(name,text)])
      if (mealResult.status === 'fulfilled') {
        const selected = { ...this.data.selected }
        for (const field of fields) selected[field] = [...new Set([...selected[field], ...mealResult.value.data[field]])]
        this.setData({ selected }); this.renderGroups()
      }
      if (nutritionResult.status === 'fulfilled' && nutritionResult.value.questions.length) {
        this.setData({ nutritionDraft: nutritionResult.value }); this.renderNutrition()
      }
      if (mealResult.status === 'rejected' && nutritionResult.status === 'rejected')
        throw mealResult.reason
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '解析失败，请手动选择标签' }) }
    finally { this.setData({ generating: false }) }
  },
  async save() {
    if (this.data.saving || this.data.generating) return
    const name = this.data.name.trim()
    if (!name) { this.setData({ error: '请填写餐食名称' }); return }
    if (this.data.selected.mealTime.length === 0) { this.setData({ error: '请至少选择一个餐次' }); return }
    const nutrition = this.data.nutritionDraft
    const variant = nutrition && nutrition.estimate ? { label: this.variantLabel(),
      templateCode: nutrition.templateCode, selections: nutrition.selections } : undefined
    const meal = { name, ...this.data.selected, ...(variant ? { nutritionVariant: variant } : {}) } as MealInput
    this.setData({ saving: true, error: '' })
    try {
      if (this.data.id) await api.updateMeal(this.data.id, meal)
      else {
        try { await api.createMeal(meal) }
        catch (error) {
          if (!(error instanceof ApiError) || error.code !== 'DUPLICATE_MEAL') throw error
          const confirmed = await new Promise<boolean>(resolve => wx.showModal({
            title: '已有同名餐食',
            content: '将把这次选择的标签合并到原餐食，并合并已有的同名条目。确认合并？',
            success: result => resolve(result.confirm),
          }))
          if (!confirmed) return
          await api.mergeMeal(meal)
        }
      }
      wx.switchTab({ url: '/pages/meals/index' })
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '保存失败' }) }
    finally { this.setData({ saving: false }) }
  },
  variantLabel() {
    const draft = this.data.nutritionDraft
    if (!draft) return '常吃搭配'
    return draft.questions.filter(item => ['size','starch','spice'].includes(item.key))
      .map(item => item.options.find(option => option.value === draft.selections[item.key])?.label)
      .filter(Boolean).join(' · ').slice(0, 64) || '常吃搭配'
  },
})
