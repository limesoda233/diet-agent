import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { VideoResponse } from '../../api/types'

export function safeHttpUrl(value: string): string | null {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null
  } catch {
    return null
  }
}

export function safeBilibiliVideoUrl(value: string): string | null {
  const safe = safeHttpUrl(value)
  if (!safe) return null
  const url = new URL(safe)
  if (url.protocol !== 'https:') return null
  if (!['bilibili.com', 'www.bilibili.com'].includes(url.hostname)) return null
  return /^\/video\/BV[0-9A-Za-z]+\/?$/.test(url.pathname) ? url.href : null
}

function duration(seconds: number | null): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return ''
  const whole = Math.floor(seconds)
  const hh = Math.floor(whole / 3600)
  const mm = Math.floor((whole % 3600) / 60).toString().padStart(2, '0')
  const ss = (whole % 60).toString().padStart(2, '0')
  return hh ? `${hh}:${mm}:${ss}` : `${mm}:${ss}`
}

export function MessageContent({ text, videos = [] }: { text: string; videos?: VideoResponse[] }) {
  const allowedVideos = videos.flatMap(video => {
    const href = safeBilibiliVideoUrl(video.url)
    return href ? [{ ...video, href }] : []
  })

  return <>
    <div className="chat-markdown">
      <ReactMarkdown remarkPlugins={[remarkGfm]} urlTransform={url => safeHttpUrl(url) ?? ''}
        components={{
          a: ({ href, children }) => href
            ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
            : <span>{children}</span>,
          img: () => null,
        }}>{text}</ReactMarkdown>
    </div>
    {allowedVideos.length > 0 && <div className="chat-video-grid" aria-label="教学视频">
      {allowedVideos.map(video => <a key={video.href} className="chat-video-card"
        href={video.href} target="_blank" rel="noopener noreferrer"
        aria-label={`打开视频：${video.title || '做饭教学视频'}`}>
        <span className="chat-video-platform">▶ 哔哩哔哩</span>
        <strong>{video.title || '做饭教学视频'}</strong>
        <small>{video.creator || '未知创作者'}{duration(video.durationSeconds) && ` · ${duration(video.durationSeconds)}`}</small>
        <span>打开视频 ↗</span>
      </a>)}
    </div>}
  </>
}
