import { Link } from 'react-router-dom';
import { ArrowUpRight, BookOpen, MessageSquare, Utensils } from 'lucide-react';

export default function HomePage() {
  return <div className="page"><section className="home-welcome"><span className="eyebrow">每天的一餐，从这里开始</span><h1>今天吃什么？<br />聊聊你的想法。</h1><p className="muted">找一顿合口味的饭，看看菜谱，再把喜欢的餐食留在自己的库里。</p><Link className="button primary" to="/diet/chat">开始聊天 <ArrowUpRight size={16} /></Link></section>
    <div className="home-grid">
      <Link to="/diet/chat" className="card"><MessageSquare size={24} /><h2>从想法到一餐</h2><p>说说时间、口味或现有食材。推荐饭菜，查找带来源的菜谱。</p></Link>
      <Link to="/diet/meals/personal" className="card"><Utensils size={24} /><h2>你的餐食手册</h2><p>一句话整理成草稿，核对后保存，也可以继续手动编辑。</p></Link>
      <Link to="/diet/meals/public" className="card"><BookOpen size={24} /><h2>先看看有什么</h2><p>浏览公共餐食，按餐次和标签寻找灵感。</p></Link>
    </div><p className="muted small" style={{ marginTop: 24 }}>菜谱回答会标注本轮来源；教学视频取决于后端是否启用视频搜索。调试工具提供 Trace 与意图评测，离线 Ragas 仍按运行指南执行。</p>
  </div>;
}
