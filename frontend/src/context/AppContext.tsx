import { createContext, Fragment, useCallback, useContext, useMemo, useState } from 'react'
import type { PropsWithChildren } from 'react'
import { createApi } from '../api/client'
import type { Api } from '../api/client'

const USER_KEY = 'diet.userId'

function normalizeUserId(value: string): string {
  return value.trim() || '1'
}

export interface AppContextValue {
  userId: string
  setUserId: (userId: string) => void
  api: Api
  mealVersion: number
  refreshMeals: () => void
}

const AppContext = createContext<AppContextValue | null>(null)

export function AppProvider({ children }: PropsWithChildren) {
  const [userId, setUserIdState] = useState(() => normalizeUserId(localStorage.getItem(USER_KEY) || '1'))
  const [mealVersion, setMealVersion] = useState(0)
  const api = useMemo(() => createApi(userId), [userId])

  const setUserId = useCallback((value: string) => {
    const next = normalizeUserId(value)
    localStorage.setItem(USER_KEY, next)
    if (next !== userId) {
      setUserIdState(next)
      setMealVersion(0)
    }
  }, [userId])

  const refreshMeals = useCallback(() => setMealVersion(version => version + 1), [])
  const value = useMemo(() => ({ userId, setUserId, api, mealVersion, refreshMeals }),
    [userId, setUserId, api, mealVersion, refreshMeals])

  return <AppContext.Provider value={value}><Fragment key={userId}>{children}</Fragment></AppContext.Provider>
}

export function useApp(): AppContextValue {
  const context = useContext(AppContext)
  if (!context) throw new Error('useApp must be used within AppProvider')
  return context
}
