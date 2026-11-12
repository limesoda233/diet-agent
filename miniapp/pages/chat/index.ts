import { api, type ChatResponse, type Meal, type MealDraft } from '../../services/api'
import { clearSession, getSession, saveSession } from '../../services/session'

const tagFields = ['mealTime', 'mood', 'scene', 'healthGoal', 'cuisine', 'taste', 'convenience'] as const
type TagField = typeof tagFields[number]
const tagLabels: Record<TagField, string> = { mealTime: '餐次', mood: '心情', scene: '场景',
  healthGoal: '健康目标', cuisine: '菜系', taste: '口味', convenience: '便利程度' }
type DraftTags = Record<TagField, string[]>
const tagsOf = (draft: MealDraft): DraftTags => Object.fromEntries(
  tagFields.map(field => [field, [...(draft.data[field] || [])]])) as DraftTags

type Message = { role: string; text: string; meals: Meal[]; details: string[] }
const welcome = (): Message => ({ role: '小狗', text: '嗨，我是小狗。今天想吃什么？说说餐次和口味，我帮你找找。', meals: [], details: [] })
const fieldNames: Record<string, string> = { name: '菜名', mealTime: '餐次' }

Page({
  data: {
    sourceMode: 'PUBLIC' as 'PUBLIC' | 'PERSONAL', message: '', sending: false,
    messages: [welcome()] as Message[], draft: null as MealDraft | null,
    draftMissing: [] as string[], draftWarnings: [] as string[],
    draftIngredients: [] as string[], draftPreparation: '',
    options: {} as Record<string,string[]>, draftTags: null as DraftTags | null,
    draftGroups: [] as { field: TagField; label: string; choices: { value: string; selected: boolean }[] }[],
  },
  async onLoad() {
    try { this.setData({ options: await api.slotOptions() }); this.renderDraftGroups() }
    catch { /* Selected tags are still available when the option list cannot be loaded. */ }
  },
  renderDraftGroups() {
    const tags = this.data.draftTags
    if (!tags) { this.setData({ draftGroups: [] }); return }
    this.setData({ draftGroups: tagFields.map(field => ({ field, label: tagLabels[field],
      choices: [...new Set([...(this.data.options[field] || []), ...tags[field]])]
        .map(value => ({ value, selected: tags[field].includes(value) })) })) })
  },
  toggleDraftTag(event: { currentTarget: { dataset: { field: TagField; value: string } } }) {
    if (!this.data.draftTags || this.data.sending) return
    const { field, value } = event.currentTarget.dataset
    const old = this.data.draftTags[field]
    this.setData({ draftTags: { ...this.data.draftTags,
      [field]: old.includes(value) ? old.filter(item => item !== value) : [...old, value] } })
    this.renderDraftGroups()
  },
  switchSource(sourceMode: 'PUBLIC' | 'PERSONAL') {
    if (this.data.sending || this.data.sourceMode === sourceMode) return
    this.restart()
    this.setData({ sourceMode })
  },
  restart() {
    if (this.data.sending) return
    clearSession()
    this.setData({ message: '', messages: [welcome()], draft: null,
      draftMissing: [], draftWarnings: [], draftIngredients: [], draftPreparation: '', draftTags: null, draftGroups: [] })
  },
  usePublic() { this.switchSource('PUBLIC') },
  usePersonal() { this.switchSource('PERSONAL') },
  onInput(event: { detail: { value: string } }) { this.setData({ message: event.detail.value }) },
  showReply(reply: ChatResponse) {
    saveSession(reply.sessionId)
    const details = [
      ...(reply.planBlocks || []).map(block => `${block.mealTime}：${block.summary}`),
      ...(reply.mealPlan?.days || []).flatMap(day => day.blocks.map(block =>
        `${day.date} ${block.mealTime}：${block.selectedMeal?.name || block.reason}`)),
      ...(reply.videoBlocks || []).map(video => `教学视频：${video.title} ${video.url}`),
    ]
    const draft = reply.mealDraft || null
    this.setData({ messages: [...this.data.messages, { role: '小狗',
      text: reply.speechText || reply.clarifyQuestion || '请再试一次',
      meals: reply.displayBlocks || [], details }], draft,
      draftMissing: (draft?.missingFields || []).map(field => fieldNames[field] || field),
      draftWarnings: draft?.warnings || [],
      draftIngredients: draft?.data.knowledge?.ingredients || [],
      draftPreparation: draft?.data.knowledge?.preparation || '',
      draftTags: draft && ['READY','NEEDS_INPUT','AWAITING_MERGE'].includes(draft.status) ? tagsOf(draft) : null })
    this.renderDraftGroups()
  },
  async send() {
    const message = this.data.message.trim()
    if (!message || this.data.sending) return
    this.setData({ sending: true, message: '', messages: [...this.data.messages,
      { role: '我', text: message, meals: [], details: [] }] })
    try {
      const reply = await api.chat({ sessionId: getSession(), message,
        sourceMode: this.data.sourceMode, context: {} })
      this.showReply(reply)
    } catch (error) {
      const text = error instanceof Error ? error.message : '服务暂时不可用'
      this.setData({ messages: [...this.data.messages, { role: '系统', text, meals: [], details: [] }] })
    } finally { this.setData({ sending: false }) }
  },
  async handleDraftAction(event: { currentTarget: { dataset: { action: 'SAVE' | 'CANCEL' | 'MERGE' } } }) {
    const draft = this.data.draft
    const sessionId = getSession()
    const action = event.currentTarget.dataset.action
    if (this.data.sending || !draft || !sessionId ||
        !['READY', 'NEEDS_INPUT', 'AWAITING_MERGE'].includes(draft.status) ||
        !['SAVE','CANCEL','MERGE'].includes(action) ||
        (action === 'SAVE' && draft.status !== 'READY') ||
        (action === 'MERGE' && draft.status !== 'AWAITING_MERGE')) return
    if (action !== 'CANCEL' && !this.data.draftTags?.mealTime.length) {
      wx.showToast({ title: '请至少选择一个餐次', icon: 'none' }); return
    }
    this.setData({ sending: true })
    try {
      const reply = await api.chat({ sessionId, sourceMode: this.data.sourceMode,
        message: action === 'SAVE' ? '确认保存' : action === 'MERGE' ? '确认合并' : '取消录入',
        context: { mealDraftAction: { draftId: draft.draftId, action,
          ...(action !== 'CANCEL' ? { tags: this.data.draftTags } : {}) } } })
      this.showReply(reply)
    } catch (error) {
      wx.showToast({ title: error instanceof Error ? error.message : '草稿操作失败', icon: 'none' })
    } finally { this.setData({ sending: false }) }
  },
})
