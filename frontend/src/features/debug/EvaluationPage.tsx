import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { EvaluationReport } from '../../api/types'
import { useApp } from '../../context/AppContext'
import { defaultRange, errorText, prettyJson, rangeError } from './debugUtils'
import './debug.css'

function score(value: number | null | undefined) { return value == null ? '-' : value.toFixed(2) }

export function EvaluationPage() {
  const { api } = useApp()
  const [range] = useState(defaultRange)
  const [startAt, setStartAt] = useState(range.startAt)
  const [endAt, setEndAt] = useState(range.endAt)
  const [limitText, setLimitText] = useState('50')
  const [includeLlmJudge, setIncludeLlmJudge] = useState(false)
  const [report, setReport] = useState<EvaluationReport | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const mounted = useRef(true)
  const requestEpoch = useRef(0)
  const busy = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; requestEpoch.current++ } }, [])

  async function evaluate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy.current) return
    const limit = Number(limitText)
    const validation = rangeError(startAt, endAt, limit)
    if (validation) { setError(validation); return }
    const epoch = ++requestEpoch.current
    busy.current = true
    setLoading(true)
    setError('')
    try {
      const result = await api.evaluate({ startAt, endAt, limit, includeLlmJudge })
      if (mounted.current && epoch === requestEpoch.current) setReport(result)
    } catch (cause) {
      if (mounted.current && epoch === requestEpoch.current) setError(errorText(cause, '评估失败，请重试'))
    } finally {
      busy.current = false
      if (mounted.current && epoch === requestEpoch.current) setLoading(false)
    }
  }

  return <div className="page">
    <header className="page-heading"><div><span className="eyebrow">调试工具</span><h1>意图评测</h1>
      <p>基于已保存的请求 Trace 生成规则评分、可选 LLM Judge 和反馈指标。</p></div></header>
    <section className="panel"><form className="form-grid" onSubmit={event => void evaluate(event)}>
      <div className="field"><label htmlFor="eval-start">开始时间</label><input id="eval-start" type="datetime-local" required value={startAt} onChange={event => setStartAt(event.target.value)} /></div>
      <div className="field"><label htmlFor="eval-end">结束时间</label><input id="eval-end" type="datetime-local" required value={endAt} onChange={event => setEndAt(event.target.value)} /></div>
      <div className="field"><label htmlFor="eval-limit">数量上限</label><input id="eval-limit" type="number" min="1" max="500" value={limitText} onChange={event => setLimitText(event.target.value)} /></div>
      <div className="field"><label htmlFor="eval-judge">LLM Judge</label><select id="eval-judge" value={String(includeLlmJudge)} onChange={event => setIncludeLlmJudge(event.target.value === 'true')}>
        <option value="false">关闭</option><option value="true">开启</option></select></div>
      <div className="field full">{error && <p role="alert">{error}</p>}<button className="button primary" type="submit" disabled={loading}>{loading ? '评估中...' : '生成评估报告'}</button></div>
    </form></section>
    <section className="panel debug-report">
      {!report ? <p className="empty">暂无报告。选择时间范围后生成评估。</p> : <>
        <div className="metrics"><div className="metric"><span>Trace 总数</span><strong>{report.totalTraces}</strong></div>
          <div className="metric"><span>已标注</span><strong>{report.labeledTraces}</strong></div>
          <div className="metric"><span>平均分</span><strong>{score(report.avgScore)}</strong></div></div>
        <p className="muted small">报告范围：{report.startAt} 至 {report.endAt}</p>
        <h2>指标均值</h2>{Object.entries(report.metricAverages ?? {}).length ?
          <div className="tags">{Object.entries(report.metricAverages).map(([name, value]) =>
            <span className="tag" key={name}>{name}：{score(value)}</span>)}</div> : <p className="muted">暂无指标</p>}
        <h2>Trace 明细</h2>{report.traceResults?.length ? <div className="table-scroll"><table aria-label="评测明细"><thead><tr>
          <th>Trace ID</th><th>会话</th><th>综合分</th><th>规则分</th><th>LLM 分</th><th>反馈分</th><th>指标 / 明细</th>
        </tr></thead><tbody>{report.traceResults.map(row => <tr key={row.traceId}>
          <td className="mono">{row.traceId}</td><td>{row.sessionId}</td><td>{score(row.score)}</td><td>{score(row.ruleScore)}</td>
          <td>{score(row.llmJudgeScore)}</td><td>{score(row.userFeedbackScore)}</td><td><details><summary>查看 JSON</summary>
            <pre className="json-box">{prettyJson({ metrics: row.metrics, detail: row.detail })}</pre></details></td>
        </tr>)}</tbody></table></div> : <p className="empty">暂无 Trace 明细</p>}
      </>}
    </section>
  </div>
}

export default EvaluationPage
