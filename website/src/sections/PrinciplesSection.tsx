import { Fingerprint, Tag, GitBranch, Network, Users } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const principles = [
  {
    icon: Fingerprint,
    title: '资源是一等实体',
    subtitle: 'RID / Owner / State / Events',
    snippet: 'rid: ri.customer.123',
    desc: '每个业务实体拥有全局唯一、永不随数据源变迁而失效的 RID，以及明确的所有者、状态机和完整事件历史。',
  },
  {
    icon: Tag,
    title: '能力在类型层声明',
    subtitle: 'Abilities: query / mutate / drop / transfer / freeze / copy / store / key',
    snippet: 'abilities: [query, mutate, drop, ...]',
    desc: 'Resource Type 定义时即声明允许发生什么。未声明 drop，则没有任何 Role 能创建删除该类型的 Action。',
  },
  {
    icon: GitBranch,
    title: '状态迁移可证明',
    subtitle: 'State Machine',
    snippet: 'Draft -> Active -> Frozen',
    desc: '状态图在类型层定义，非法迁移在 Action 定义或运行时即被拒绝。Agent 无法将实体推入非法状态。',
  },
  {
    icon: Network,
    title: '关系语义决定生命周期',
    subtitle: 'Ownership / Reference / Association',
    snippet: 'Ownership | Reference | Association',
    desc: '三种精确关系语义取代无差别边。Agent 在执行写操作前即可判断级联后果。',
  },
  {
    icon: Users,
    title: 'Agent 与人类平权',
    subtitle: 'Actor → Role → Capability → Action',
    snippet: 'Actor -> Role -> Capability -> Action',
    desc: '人类、AI Agent、自动化工作流走完全相同的校验链。Agent 的边界即 Role 的边界。',
  },
];

export function PrinciplesSection() {
  return (
    <Section id="principles" className="bg-stone-50">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">五条设计原则</h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          安全不依赖 prompt，而依赖系统的不可逾越边界。
        </p>
      </div>

      <div className="mt-14 grid gap-6 md:grid-cols-2 lg:grid-cols-3">
        {principles.map((p, idx) => {
          const number = String(idx + 1).padStart(2, '0');
          return (
            <div
              key={p.title}
              className="relative rounded-xl border border-stone-200 bg-white p-7 shadow-sm overflow-hidden"
            >
              <span className="absolute top-4 right-4 font-mono text-5xl font-bold text-stone-100 select-none">
                {number}
              </span>
              <div className="relative">
                <div className="rounded-lg bg-indigo-50 w-fit p-3">
                  <p.icon className="w-6 h-6 text-indigo-700" />
                </div>
                <h3 className="mt-5 text-lg font-semibold text-stone-900">{p.title}</h3>
                <p className="mt-1 text-xs font-mono text-violet-700">{p.subtitle}</p>
                <code className="mt-4 block rounded-md bg-stone-50 border border-stone-100 px-3 py-2 text-xs font-mono text-stone-900">
                  {p.snippet}
                </code>
                <p className="mt-4 text-sm text-stone-600 leading-relaxed">{p.desc}</p>
              </div>
            </div>
          );
        })}
      </div>
    </Section>
  );
}
