import { api, type DietaryProfile } from '../../services/api'

type FoodField = 'allergens' | 'avoidedFoods' | 'dislikedFoods'
const questions = [
  { title: '有哪些食物会让你过敏？', hint: '可以多选。这里只记录你明确知道的过敏原，暂时不确定可以跳过。', field: 'allergens' as const,
    choices: ['花生', '牛奶', '鸡蛋', '小麦', '虾', '蟹', '鱼', '大豆', '坚果', '芝麻'] },
  { title: '有哪些食物你不吃？', hint: '忌口和过敏不同，例如香菜、牛肉，按你的实际习惯填写。', field: 'avoidedFoods' as const,
    choices: ['香菜', '牛肉', '猪肉', '羊肉', '海鲜', '内脏', '菌菇'] },
  { title: '你平时吃辣吗？', hint: '这会影响小狗推荐的辣味标签。', field: 'spicePreference' as const,
    choices: ['完全不吃辣', '微辣可以', '都可以', '暂不确定'] },
  { title: '有没有你不喜欢的食物？', hint: '比如榴莲、折耳根。以后也可以在设置里修改。', field: 'dislikedFoods' as const,
    choices: ['榴莲', '折耳根', '香菜', '苦瓜', '芹菜', '茄子'] },
]
const spiceValues: DietaryProfile['spicePreference'][] = ['NEVER', 'MILD', 'OK', 'UNSET']
const empty: DietaryProfile = { allergens: [], avoidedFoods: [], dislikedFoods: [],
  spicePreference: 'UNSET', spiceNote: '', onboardingStatus: 'NEW' }

Page({
  data: { step: 0, total: questions.length, title: '', hint: '', choices: [] as { label: string; selected: boolean }[],
    profile: empty, custom: '', busy: false, loading: true, error: '', editing: false },
  async onLoad(options: { edit?: string }) {
    this.setData({ editing: options.edit === '1' })
    try {
      const profile = await api.dietaryProfile()
      this.setData({ profile, loading: false })
      this.renderStep()
    } catch (error) {
      this.setData({ error: error instanceof Error ? error.message : '加载失败', loading: false })
    }
  },
  renderStep() {
    const question = questions[this.data.step]
    const profile = this.data.profile
    const selected = question.field === 'spicePreference' ?
      [questions[2].choices[spiceValues.indexOf(profile.spicePreference)]] : profile[question.field]
    const labels = question.field === 'spicePreference' ? question.choices :
      [...new Set([...question.choices, ...selected])]
    this.setData({ title: question.title, hint: question.hint,
      choices: labels.map(label => ({ label, selected: selected.includes(label) })),
      ...(question.field === 'spicePreference' ? { custom: profile.spiceNote } : {}) })
  },
  toggle(event: { currentTarget: { dataset: { label: string } } }) {
    const label = event.currentTarget.dataset.label
    const question = questions[this.data.step]
    const profile = this.data.profile
    if (question.field === 'spicePreference') {
      this.setData({ profile: { ...profile, spicePreference: spiceValues[question.choices.indexOf(label)] } })
    } else {
      const field: FoodField = question.field
      const old = profile[field]
      this.setData({ profile: { ...profile, [field]: old.includes(label) ? old.filter(item => item !== label) : [...old, label] } })
    }
    this.renderStep()
  },
  onCustom(event: { detail: { value: string } }) {
    const custom = event.detail.value
    this.setData({ custom, ...(this.data.step === 2 ? { profile: { ...this.data.profile, spiceNote: custom } } : {}) })
  },
  addCustom() {
    const value = this.data.custom.trim()
    if (!value || value.length > (this.data.step === 2 ? 128 : 64)) {
      wx.showToast({ title: '请填写有效的补充内容', icon: 'none' }); return false
    }
    const field = questions[this.data.step].field
    if (field === 'spicePreference') {
      this.setData({ profile: { ...this.data.profile, spiceNote: value } })
    } else {
      const old = this.data.profile[field]
      this.setData({ profile: { ...this.data.profile, [field]: old.includes(value) ? old : [...old, value] }, custom: '' })
    }
    this.renderStep()
    return true
  },
  back() { if (this.data.step > 0) { this.setData({ step: this.data.step - 1, custom: '' }); this.renderStep() } },
  next() {
    if (this.data.custom.trim() && !this.addCustom()) return
    if (this.data.step < questions.length - 1) { this.setData({ step: this.data.step + 1, custom: '' }); this.renderStep() }
    else void this.save()
  },
  async save() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      await api.saveDietaryProfile({ ...this.data.profile, onboardingStatus: 'COMPLETED' })
      this.finish()
    } catch (error) { this.setData({ error: error instanceof Error ? error.message : '保存失败' }) }
    finally { this.setData({ busy: false }) }
  },
  async close() {
    if (this.data.busy) return
    if (this.data.editing) { wx.navigateBack(); return }
    this.setData({ busy: true, error: '' })
    try { await api.skipDietaryProfile(); this.finish() }
    catch (error) { this.setData({ error: error instanceof Error ? error.message : '暂时无法跳过' }) }
    finally { this.setData({ busy: false }) }
  },
  finish() {
    if (this.data.editing) wx.navigateBack()
    else wx.switchTab({ url: '/pages/chat/index' })
  },
})
