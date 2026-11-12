import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useApp } from '../../context/AppContext'
import type { DietaryProfile } from '../../api/types'
import dogPortrait from '../../assets/dog.jpg'
import './dietary-profile.css'

type FoodField = 'allergens' | 'avoidedFoods' | 'dislikedFoods'
const questions = [
  { title: '有哪些食物会让你过敏？', hint: '仅填写明确知道的过敏原。', field: 'allergens' as const,
    choices: ['花生', '牛奶', '鸡蛋', '小麦', '虾', '蟹', '鱼', '大豆', '坚果', '芝麻'] },
  { title: '有哪些食物你不吃？', hint: '写下忌口，和过敏原分开记录。', field: 'avoidedFoods' as const,
    choices: ['香菜', '牛肉', '猪肉', '羊肉', '海鲜', '内脏', '菌菇'] },
  { title: '你平时吃辣吗？', hint: '这会影响小狗推荐的辣味标签。', field: 'spicePreference' as const,
    choices: ['完全不吃辣', '微辣可以', '都可以', '暂不确定'] },
  { title: '有没有你不喜欢的食物？', hint: '例如榴莲、折耳根。', field: 'dislikedFoods' as const,
    choices: ['榴莲', '折耳根', '香菜', '苦瓜', '芹菜', '茄子'] },
]
const spiceValues: DietaryProfile['spicePreference'][] = ['NEVER', 'MILD', 'OK', 'UNSET']

export default function DietaryProfilePage() {
  const { api } = useApp()
  const [profile, setProfile] = useState<DietaryProfile | null>(null)
  const [step, setStep] = useState(0)
  const [custom, setCustom] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    let live = true
    void api.dietaryProfile().then(value => { if (live) setProfile(value) })
      .catch(failure => { if (live) setError(failure instanceof Error ? failure.message : '加载失败') })
    return () => { live = false }
  }, [api])

  if (!profile) return <main className="dietary-page"><p role={error ? 'alert' : 'status'}>{error || '正在加载饮食限制...'}</p></main>
  const currentProfile = profile
  const question = questions[step]
  const selected = question.field === 'spicePreference' ? [question.choices[spiceValues.indexOf(profile.spicePreference)]] : profile[question.field]
  const choices = question.field === 'spicePreference' ? question.choices : [...new Set([...question.choices, ...selected])]
  function toggle(label: string) {
    if (question.field === 'spicePreference') setProfile({ ...currentProfile, spicePreference: spiceValues[question.choices.indexOf(label)] })
    else {
      const field: FoodField = question.field
      setProfile({ ...currentProfile, [field]: currentProfile[field].includes(label) ? currentProfile[field].filter(item => item !== label) : [...currentProfile[field], label] })
    }
    setSaved(false)
  }
  function addCustom() {
    const value = custom.trim()
    if (question.field === 'spicePreference') { setError(''); return }
    if (!value || value.length > 64) { setError('请填写 1 到 64 个字'); return }
    const field: FoodField = question.field
    setProfile({ ...currentProfile, [field]: [...new Set([...currentProfile[field], value])] })
    setCustom('')
    setError('')
    setSaved(false)
  }
  async function save() {
    setBusy(true)
    setError('')
    try {
      const result = await api.saveDietaryProfile({ ...currentProfile, onboardingStatus: 'COMPLETED' })
      setProfile(result)
      setSaved(true)
    } catch (failure) { setError(failure instanceof Error ? failure.message : '保存失败') }
    finally { setBusy(false) }
  }
  return <main className="dietary-page">
    <div className="dietary-top"><Link to="/diet/chat" aria-label="关闭饮食限制">×</Link>
      <div className="dietary-progress">{questions.map((_, index) => <span key={index} className={index <= step ? 'filled' : ''} />)}</div>
      <small>{step + 1} / {questions.length}</small></div>
    <section className="dietary-content"><img src={dogPortrait} alt="小狗" />
      <p className="meal-eyebrow">小狗先了解一下你</p><h1>{question.title}</h1><p>{question.hint}</p>
      <div className="dietary-choices" role="group" aria-label={question.title}>{choices.map(label =>
        <button key={label} type="button" aria-pressed={selected.includes(label)} onClick={() => toggle(label)}>{label}<span>{selected.includes(label) ? '✓' : ''}</span></button>)}</div>
      <div className="dietary-custom"><input aria-label="其他食物或说明" value={step === 2 ? profile.spiceNote : custom}
        onChange={event => step === 2 ? setProfile({ ...currentProfile, spiceNote: event.target.value }) : setCustom(event.target.value)}
        placeholder={step === 2 ? '补充你的辣度习惯' : '其他？自己填写'} />
        {step !== 2 && <button type="button" onClick={addCustom}>添加</button>}</div>
      {step === 2 && profile.spiceNote && <p>补充：{profile.spiceNote}</p>}
      {step === 0 && <p className="dietary-safety">餐食缺少完整配料信息时，小狗无法判断过敏安全，请以实际配料为准。</p>}
      {error && <p role="alert" className="dietary-error">{error}</p>}
      {saved && <p role="status">饮食限制已保存。</p>}
    </section>
    <div className="dietary-footer">{step > 0 && <button type="button" className="back" onClick={() => { setStep(step - 1); setCustom('') }}>上一步</button>}
      <button type="button" disabled={busy} onClick={() => {
        if (custom.trim()) { setError('请先点击“添加”，保存这条补充内容'); return }
        setError('')
        if (step < questions.length - 1) { setStep(step + 1); setCustom('') } else void save()
      }}>
        {busy ? '保存中...' : step === questions.length - 1 ? '保存饮食限制' : '继续'}</button></div>
  </main>
}
