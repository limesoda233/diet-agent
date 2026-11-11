import { api, type Meal } from '../../services/api'

Page({
  data: { meals: [] as Meal[], empty: false },
  onShow() { void this.load() },
  async load() {
    try {
      const meals = await api.personalMeals()
      this.setData({ meals, empty: meals.length === 0 })
    } catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' }) }
  },
  add() { wx.navigateTo({ url: '/pages/meal-edit/index' }) },
  edit(event: { currentTarget: { dataset: { id: number } } }) {
    wx.navigateTo({ url: `/pages/meal-edit/index?id=${event.currentTarget.dataset.id}` })
  },
  remove(event: { currentTarget: { dataset: { id: number } } }) {
    const id = event.currentTarget.dataset.id
    wx.showModal({ title: '删除个人餐食', content: '删除后无法恢复。', success: async result => {
      if (!result.confirm) return
      try { await api.deleteMeal(id); await this.load() }
      catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '删除失败', icon: 'none' }) }
    } })
  },
})
