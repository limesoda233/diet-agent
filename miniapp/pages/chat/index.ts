import { api, type Meal } from '../../services/api'
import { getSession, saveSession } from '../../services/session'

Page({
  data: {
    sourceMode: 'PUBLIC' as 'PUBLIC' | 'PERSONAL', message: '', sending: false,
    messages: [{ role: '小狗', text: '嗨，我是小狗。今天想吃什么？说说餐次和口味，我帮你找找。', meals: [], details: [] }] as { role: string; text: string; meals: Meal[]; details: string[] }[],
  },
  usePublic() { this.setData({ sourceMode: 'PUBLIC' }) },
  usePersonal() { this.setData({ sourceMode: 'PERSONAL' }) },
  onInput(event: { detail: { value: string } }) { this.setData({ message: event.detail.value }) },
  async send() {
    const message = this.data.message.trim()
    if (!message || this.data.sending) return
    this.setData({ sending: true, message: '', messages: [...this.data.messages,
      { role: '我', text: message, meals: [], details: [] }] })
    try {
      const reply = await api.chat({ sessionId: getSession(), message,
        sourceMode: this.data.sourceMode, context: {} })
      saveSession(reply.sessionId)
      const details = [
        ...(reply.planBlocks || []).map(block => `${block.mealTime}：${block.summary}`),
        ...(reply.mealPlan?.days || []).flatMap(day => day.blocks.map(block =>
          `${day.date} ${block.mealTime}：${block.selectedMeal?.name || block.reason}`)),
        ...(reply.videoBlocks || []).map(video => `教学视频：${video.title} ${video.url}`),
      ]
      this.setData({ messages: [...this.data.messages, { role: '小狗',
        text: reply.speechText || reply.clarifyQuestion || '请再试一次',
        meals: reply.displayBlocks || [], details }] })
    } catch (error) {
      const text = error instanceof Error ? error.message : '服务暂时不可用'
      this.setData({ messages: [...this.data.messages, { role: '系统', text, meals: [], details: [] }] })
    } finally { this.setData({ sending: false }) }
  },
})
