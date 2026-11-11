import { Link } from 'react-router-dom';
import { ArrowUpRight, BookOpen, MessageSquare, Utensils } from 'lucide-react';
import dogPortrait from '../assets/dog.jpg';

export default function HomePage() {
  return <div className="page"><section className="home-welcome"><div className="home-welcome-copy"><span className="eyebrow">你的饮食搭子 · 小狗</span><h1>今天吃什么？<br />先交给我想想。</h1><p className="muted">说说口味和餐次，小狗帮你从自己的餐食库或公共餐食里找灵感。想问菜谱，也可以直接开口。</p><Link className="button primary" to="/diet/chat">和小狗聊聊 <ArrowUpRight size={16} /></Link></div><div className="home-welcome-art" aria-hidden="true"><img src={dogPortrait} alt="" /><span>耳朵竖好了，听你说。</span></div></section>
    <div className="home-grid">
      <Link to="/diet/chat" className="card"><MessageSquare size={24} /><h2>从想法到一餐</h2><p>说说时间、口味或现有食材。推荐饭菜，查找带来源的菜谱。</p></Link>
      <Link to="/diet/meals/personal" className="card"><Utensils size={24} /><h2>只属于你的餐食库</h2><p>每个人从空库开始，餐食与个人做法由自己维护。可以聊着录入，也可以手动编辑。</p></Link>
      <Link to="/diet/meals/public" className="card"><BookOpen size={24} /><h2>先看看有什么</h2><p>浏览公共餐食，按餐次和标签寻找灵感。</p></Link>
    </div><p className="muted small" style={{ marginTop: 24 }}>菜谱回答会标注本轮来源；教学视频取决于后端是否启用视频搜索。调试工具提供 Trace 与意图评测，离线 Ragas 仍按运行指南执行。</p>
  </div>;
}
