import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, test, vi } from 'vitest'
import DietaryProfilePage from './DietaryProfilePage'

const api = vi.hoisted(() => ({ dietaryProfile: vi.fn(), saveDietaryProfile: vi.fn() }))
vi.mock('../../context/AppContext', () => ({ useApp: () => ({ api }) }))

beforeEach(() => {
  vi.clearAllMocks()
  api.dietaryProfile.mockResolvedValue({ allergens: [], avoidedFoods: [], dislikedFoods: [],
    spicePreference: 'UNSET', spiceNote: '', onboardingStatus: 'NEW' })
  api.saveDietaryProfile.mockImplementation(async value => value)
})

test('dietary settings save selections and user supplied foods', async () => {
  render(<MemoryRouter><DietaryProfilePage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '花生' }))
  fireEvent.click(screen.getByRole('button', { name: '继续' }))
  fireEvent.change(screen.getByRole('textbox', { name: '其他食物或说明' }), { target: { value: '韭菜' } })
  fireEvent.click(screen.getByRole('button', { name: '添加' }))
  fireEvent.click(screen.getByRole('button', { name: '继续' }))
  fireEvent.click(screen.getByRole('button', { name: '完全不吃辣' }))
  fireEvent.click(screen.getByRole('button', { name: '继续' }))
  fireEvent.click(screen.getByRole('button', { name: '折耳根' }))
  fireEvent.click(screen.getByRole('button', { name: '保存饮食限制' }))
  await waitFor(() => expect(api.saveDietaryProfile).toHaveBeenCalledWith(expect.objectContaining({
    allergens: ['花生'], avoidedFoods: ['韭菜'], dislikedFoods: ['折耳根'], spicePreference: 'NEVER',
  })))
})
