import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { ClarifyAction, Intent, Trace } from '../../api/types'
import { useApp } from '../../context/AppContext'
import { defaultRange, errorText, parseExpectedSlots, prettyJson, rangeError } from './debugUtils'
import './debug.css'

const INTENTS: Intent[] = ['MEAL_RECOMMENDATION', 'CLARIFY_NEEDED', 'MEAL_ADJUST', 'MEAL_PLAN',
  'RECIPE_QUERY', 'HEALTH_RISK', 'OTHER']

interface EventData {
  stepOrder?: number
  eventType?: string
  phase?: string
  agentName?: string | null
  modelName?: string | null
  inputPayload?: string | null
  outputPayload?: string | null
  latencyMs?: number | null
  errorMessage?: string | null
  createdAt?: string
}

function eventsOf(raw: string | null): EventData[] {
  if (!raw) return []
  try {
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object' || !('events' in value)) return []
    const events = value.events
    return Array.isArray(events) ? events.filter(item => item && typeof item === 'object') as EventData[] : []
  } catch { return [] }
}

function TraceDetail({ trace, onSaved }: { trace: Trace, onSaved: (row: Trace) => void }) {
  const { api } = useApp()
  const [intent, setIntent] = useState<Intent | ''>(trace.expectedIntent ?? '')
  const [clarify, setClarify] = useState<ClarifyAction | ''>(trace.expectedClarifyAction ?? '')
  const [slots, setSlots] = useState(prettyJson(trace.expectedSlots))
  const [note, setNote] = useState(trace.labelNote ?? '')
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)
  const mounted = useRef(true)
  const busy = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  async function saveLabel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy.current) return
    let expectedSlots: ReturnType<typeof parseExpectedSlots>
    try { expectedSlots = parseExpectedSlots(slots) }
    catch (cause) { setError(errorText(cause, '预期槽位无效')); return }
    busy.current = true
    setSaving(true)
    setSaved(false)
    setError('')
    try {
      await api.labelTrace(trace.traceId, { expectedIntent: intent || null, expectedSlots,
        expectedClarifyAction: clarify || null, labelNote: note.trim() })
      if (!mounted.current) return
      const refreshed = await api.getTrace(trace.traceId)
      if (mounted.current) { onSaved(refreshed); setSaved(true) }
    } catch (cause) {
      if (mounted.current) setError(errorText(cause, '标注保存失败，请重试'))
    } finally {
      busy.current = false
      if (mounted.current) setSaving(false)
    }
  }

  const events = eventsOf(trace.traceJson)
  return <section className="panel" role="region" aria-label="Trace 详情">
    <div className="page-heading"><div><h2>Trace 详情</h2><p className="mono">{trace.traceId}</p></div><span className="tag">{trace.status || 'UNKNOWN'}</span></div>
    <p className="muted small">会话：{trace.sessionId || '-'} · 事件：{trace.eventCount ?? '-'} · 耗时：{trace.durationMs ?? '-'} ms</p>
    {trace.errorMessage && <p role="alert">{trace.errorMessage}</p>}
    <h3>事件时间线</h3>
    {events.length ? <ol className="trace-timeline">{events.map((item, index) => <li className="trace-event" key={`${item.stepOrder ?? index}-${index}`}>
      <h3>{item.stepOrder ?? index + 1}. {item.eventType || '事件'}</h3>
      <p className="muted small">{[item.createdAt, item.phase, item.agentName, item.modelName,
        item.latencyMs == null ? null : `${item.latencyMs} ms`].filter(Boolean).join(' · ')}</p>
      {item.errorMessage && <p role="alert">{item.errorMessage}</p>}
      {item.inputPayload != null && <details><summary>输入</summary><pre className="json-box">{prettyJson(item.inputPayload)}</pre></details>}
      {item.outputPayload != null && <details><summary>输出</summary><pre className="json-box">{prettyJson(item.outputPayload)}</pre></details>}
    </li>)}</ol> : <p className="empty">暂无事件数据。</p>}
    <details><summary>完整 Trace JSON</summary><pre className="json-box">{prettyJson(trace.traceJson) || '无'}</pre></details>
    <h3>人工标注</h3>
    <form className="form-grid" onSubmit={event => void saveLabel(event)}>
      <div className="field"><label htmlFor="trace-intent">预期意图</label><select id="trace-intent" value={intent} onChange={event => setIntent(event.target.value as Intent | '')}>
        <option value="">不标注</option>{INTENTS.map(value => <option key={value} value={value}>{value}</option>)}</select></div>
      <div className="field"><label htmlFor="trace-clarify">澄清动作</label><select id="trace-clarify" value={clarify} onChange={event => setClarify(event.target.value as ClarifyAction | '')}>
        <option value="">不标注</option><option value="ASK">ASK</option><option value="READY">READY</option></select></div>
      <div className="field full"><label htmlFor="trace-slots">预期槽位 JSON</label><textarea id="trace-slots" value={slots} onChange={event => setSlots(event.target.value)} placeholder='{"mealTime":["晚餐"]}' /></div>
      <div className="field full"><label htmlFor="trace-note">备注</label><textarea id="trace-note" value={note} onChange={event => setNote(event.target.value)} /></div>
      <div className="field full">{error && <p role="alert">{error}</p>}{saved && <p className="success" role="status">标注已保存</p>}
        <button className="button primary" type="submit" disabled={saving}>{saving ? '保存中...' : '保存标注'}</button></div>
    </form>
  </section>
}

