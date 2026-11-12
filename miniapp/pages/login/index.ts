import { login } from '../../services/auth'
import { api } from '../../services/api'

Page({
  data: { invitationCode: '', loading: false, error: '' },
  onCode(event: { detail: { value: string } }) { this.setData({ invitationCode: event.detail.value }) },
  async submit() {
    if (this.data.loading) return
    this.setData({ loading: true, error: '' })
    try {
      await login(this.data.invitationCode.trim() || undefined)
      const profile = await api.dietaryProfile()
      if (profile.onboardingStatus === 'NEW') wx.reLaunch({ url: '/pages/diet-profile/index?onboarding=1' })
      else wx.switchTab({ url: '/pages/chat/index' })
    } catch (error) {
      this.setData({ error: error instanceof Error ? error.message : '登录失败' })
    } finally { this.setData({ loading: false }) }
  },
})
