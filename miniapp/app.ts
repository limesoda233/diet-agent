import { getAuth, getStoredAuth, login } from './services/auth'
import { CLOUD_ENV_ID } from './config'

App({
  async onLaunch() {
    wx.cloud.init({ env: CLOUD_ENV_ID })
    if (getAuth()) { wx.switchTab({ url: '/pages/chat/index' }); return }
    const cached = getStoredAuth()
    if (cached) {
      try {
        const updated = await login()
        if (updated.user.id === cached.user.id) {
          wx.switchTab({ url: '/pages/chat/index' }); return
        }
      } catch { /* Login page will show the user a retry action. */ }
    }
    wx.reLaunch({ url: '/pages/login/index' })
  },
})
