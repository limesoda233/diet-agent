import { api, type MealInput } from '../../services/api'

const fields = ['mealTime', 'mood', 'scene', 'healthGoal', 'cuisine', 'taste', 'convenience'] as const
const tags = (text: string): string[] => text.split(/[,，、]/).map(value => value.trim()).filter(Boolean)

Page({
  data: { id: 0, name: '', mealTime: '', mood: '', scene: '', healthGoal: '',
    cuisine: '', taste: '', convenience: '', saving: false },
  async onLoad(options: { id?: string }) {
    if (!options.id) return
    const id = Number(options.id)
    try {
      const meal = (await api.personalMeals()).find(item => item.id === id)
      if (!meal) throw new Error('餐食不存在')
      const values: Record<string, string | number> = { id, name: meal.name }
      for (const field of fields) values[field] = meal[field].join('，')
      this.setData(values)
    } catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' }) }
  },
  onInput(event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) {
    this.setData({ [event.currentTarget.dataset.field]: event.detail.value })
  },
  async save() {
    if (this.data.saving) return
    const name = this.data.name.trim()
    if (!name) { wx.showToast({ title: '请填写餐食名称', icon: 'none' }); return }
    const meal = { name } as MealInput
    for (const field of fields) meal[field] = tags(this.data[field])
    this.setData({ saving: true })
    try {
      if (this.data.id) await api.updateMeal(this.data.id, meal)
      else await api.createMeal(meal)
      wx.switchTab({ url: '/pages/meals/index' })
    } catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '保存失败', icon: 'none' }) }
    finally { this.setData({ saving: false }) }
  },
})