export function TracePage() {
  const { api } = useApp()
  const [searchParams, setSearchParams] = useSearchParams()
  const traceId = searchParams.get('traceId') || ''
  const retry = searchParams.get('retry') || ''
  const [range] = useState(defaultRange)
  const [startAt, setStartAt] = useState(range.startAt)
  const [endAt, setEndAt] = useState(range.endAt)
  const [sessionId, setSessionId] = useState('')
  const [limitText, setLimitText] = useState('50')
  const [onlyUnlabeled, setOnlyUnlabeled] = useState(false)
  const [rows, setRows] = useState<Trace[]>([])
  const [selected, setSelected] = useState<Trace | null>(null)
  const [loading, setLoading] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const mounted = useRef(true)
  const listEpoch = useRef(0)
  const detailEpoch = useRef(0)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; listEpoch.current++; detailEpoch.current++ } }, [])

  const load = useCallback(async (params: { startAt: string, endAt: string, sessionId: string, limit: number, onlyUnlabeled: boolean }) => {
    const epoch = ++listEpoch.current
    setLoading(true)
    setError('')
    try {
      const result = params.sessionId ? await api.listSessionTraces(params.sessionId, params.limit) :
        await api.listTraces({ startAt: params.startAt, endAt: params.endAt,
          onlyUnlabeled: params.onlyUnlabeled, limit: params.limit })
      if (mounted.current && epoch === listEpoch.current) {
        setRows(params.sessionId && params.onlyUnlabeled ? result.filter(row =>
          !row.expectedIntent && !row.expectedSlots && !row.expectedClarifyAction) : result)
      }
    } catch (cause) {
      if (mounted.current && epoch === listEpoch.current) setError(errorText(cause, 'Trace 查询失败，请重试'))
    } finally {
      if (mounted.current && epoch === listEpoch.current) setLoading(false)
    }
  }, [api])

  useEffect(() => { void load({ startAt: range.startAt, endAt: range.endAt, sessionId: '', limit: 50, onlyUnlabeled: false }) }, [load, range])

  useEffect(() => {
    const epoch = ++detailEpoch.current
    setSelected(null)
    setDetailError('')
    if (!traceId) { setDetailLoading(false); return }
    setDetailLoading(true)
    api.getTrace(traceId).then(result => {
      if (mounted.current && epoch === detailEpoch.current) {
        if (result) setSelected(result)
        else setDetailError('找不到这条 Trace')
      }
    }).catch(cause => {
      if (mounted.current && epoch === detailEpoch.current) setDetailError(errorText(cause, 'Trace 详情加载失败'))
    }).finally(() => { if (mounted.current && epoch === detailEpoch.current) setDetailLoading(false) })
  }, [api, traceId, retry])

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const limit = Number(limitText)
    const validation = rangeError(startAt, endAt, limit)
    if (validation) { setError(validation); return }
    void load({ startAt, endAt, sessionId: sessionId.trim(), limit, onlyUnlabeled })
  }

  function handleSaved(row: Trace) {
    if (!mounted.current || row.traceId !== traceId) return
    setSelected(row)
    setRows(previous => previous.map(item => item.traceId === row.traceId ? row : item))
  }

  return <div className="page">
    <header className="page-heading"><div><span className="eyebrow">调试工具</span><h1>Trace 调试</h1>
      <p>按时间或会话查询请求链路，查看事件并标注预期结果。</p></div></header>
    <div className="debug-split">
      <section className="panel">
        <form className="form-grid" onSubmit={search}>
          <div className="field"><label htmlFor="trace-start">开始时间</label><input id="trace-start" type="datetime-local" required value={startAt} onChange={event => setStartAt(event.target.value)} /></div>
          <div className="field"><label htmlFor="trace-end">结束时间</label><input id="trace-end" type="datetime-local" required value={endAt} onChange={event => setEndAt(event.target.value)} /></div>
          <div className="field"><label htmlFor="trace-session">会话 ID（可选）</label><input id="trace-session" value={sessionId} onChange={event => setSessionId(event.target.value)} placeholder="填写后按会话查询" /></div>
          <div className="field"><label htmlFor="trace-limit">数量上限</label><input id="trace-limit" type="number" min="1" max="500" value={limitText} onChange={event => setLimitText(event.target.value)} /></div>
          <div className="field"><label htmlFor="trace-unlabeled">标注状态</label><select id="trace-unlabeled" value={String(onlyUnlabeled)} onChange={event => setOnlyUnlabeled(event.target.value === 'true')}>
            <option value="false">全部</option><option value="true">仅未标注</option></select></div>
          <div className="field"><span>&nbsp;</span><button className="button primary" type="submit" disabled={loading}>{loading ? '查询中...' : '查询 Trace'}</button></div>
        </form>
        {sessionId.trim() && <p className="muted small">按会话查询时，时间范围不参与检索。</p>}
        {error && <p role="alert">{error} <button className="button" type="button" onClick={() => void load({ startAt, endAt, sessionId: sessionId.trim(), limit: Number(limitText), onlyUnlabeled })}>重试</button></p>}
        {loading ? <p role="status">Trace 查询中...</p> : rows.length ? <div className="table-scroll"><table aria-label="Trace 列表"><thead><tr>
          <th>Trace ID</th><th>会话</th><th>状态</th><th>事件</th><th>耗时</th><th>创建时间</th><th>标注</th><th>操作</th>
        </tr></thead><tbody>{rows.map(row => <tr className={row.traceId === traceId ? 'selected-row' : ''} key={row.traceId}>
          <td className="mono">{row.traceId}</td><td>{row.sessionId}</td><td>{row.status || '-'}</td><td>{row.eventCount ?? '-'}</td>
          <td>{row.durationMs == null ? '-' : `${row.durationMs} ms`}</td><td>{row.createdAt || '-'}</td><td>{row.expectedIntent || '未标注'}</td>
          <td><button className="button" type="button" aria-label={`查看 ${row.traceId}`} onClick={() => setSearchParams({ traceId: row.traceId })}>查看</button></td>
        </tr>)}</tbody></table></div> : <p className="empty">暂无 Trace 数据。可以先在聊天页发起几轮对话。</p>}
      </section>
      {detailLoading ? <section className="panel" role="status">Trace 详情加载中...</section> : selected ?
        <TraceDetail key={selected.traceId} trace={selected} onSaved={handleSaved} /> :
        <section className="panel">{detailError ? <p role="alert">{detailError} <button className="button" type="button" onClick={() => {
          setSearchParams({ traceId, retry: String(Date.now()) })
        }}>重试</button></p> : <p className="empty">选择一条 Trace 查看详情和标注表单。</p>}</section>}
    </div>
  </div>
}

export default TracePage
