import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MessageContent } from './MessageContent'

describe('message content', () => {
  it('renders cited recipe Markdown as text without executing raw HTML or unsafe links', () => {
    render(<MessageContent text={'## 做法\n\n番茄切块 [S1]。\n\n<script>alert(1)</script>\n\n[危险](javascript:alert(1)) [来源](https://example.org/recipe)'} />)
    expect(screen.getByRole('heading', { name: '做法' })).toBeInTheDocument()
    expect(screen.getByText(/番茄切块 \[S1\]/)).toBeInTheDocument()
    expect(document.querySelector('script')).toBeNull()
    expect(screen.queryByRole('link', { name: '危险' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '来源' })).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('shows only allowed Bilibili video links in separate tabs', () => {
    render(<MessageContent text="教学视频" videos={[
      { title: '清蒸鱼教程', url: 'https://www.bilibili.com/video/BV123abc', platform: 'BILIBILI', creator: '小厨', durationSeconds: 125 },
      { title: '冒充视频', url: 'https://www.bilibili.com.evil.test/video/BV123abc', platform: 'BILIBILI', creator: '未知', durationSeconds: 10 },
    ]} />)
    const link = screen.getByRole('link', { name: /清蒸鱼教程/ })
    expect(link).toHaveAttribute('href', 'https://www.bilibili.com/video/BV123abc')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.queryByRole('link', { name: /冒充视频/ })).not.toBeInTheDocument()
  })
})
