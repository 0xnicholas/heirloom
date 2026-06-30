import { ShieldAlert, Lock, Unlock, CheckCircle2 } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const pitfalls = [
  {
    icon: Lock,
    title: '沙箱模式',
    desc: '不给数据库权限。Agent 只能看不能动，安全但无用，无法真正参与业务操作。',
    tone: 'muted' as const,
  },
  {
    icon: Unlock,
    title: '上帝模式',
    desc: '给数据库直连权限。Agent 什么都能做，有用但危险；一次幻觉就可能 DROP TABLE。',
    tone: 'danger' as const,
  },
];

export function ProblemSection() {
  return (
    <Section id="problem" className="bg-white">
      <div className="max-w-4xl mx-auto text-center">
        <div className="inline-flex items-center gap-2 text-sm font-medium text-amber-700">
          <ShieldAlert className="w-4 h-4" />
          <span>AI 数据访问的两难困境</span>
        </div>
        <h2 className="mt-4 text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">
          不是 Agent 不可信，是数据界面缺了一层
        </h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          当前企业给 Agent 的数据访问只有两端：完全禁止或完全开放。Heirloom 是中间缺失的语义接口。
        </p>
      </div>

      <div className="mt-14 grid gap-6 md:grid-cols-2">
        {pitfalls.map((item) => (
          <div
            key={item.title}
            className="rounded-xl border border-stone-200 bg-white p-8 shadow-sm"
          >
            <item.icon
              className={`w-10 h-10 ${item.tone === 'danger' ? 'text-amber-700' : 'text-stone-500'}`}
            />
            <h3 className="mt-5 text-xl font-semibold text-stone-900">{item.title}</h3>
            <p className="mt-3 text-stone-600 leading-relaxed">{item.desc}</p>
          </div>
        ))}
      </div>

      <div className="mt-8 rounded-xl border border-amber-200 bg-amber-50 p-8">
        <div className="flex items-start gap-4">
          <div className="shrink-0 rounded-full bg-amber-100 p-3">
            <CheckCircle2 className="w-7 h-7 text-amber-700" />
          </div>
          <div>
            <h3 className="text-xl font-semibold text-stone-900">Heirloom：类型级安全的语义接口</h3>
            <p className="mt-3 text-stone-700 leading-relaxed">
              Agent 不直接访问数据库，而操作具有 <span className="font-mono text-indigo-700">RID</span>、
              <span className="font-mono text-indigo-700">Owner</span>、
              <span className="font-mono text-indigo-700">Abilities</span> 和
              <span className="font-mono text-indigo-700">状态机</span> 的{' '}
              <span className="font-mono text-stone-900">Resource</span>。
              有害操作在类型系统中即不可表达，而不是依赖 prompt 或人的谨慎。
            </p>
          </div>
        </div>
      </div>
    </Section>
  );
}
