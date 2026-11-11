import { login } from '../../services/auth'

Page({
  data: { invitationCode: '', loading: false, error: '' },
  onCode(event: { detail: { value: string } }) { this.setData({ invitationCode: event.detail.value }) },
  async submit() {
    if (this.data.loading) return
    this.setData({ loading: true, error: '' })
    try {
      await login(this.data.invitationCode.trim() || undefined)
      wx.switchTab({ url: '/pages/chat/index' })
    } catch (error) {
      this.setData({ error: error instanceof Error ? error.message : '登录失败' })
    } finally { this.setData({ loading: false }) }
  },
})
