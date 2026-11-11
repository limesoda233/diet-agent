import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ChatResponse, Meal, MealDraft, SourceMode } from '../../api/types'
import { useApp } from '../../context/AppContext'
import { Composer } from './Composer'
import { MessageContent } from './MessageContent'
import { MealPlanningCards } from './MealPlanningCards'
import dogPortrait from '../../assets/dog.jpg'

type Message = {
  id: number
  role: 'assistant' | 'user'
  text: string
  response?: ChatResponse
  sessionId?: string
  revision?: number
  userId?: string
}

const slotLabels: Record<string, string> = {
  name: '餐食名称', mealTime: '用餐时间', mood: '心情状态', scene: '用餐场景',
  healthGoal: '健康目标', cuisine: '菜系偏好', taste: '口味偏好', convenience: '便利程度',
}
const slotKeys = ['mealTime', 'mood', 'scene', 'healthGoal', 'cuisine', 'taste', 'convenience'] as const
const welcome = '嗨，我是小狗。今天吃什么，交给我一起琢磨。你可以从自己的餐食库或公共餐食里找推荐，也可以问我菜谱做法。'
const quickQuestions = ['早餐想吃方便一点', '晚饭推荐清淡低脂的', '帮我安排今天三餐', '宫保鸡丁怎么做？', '换一批，不想吃刚才那些']

