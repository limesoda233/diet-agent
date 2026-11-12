import { useEffect, useState } from 'react';
import { HashRouter, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { BookOpen, Brain, FlaskConical, Home, Menu, MessageSquare, Search, ShieldCheck, Utensils, X } from 'lucide-react';
import dogPortrait from '../assets/dog.jpg';
import { AppProvider, useApp } from '../context/AppContext';
import ChatPage from '../features/chat/ChatPage';
import MealsPage from '../features/meals/MealsPage';
import TracePage from '../features/debug/TracePage';
import EvaluationPage from '../features/debug/EvaluationPage';
import HomePage from './HomePage';
import MemoryPage from '../features/memories/MemoryPage';
import DietaryProfilePage from '../features/profile/DietaryProfilePage';

const navigation = [
  { to: '/diet/chat', label: '聊天助手', Icon: MessageSquare },
  { to: '/diet/meals/personal', label: '个人餐食', Icon: Utensils },
  { to: '/diet/meals/public', label: '公共餐食', Icon: BookOpen },
  { to: '/diet/memories', label: '饮食记忆', Icon: Brain },
  { to: '/diet/profile', label: '饮食限制', Icon: ShieldCheck },
  { to: '/diet', label: '使用概览', Icon: Home },
  { to: '/admin/traces', label: 'Trace 调试', Icon: Search },
  { to: '/admin/evaluations', label: '意图评测', Icon: FlaskConical },
];

function Shell() {
  const { userId, setUserId } = useApp();
  const [identity, setIdentity] = useState(userId);
  const [open, setOpen] = useState(false);
  const [identityError, setIdentityError] = useState('');
  const [viewport, setViewport] = useState<{ height: number; top: number } | null>(null);
  useEffect(() => {
    const visible = window.visualViewport;
    if (!visible) return;
    // Safari and mobile Chrome can shrink only the visible viewport for the keyboard.
    // Preserve browser zoom behavior; resize the shell only at its normal scale.
    const resize = () => { if (visible.scale === 1) setViewport({ height: visible.height, top: visible.offsetTop }); };
    resize();
    visible.addEventListener('resize', resize);
    visible.addEventListener('scroll', resize);
    return () => { visible.removeEventListener('resize', resize); visible.removeEventListener('scroll', resize); };
  }, []);
  const location = useLocation();
  const isChat = location.pathname === '/diet/chat';
  const title = navigation.find(item => item.to === location.pathname)?.label ?? '饮食助手';
  return <div className="app-shell" style={viewport ? { height: viewport.height, top: viewport.top } : undefined}>
    <button aria-label="关闭导航" className={`mobile-overlay ${open ? 'is-open' : ''}`} onClick={() => setOpen(false)} />
    <aside className={`sidebar ${open ? 'is-open' : ''}`}>
      <NavLink to="/diet/chat" className="brand" onClick={() => setOpen(false)}><span className="brand-mark"><img src={dogPortrait} alt="" /></span><span>小狗劝吃</span></NavLink>
      <div className="brand-sub">小狗陪你，好好吃饭</div>
      <nav aria-label="主导航">
        <div className="nav-caption">饮食助手</div>
        <div className="nav-list">{navigation.filter(item => item.to.startsWith('/diet')).map(({ to, label, Icon }) => <NavLink end key={to} to={to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`} onClick={() => setOpen(false)}><Icon size={17} />{label}</NavLink>)}</div>
        <div className="nav-caption">调试工具</div>
        <div className="nav-list">{navigation.filter(item => item.to.startsWith('/admin')).map(({ to, label, Icon }) => <NavLink key={to} to={to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`} onClick={() => setOpen(false)}><Icon size={17} />{label}</NavLink>)}</div>
      </nav>
      <form className="identity" onSubmit={e => {
        e.preventDefault();
        if (!/^[1-9]\d*$/.test(identity.trim()) || !Number.isSafeInteger(Number(identity))) { setIdentityError('请输入正整数用户编号'); return; }
        setIdentityError(''); setUserId(identity.trim()); setOpen(false);
      }}>
        <label htmlFor="identity">当前用户编号</label><div className="identity-row"><input id="identity" value={identity} onChange={e => setIdentity(e.target.value)} inputMode="numeric" /><button type="submit" aria-label="切换用户">切换</button></div>
        {identityError && <p role="alert">{identityError}</p>}<p>练习环境 · 用户编号用于数据隔离，不是身份认证</p>
      </form>
    </aside>
    <div className="main-shell">
      <header className="topbar"><button className="menu-toggle" aria-label="打开导航" aria-expanded={open} onClick={() => setOpen(value => !value)}>{open ? <X size={20} /> : <Menu size={20} />}</button><span className="topbar-title">好好吃饭 <span>/</span> {title}</span><span className="topbar-dot">个人空间 · 用户 {userId}</span></header>
      <main className={`page-host ${isChat ? 'is-chat' : ''}`} key={userId}>
        {/* Keep the current dialogue alive when visiting the library or its Trace. Identity changes remount it. */}
        <div className="chat-route" hidden={!isChat}><ChatPage /></div><Routes>
        <Route path="/diet/chat" element={null} />
        <Route path="/diet/meals/personal" element={<MealsPage />} />
        <Route path="/diet/meals/public" element={<MealsPage publicOnly />} />
        <Route path="/diet/memories" element={<MemoryPage />} />
        <Route path="/diet/profile" element={<DietaryProfilePage />} />
        <Route path="/diet" element={<HomePage />} />
        <Route path="/admin/traces" element={<TracePage />} />
        <Route path="/admin/evaluations" element={<EvaluationPage />} />
        <Route path="*" element={<Navigate to="/diet/chat" replace />} />
      </Routes></main>
    </div>
  </div>;
}

export function App() { return <AppProvider><HashRouter><Shell /></HashRouter></AppProvider>; }
