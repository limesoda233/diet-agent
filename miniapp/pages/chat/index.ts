import { api, type ChatResponse, type Meal, type MealDraft } from '../../services/api'
import { clearSession, getSession, saveSession } from '../../services/session'

type Message = { role: string; text: string; meals: Meal[]; details: string[] }
const welcome = (): Message => ({ role: '小狗', text: '嗨，我是小狗。今天想吃什么？说说餐次和口味，我帮你找找。', meals: [], details: [] })
const fieldNames: Record<string, string> = { name: '菜名', mealTime: '餐次' }

Page({
  data: {
    sourceMode: 'PUBLIC' as 'PUBLIC' | 'PERSONAL', message: '', sending: false,
    messages: [welcome()] as Message[], draft: null as MealDraft | null,
    draftMissing: [] as string[], draftWarnings: [] as string[],
    draftIngredients: [] as string[], draftPreparation: '',
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
      draftMissing: [], draftWarnings: [], draftIngredients: [], draftPreparation: '' })
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
      draftPreparation: draft?.data.knowledge?.preparation || '' })
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
  async handleDraftAction(event: { currentTarget: { dataset: { action: 'SAVE' | 'CANCEL' } } }) {
    const draft = this.data.draft
    const sessionId = getSession()
    const action = event.currentTarget.dataset.action
    if (this.data.sending || !draft || !sessionId ||
        !['READY', 'NEEDS_INPUT'].includes(draft.status) ||
        (action !== 'SAVE' && action !== 'CANCEL') ||
        (action === 'SAVE' && draft.status !== 'READY')) return
    this.setData({ sending: true })
    try {
      const reply = await api.chat({ sessionId, sourceMode: this.data.sourceMode,
        message: action === 'SAVE' ? '确认保存' : '取消录入',
        context: { mealDraftAction: { draftId: draft.draftId, action } } })
      this.showReply(reply)
    } catch (error) {
      wx.showToast({ title: error instanceof Error ? error.message : '草稿操作失败', icon: 'none' })
    } finally { this.setData({ sending: false }) }
  },
})