function initialMessages(): Message[] { return [{ id: 0, role: 'assistant', text: welcome }] }
function errorText(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

function DraftCard({ draft, actionable, onAction }: {
  draft: MealDraft
  actionable: boolean
  onAction: (action: 'SAVE' | 'CANCEL') => void
}) {
  const missing = (draft.missingFields ?? []).map(key => slotLabels[key] ?? key)
  const tags: string[] = []
  if (draft.data?.name) tags.push(draft.data.name)
  for (const key of slotKeys) {
    for (const value of draft.data?.[key] ?? []) tags.push(`${slotLabels[key]}：${value}`)
  }
  for (const ingredient of draft.data?.knowledge?.ingredients ?? []) tags.push(ingredient)
  const status = draft.status === 'SAVED' ? `已保存，餐食 ID：${draft.savedMealId ?? ''}`
    : draft.status === 'CANCELLED' ? '已取消录入'
      : missing.length ? `待补充：${missing.join('、')}` : '请核对草稿后确认保存'

  return <section className="chat-draft" role="region" aria-label="餐食录入草稿">
    <strong>{status}</strong>
    {tags.length > 0 && <div className="chat-chips">{tags.map((tag, index) => <span key={`${tag}-${index}`}>{tag}</span>)}</div>}
    <dl className="chat-draft-review">
      <div><dt>原话</dt><dd>{draft.originalText || '未提供'}</dd></div>
      <div><dt>描述</dt><dd>{draft.data?.knowledge?.description || '未提供'}</dd></div>
      <div><dt>做法</dt><dd>{draft.data?.knowledge?.preparation || '未提供'}</dd></div>
    </dl>
    {missing.length > 0 && <p>请在聊天框补充{missing.join('、')}，再确认保存。</p>}
    {(draft.warnings ?? []).map((warning, index) => <p className="chat-warning" key={index}>{warning}</p>)}
    {(draft.status === 'READY' || draft.status === 'NEEDS_INPUT') && <div className="chat-card-actions">
      {draft.status === 'READY' && <button type="button" disabled={!actionable} onClick={() => onAction('SAVE')}>确认保存</button>}
      <button type="button" className="secondary" disabled={!actionable} onClick={() => onAction('CANCEL')}>取消录入</button>
    </div>}
  </section>
}

function MealCard({ meal, feedback, onFeedback }: {
  meal: Meal
  feedback?: { pending: boolean; result?: string }
  onFeedback?: (meal: Meal, action: 'LIKE' | 'ADOPT' | 'DISLIKE') => void
}) {
  const tags = slotKeys.flatMap(key => (meal[key] ?? []).map(value => `${slotLabels[key]}：${value}`))
  return <article className="chat-meal-card" aria-label={meal.name}>
    <header><h4>{meal.name}</h4><small>{meal.sourceType === 'PERSONAL' ? '个人餐食' : '公共餐食'}</small></header>
    {tags.length > 0 && <div className="chat-chips">{tags.map((tag, index) => <span key={`${tag}-${index}`}>{tag}</span>)}</div>}
    {onFeedback && <div className="chat-card-actions">
      <button type="button" disabled={feedback?.pending || feedback?.result === '反馈已记录'} onClick={() => onFeedback(meal, 'LIKE')}>喜欢</button>
      <button type="button" className="secondary" disabled={feedback?.pending || feedback?.result === '反馈已记录'} onClick={() => onFeedback(meal, 'ADOPT')}>采纳</button>
      <button type="button" className="secondary" disabled={feedback?.pending || feedback?.result === '反馈已记录'} onClick={() => onFeedback(meal, 'DISLIKE')}>不合适</button>
    </div>}
    {feedback?.result && <p className={feedback.result === '反馈已记录' ? 'chat-success' : 'chat-warning'} role="status">{feedback.result}</p>}
  </article>
}

export function ChatPage() {
  const { userId, api, refreshMeals } = useApp()
  const [sourceMode, setSourceMode] = useState<SourceMode>('PERSONAL')
  const [sessionId, setSessionId] = useState<string | null>(null)
  const sessionRef = useRef<string | null>(null)
  const [messages, setMessages] = useState<Message[]>(initialMessages)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const sendingRef = useRef(false)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState<Record<string, { pending: boolean; result?: string }>>({})
  const [showLatest, setShowLatest] = useState(false)
  const logRef = useRef<HTMLDivElement>(null)
  const nearBottomRef = useRef(true)
  const epochRef = useRef(0)
  const revisionRef = useRef(0)
  const nextMessageId = useRef(1)
  const userRef = useRef(userId)
  userRef.current = userId

  useEffect(() => () => { epochRef.current++ }, [])
  useEffect(() => {
    resetConversation()
    // User identity changes must clear the prior conversation even without a keyed parent.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId])

  useLayoutEffect(() => {
    const log = logRef.current
    if (log && nearBottomRef.current) log.scrollTop = log.scrollHeight
    setShowLatest(!nearBottomRef.current)
  }, [messages])

  function current(epoch: number, owner: string) {
    return epochRef.current === epoch && userRef.current === owner
  }

  function resetConversation(nextSource?: SourceMode) {
    epochRef.current++
    revisionRef.current = 0
    sessionRef.current = null
    sendingRef.current = false
    nearBottomRef.current = true
    setSessionId(null)
    setMessages(initialMessages())
    setInput('')
    setSending(false)
    setError('')
    setFeedback({})
    setShowLatest(false)
    if (nextSource) setSourceMode(nextSource)
  }

  async function sendMessage() {
    const text = input.trim()
    if (!text || sendingRef.current) return
    const epoch = epochRef.current
    const owner = userId
    const source = sourceMode
    sendingRef.current = true
    setSending(true)
    setError('')
    try {
      let session = sessionRef.current
      if (!session) {
        session = (await api.createSession()).sessionId
        if (!current(epoch, owner)) return
        sessionRef.current = session
        setSessionId(session)
      }
      const response = await api.chat({ sessionId: session, message: text, sourceMode: source, context: {} })
      if (!current(epoch, owner)) return
      const actualSession = response.sessionId || session
      sessionRef.current = actualSession
      setSessionId(actualSession)
      const revision = ++revisionRef.current
      setMessages(items => [...items,
        { id: nextMessageId.current++, role: 'user', text },
        { id: nextMessageId.current++, role: 'assistant', text: response.clarifyQuestion || response.speechText || '已处理这轮请求。',
          response, sessionId: actualSession, revision, userId: owner },
      ])
      setInput('')
      if (response.mealDraft?.status === 'SAVED') refreshMeals()
    } catch (failure) {
      if (current(epoch, owner)) setError(errorText(failure, '聊天请求失败，请重试。'))
    } finally {
      if (current(epoch, owner)) {
        sendingRef.current = false
        setSending(false)
      }
    }
  }

  async function actOnDraft(message: Message, action: 'SAVE' | 'CANCEL') {
    const draft = message.response?.mealDraft
    const session = message.sessionId
    if (!draft?.draftId || !session || sendingRef.current || message.userId !== userId ||
      session !== sessionRef.current || message.revision !== revisionRef.current ||
      (action === 'SAVE' && draft.status !== 'READY') ||
      (action === 'CANCEL' && draft.status !== 'READY' && draft.status !== 'NEEDS_INPUT')) return
    const epoch = epochRef.current
    const owner = userId
    sendingRef.current = true
    setSending(true)
    setError('')
    try {
      const response = await api.chat({ sessionId: session, message: action === 'SAVE' ? '确认保存' : '取消录入',
        sourceMode, context: { mealDraftAction: { draftId: draft.draftId, action } } })
      if (!current(epoch, owner)) return
      const actualSession = response.sessionId || session
      sessionRef.current = actualSession
      setSessionId(actualSession)
      setMessages(items => [...items, { id: nextMessageId.current++, role: 'assistant',
        text: response.clarifyQuestion || response.speechText || '草稿操作已处理。',
        response, sessionId: actualSession, revision: ++revisionRef.current, userId: owner }])
      if (response.mealDraft?.status === 'SAVED') refreshMeals()
    } catch (failure) {
      if (current(epoch, owner)) setError(errorText(failure, '草稿操作失败，请重试。'))
    } finally {
      if (current(epoch, owner)) {
        sendingRef.current = false
        setSending(false)
      }
    }
  }

  async function recordFeedback(message: Message, meal: Meal, action: 'LIKE' | 'ADOPT' | 'DISLIKE', taskId?: string) {
    if (!message.sessionId || message.userId !== userId || message.sessionId !== sessionRef.current) return
    const plan = message.response?.mealPlan
    if (taskId && (!plan?.planId || !Number.isInteger(plan.version) || plan.version < 1 || !message.response?.traceId)) return
    const key = feedbackKey(message, meal, taskId)
    if (feedback[key]?.pending || feedback[key]?.result === '反馈已记录') return
    const epoch = epochRef.current
    const owner = userId
    setFeedback(previous => ({ ...previous, [key]: { pending: true } }))
    try {
      await api.saveFeedback({ sessionId: message.sessionId, itemId: meal.id, action,
        ...(message.response?.traceId ? { traceId: message.response.traceId } : {}),
        ...(taskId && plan?.planId ? { planId: plan.planId, planVersion: plan.version, taskId } : {}),
        rating: action === 'DISLIKE' ? 2 : 5, reason: '' })
      if (current(epoch, owner)) setFeedback(previous => ({ ...previous, [key]: { pending: false, result: '反馈已记录' } }))
    } catch (failure) {
      if (current(epoch, owner)) setFeedback(previous => ({ ...previous,
        [key]: { pending: false, result: errorText(failure, '反馈提交失败，请重试。') } }))
    }
  }

  function feedbackKey(message: Message, meal: Meal, taskId?: string) {
    const plan = message.response?.mealPlan
    return taskId ? `${message.id}:${plan?.planId}:${plan?.version}:${taskId}:${meal.id}` : `${message.id}:${meal.id}`
  }

  function renderMeal(message: Message, meal: Meal, taskId?: string, actionable = true) {
    const key = feedbackKey(message, meal, taskId)
    return <MealCard key={key} meal={meal} feedback={feedback[key]}
      onFeedback={actionable ? (selected, action) => { void recordFeedback(message, selected, action, taskId) } : undefined} />
  }

  function renderMessage(message: Message) {
    const response = message.response
    const latestDraft = response?.mealDraft && message.revision === revisionRef.current &&
      message.userId === userId && message.sessionId === sessionId && !sending
    return <article key={message.id} className={`chat-message ${message.role}`}>
      {message.role === 'assistant' && <div className="chat-message-author"><img src={dogPortrait} alt="" /><strong>小狗</strong><span>饮食助手</span></div>}
      <div className="chat-bubble">{message.role === 'assistant'
        ? <MessageContent text={message.text} videos={response?.videoBlocks} />
        : <p>{message.text}</p>}</div>
      {response?.memoryNotices?.length ? <aside className="chat-memory-notices" role="status" aria-label="饮食记忆提示">
        {response.memoryNotices.map((notice, index) => <p key={index}>{notice}</p>)}
        <a href="#/diet/memories">管理饮食记忆</a>
      </aside> : null}
      {response?.missingSlots?.length ? <div className="chat-chips">{response.missingSlots.map(slot =>
        <span key={slot}>{slotLabels[slot] ?? slot}</span>)}</div> : null}
      {response?.mealPlan ? <MealPlanningCards plan={response.mealPlan} canFeedback={Boolean(response.traceId)}
        renderMeal={(meal, taskId, actionable) => renderMeal(message, meal, taskId, actionable)} />
        : response?.planBlocks?.length ? <div className="chat-meal-plan">{response.planBlocks.map((plan, index) =>
        <section key={`${plan.mealTime}-${index}`}>
          <h3>{plan.mealTime || '餐次'}</h3>
          {plan.summary && <p>{plan.summary}</p>}
          {plan.meals?.length ? <div className="chat-meals">{plan.meals.map(meal => renderMeal(message, meal))}</div>
            : <p>该餐次暂时没有匹配餐食。</p>}
        </section>)}</div>
        : response?.displayBlocks?.length ? <div className="chat-meals">{response.displayBlocks.map(meal => renderMeal(message, meal))}</div> : null}
      {response?.mealDraft && <DraftCard draft={response.mealDraft} actionable={Boolean(latestDraft)}
        onAction={action => { void actOnDraft(message, action) }} />}
      {response?.traceId && <div className="chat-message-meta">Trace：<a href={`#/admin/traces?traceId=${encodeURIComponent(response.traceId)}`}>{response.traceId}</a></div>}
    </article>
  }

  function onLogScroll() {
    const log = logRef.current
    if (!log) return
    nearBottomRef.current = log.scrollHeight - log.scrollTop - log.clientHeight < 96
    setShowLatest(!nearBottomRef.current)
  }

  function jumpToLatest() {
    const log = logRef.current
    if (log) log.scrollTop = log.scrollHeight
    nearBottomRef.current = true
    setShowLatest(false)
  }

  return <section className="chat-page" aria-label="聊天推荐">
    <header className="chat-header">
      <div className="chat-header-identity"><span className="chat-header-avatar"><img src={dogPortrait} alt="" /></span><div><span className="chat-eyebrow">小狗 · 你的饮食搭子</span><h1>今天想吃什么？</h1>
        <p>先说说你的想法，我认真听。{sessionId ? `会话 ${sessionId}` : ''}</p></div></div>
      <div className="chat-header-actions" aria-label="聊天设置">
        <div className="chat-source" role="group" aria-label="餐食来源">
          <button type="button" aria-pressed={sourceMode === 'PERSONAL'} onClick={() => sourceMode !== 'PERSONAL' && resetConversation('PERSONAL')}>个人库</button>
          <button type="button" aria-pressed={sourceMode === 'PUBLIC'} onClick={() => sourceMode !== 'PUBLIC' && resetConversation('PUBLIC')}>公共库</button>
        </div>
        <button type="button" className="chat-new-session" onClick={() => resetConversation()}>新对话</button>
      </div>
    </header>
    <div className="chat-log-wrap">
      <div className="chat-log" role="log" aria-label="聊天消息" ref={logRef} onScroll={onLogScroll}>
        {messages.map(renderMessage)}
      </div>
      {showLatest && <button type="button" className="chat-latest" onClick={jumpToLatest}>回到最新</button>}
    </div>
    <div className="chat-bottom">
      {error && <p className="chat-error" role="alert">{error}</p>}
      <div className="chat-quick" aria-label="快捷问题">{quickQuestions.map(question =>
        <button type="button" key={question} disabled={sending} onClick={() => setInput(question)}>{question}</button>)}</div>
      <Composer value={input} onChange={setInput} onSend={() => { void sendMessage() }} sending={sending} />
    </div>
  </section>
}

export default ChatPage
