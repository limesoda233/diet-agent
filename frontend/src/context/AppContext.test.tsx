import { render, screen, fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { AppProvider, useApp } from './AppContext'

function Consumer() {
  const { userId, setUserId, api, mealVersion, refreshMeals } = useApp()
  return <>
    <output aria-label="user">{userId}</output>
    <output aria-label="version">{mealVersion}</output>
    <output aria-label="api">{typeof api.chat}</output>
    <button onClick={() => setUserId(' 25 ')}>换用户</button>
    <button onClick={refreshMeals}>刷新餐食</button>
  </>
}

beforeEach(() => localStorage.clear())

describe('AppProvider', () => {
  it('normalizes and persists a switched user while refreshing meal data', () => {
    render(<AppProvider><Consumer /></AppProvider>)
    expect(screen.getByLabelText('user')).toHaveTextContent('1')
    fireEvent.click(screen.getByText('刷新餐食'))
    expect(screen.getByLabelText('version')).toHaveTextContent('1')
    fireEvent.click(screen.getByText('换用户'))
    expect(screen.getByLabelText('user')).toHaveTextContent('25')
    expect(screen.getByLabelText('version')).toHaveTextContent('0')
    expect(screen.getByLabelText('api')).toHaveTextContent('function')
    expect(localStorage.getItem('diet.userId')).toBe('25')
  })
})
