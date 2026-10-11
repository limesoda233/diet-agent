import { useLayoutEffect, useRef, type FormEvent, type KeyboardEvent } from 'react'

interface ComposerProps {
  value: string
  onChange: (value: string) => void
  onSend: () => void
  sending: boolean
}

export function Composer({ value, onChange, onSend, sending }: ComposerProps) {
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const composing = useRef(false)

  useLayoutEffect(() => {
    const input = inputRef.current
    if (!input) return
    input.style.height = 'auto'
    input.style.height = `${Math.min(input.scrollHeight, 152)}px`
  }, [value])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!sending && value.trim()) onSend()
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing ||
      event.keyCode === 229 || composing.current) return
    event.preventDefault()
    if (!sending && value.trim()) onSend()
  }

  return <form className="chat-composer" onSubmit={submit}>
    <label htmlFor="chat-message">消息</label>
    <div className="chat-composer-row">
      <textarea id="chat-message" ref={inputRef} value={value} disabled={sending}
        onChange={event => onChange(event.target.value)} onKeyDown={onKeyDown}
        onCompositionStart={() => { composing.current = true }}
        onCompositionEnd={() => { composing.current = false }}
        placeholder="说说你的用餐时间、口味，或问一道菜怎么做" rows={1} />
      <button type="submit" disabled={sending || !value.trim()}>{sending ? '发送中…' : '发送'}</button>
    </div>
    <p>Enter 发送 · Shift+Enter 换行</p>
  </form>
}
