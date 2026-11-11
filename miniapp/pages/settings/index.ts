import { api, authorizeNextDiagnostic } from '../../services/api'
import { clearAuth } from '../../services/auth'

Page({
  data: { displayName: '', isAdmin: false, invitationCode: '' },
  async onShow() {
    try { const me = await api.me(); this.setData({ displayName: me.displayName || '', isAdmin: me.role === 'ADMIN' }) }
    catch { wx.showToast({ title: '加载资料失败', icon: 'none' }) }
  },
  onHide() { this.setData({ invitationCode: '' }) },
  onName(event: { detail: { value: string } }) { this.setData({ displayName: event.detail.value }) },
  async saveName() {
    try { await api.rename(this.data.displayName.trim()); wx.showToast({ title: '已保存' }) }
    catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '保存失败', icon: 'none' }) }
  },
  async createInvitation() {
    try {
      const result = await api.createInvitation()
      this.setData({ invitationCode: result.invitationCode })
    } catch (error) {
      wx.showToast({ title: error instanceof Error ? error.message : '生成邀请码失败', icon: 'none' })
    }
  },
  copyInvitation() {
    if (this.data.invitationCode) wx.setClipboardData({ data: this.data.invitationCode })
  },
  allowDiagnostic() {
    wx.showModal({ title: '授权一次完整诊断',
      content: '下一次请求会记录聊天内容、模型输入输出和错误信息，保存 24 小时。确认创建一次性授权？',
      success: async result => {
        if (!result.confirm) return
        try {
          const consent = await api.consent()
          authorizeNextDiagnostic(consent.token)
          wx.showToast({ title: '已授权下一次请求' })
        } catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '授权失败', icon: 'none' }) }
      },
    })
  },
  async logout() {
    try { await api.logout() } finally { clearAuth(); wx.reLaunch({ url: '/pages/login/index' }) }
  },
  deleteAccount() {
    wx.showModal({ title: '删除账号与个人数据',
      content: '个人餐食、聊天记录、偏好和诊断记录将被删除，且无法恢复。确认删除？',
      confirmText: '确认删除', success: async result => {
        if (!result.confirm) return
        try { await api.deleteAccount(); clearAuth(); wx.reLaunch({ url: '/pages/login/index' }) }
        catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '删除失败', icon: 'none' }) }
      },
    })
  },
})
