import { useCallback, useEffect, useRef, useState } from 'react'
import type { Api } from '../../api/client'
import type { MemoryItem, MemoryPatch, MemorySnapshot, MemoryStance, MemoryType } from '../../api/types'
import { useApp } from '../../context/AppContext'
import './memories.css'

const typeLabels: Record<MemoryType, string> = {
  ALLERGEN: '过敏原', AVOID_INGREDIENT: '忌口食材', TASTE: '口味', CUISINE: '菜系',
  EQUIPMENT: '厨具', MAX_MINUTES: '最长烹饪时间',
}
const stanceLabels: Record<MemoryStance, string> = { LIKE: '喜欢', DISLIKE: '不喜欢', NEUTRAL: '中性' }
function errorText(error: unknown) { return error instanceof Error ? error.message : '操作失败，请重试' }

function MemoryContent({ api }: { api: Api }) {
  const [snapshot, setSnapshot] = useState<MemorySnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<MemoryItem | null>(null)
  const [value, setValue] = useState('')
  const [stance, setStance] = useState<MemoryStance>('NEUTRAL')
  const [formError, setFormError] = useState('')
  const [deleting, setDeleting] = useState<string | null>(null)
  const mounted = useRef(true)
  const loadEpoch = useRef(0)
  const mutationBusy = useRef(false)
  const valueRef = useRef<HTMLInputElement>(null)
  const deleteRef = useRef<HTMLButtonElement>(null)

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; loadEpoch.current++ } }, [])
  useEffect(() => { if (editing) valueRef.current?.focus() }, [editing])
  useEffect(() => { if (deleting) deleteRef.current?.focus() }, [deleting])
  const reload = useCallback(async () => {
    if (mutationBusy.current) return
    const epoch = ++loadEpoch.current
    setLoading(true)
    setError('')
    setEditing(null)
    setDeleting(null)
    try {
      const result = await api.listMemories()
      if (mounted.current && epoch === loadEpoch.current) setSnapshot(result)
    } catch (failure) {
      if (mounted.current && epoch === loadEpoch.current) setError(errorText(failure))
    } finally {
      if (mounted.current && epoch === loadEpoch.current) setLoading(false)
    }
  }, [api])
  useEffect(() => { void reload() }, [reload])

  async function mutate(operation: () => Promise<void>) {
    if (mutationBusy.current || loading) return
    mutationBusy.current = true
    setBusy(true)
    setError('')
    try { await operation() }
    catch (failure) { if (mounted.current) setError(errorText(failure)) }
    finally {
      if (mounted.current) { mutationBusy.current = false; setBusy(false) }
    }
  }

  function patch(item: MemoryItem, payload: MemoryPatch) {
    void mutate(async () => {
      const updated = await api.patchMemory(item.id, payload)
      if (!mounted.current) return
      setSnapshot(previous => previous && ({ ...previous, items: previous.items.flatMap(existing =>
        existing.id !== item.id ? [existing] : updated.status === 'DELETED' ? [] : [updated]) }))
      setEditing(null)
      setDeleting(null)
    })
  }

  return <section className="page memory-page" aria-labelledby="memory-title">
    <header className="page-heading"><div><span className="eyebrow">个人空间</span><h1 id="memory-title">饮食记忆</h1>
      <p>查看、确认和纠正跨会话使用的饮食偏好与限制。</p></div>
      <button type="button" className="button" disabled={loading || busy} onClick={() => { void reload() }}>重新加载</button>
    </header>
    {error && <p role="alert">{error}</p>}
    {loading ? <p role="status" className="muted">正在加载饮食记忆…</p>
      : !snapshot ? null : !snapshot.enabled ? <div className="panel empty">饮食记忆暂未启用</div>
        : <>
          <section className="panel memory-settings" aria-label="记忆收集设置">
            <label className="memory-toggle"><input type="checkbox" checked={snapshot.collectionEnabled} disabled={busy}
              onChange={() => { void mutate(async () => {
                const result = await api.updateMemorySettings(!snapshot.collectionEnabled)
                if (mounted.current) { setSnapshot(result); setEditing(null); setDeleting(null) }
              }) }} />收集新的饮食记忆</label>
            <p className="muted small">关闭收集不会删除已有记忆；如不再需要某条记忆，请单独撤销。</p>
          </section>
          {snapshot.items.length === 0 ? <div className="panel empty"><p>还没有饮食记忆</p><a href="#/diet/chat">去聊天，说说你的饮食偏好</a></div>
            : <div className="memory-list">{snapshot.items.map(item => <article key={item.id} className="card memory-card" aria-label={item.value}>
              <header className="memory-card-heading"><div><span className="muted small">{typeLabels[item.type]}</span><h2>{item.value}{item.type === 'MAX_MINUTES' ? ' 分钟' : ''}</h2></div>
                <span className={`tag ${item.status === 'PENDING' ? 'memory-pending' : ''}`}>{item.status === 'PENDING' ? '待确认' : '已生效'}</span></header>
              <div className="tags"><span className="tag">{item.sourceType === 'INFERRED' ? '推断偏好' : '本人明确表达'}</span>
                {(item.type === 'TASTE' || item.type === 'CUISINE') && <span className="tag">{stanceLabels[item.stance]}</span>}</div>
              <dl className="memory-evidence"><dt>原话证据</dt><dd>{item.evidence || '未提供原话'}</dd></dl>
              {item.sourceType === 'INFERRED' && <p className="muted small">仅作为推荐排序的参考，可编辑或撤销。</p>}
              {item.status === 'PENDING' && <p className="memory-pending-copy small">请核对后确认，确认后才作为长期记忆生效。</p>}
              {editing?.id === item.id ? <form className="memory-editor" onSubmit={event => {
                event.preventDefault()
                if (!value.trim()) { setFormError('请输入记忆内容'); valueRef.current?.focus(); return }
                if (item.type === 'MAX_MINUTES' && (!/^[1-9]\d*$/.test(value.trim()) || !Number.isSafeInteger(Number(value)))) {
                  setFormError('请输入正整数分钟数'); valueRef.current?.focus(); return
                }
                setFormError('')
                patch(item, { version: item.version, action: 'UPDATE', value: value.trim(), stance })
              }}>
                <div className="field"><label htmlFor={`memory-value-${item.id}`}>记忆内容</label>
                  <input ref={valueRef} id={`memory-value-${item.id}`} value={value} disabled={busy} aria-invalid={Boolean(formError)}
                    aria-describedby={formError ? `memory-error-${item.id}` : undefined} onChange={event => setValue(event.target.value)} />
                  {formError && <p id={`memory-error-${item.id}`} role="alert">{formError}</p>}</div>
                {(item.type === 'TASTE' || item.type === 'CUISINE') && <div className="field"><label htmlFor={`memory-stance-${item.id}`}>偏好态度</label>
                  <select id={`memory-stance-${item.id}`} value={stance} disabled={busy} onChange={event => setStance(event.target.value as MemoryStance)}>
                    {Object.entries(stanceLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></div>}
                {item.type === 'ALLERGEN' && <p className="small">请核实过敏原；保存即确认修改后的长期过敏记忆。</p>}
                <div className="actions"><button className="button primary" disabled={busy} type="submit">{item.type === 'ALLERGEN' ? '确认并保存修改' : '保存修改'}</button>
                  <button className="button" disabled={busy} type="button" onClick={() => setEditing(null)}>取消编辑</button></div>
              </form> : deleting === item.id ? <div className="memory-revoke" role="group" aria-label="撤销确认">
                <p>确认撤销“{item.value}”？之后将不再使用这条记忆。</p><div className="actions">
                  <button ref={deleteRef} className="button danger" disabled={busy} onClick={() => patch(item, { version: item.version, action: 'DELETE' })}>确认撤销</button>
                  <button className="button" disabled={busy} onClick={() => setDeleting(null)}>取消撤销</button></div>
              </div> : <div className="actions memory-actions">
                {item.status === 'PENDING' && <button className="button primary" disabled={busy} onClick={() => patch(item, { version: item.version, action: 'CONFIRM' })}>确认长期记忆</button>}
                <button className="button" disabled={busy} onClick={() => { setEditing(item); setValue(item.value); setStance(item.stance); setFormError(''); setDeleting(null) }}>编辑</button>
                <button className="button danger" disabled={busy} onClick={() => { setDeleting(item.id); setEditing(null) }}>撤销</button>
              </div>}
            </article>)}</div>}
        </>}
    {busy && <p className="small muted" role="status">正在更新饮食记忆…</p>}
  </section>
}

export default function MemoryPage() {
  const { api, userId } = useApp()
  return <MemoryContent key={userId} api={api} />
}
