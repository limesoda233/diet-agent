import { getAuth, getStoredAuth, login } from './services/auth'
import { CLOUD_ENV_ID } from './config'
import { api } from './services/api'

async function showSignedInHome() {
  try {
    const profile = await api.dietaryProfile()
    if (profile.onboardingStatus === 'NEW') {
      wx.reLaunch({ url: '/pages/diet-profile/index?onboarding=1' })
      return
    }
  } catch { /* The profile request can be retried from settings. */ }
  wx.switchTab({ url: '/pages/chat/index' })
}

App({
  async onLaunch() {
    wx.cloud.init({ env: CLOUD_ENV_ID })
    if (getAuth()) { await showSignedInHome(); return }
    const cached = getStoredAuth()
    if (cached) {
      try {
        const updated = await login()
        if (updated.user.id === cached.user.id) {
          await showSignedInHome(); return
        }
      } catch { /* Login page will show the user a retry action. */ }
    }
    wx.reLaunch({ url: '/pages/login/index' })
  },
})
