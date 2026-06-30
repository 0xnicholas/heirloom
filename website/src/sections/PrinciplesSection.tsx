import { Fingerprint, Tag, GitBranch, Network, Users } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const principles = [
  {
    icon: Fingerprint,
    title: '业务实体全局统一',
    subtitle: 'RID / Owner / State / Events',
    snippet: 'rid: ri.customer.123',
    desc: '每个客户、订单、合同都有唯一身份和完整生命周期，数据迁移、系统拆分也不影响 Agent 对业务的理解。',
  },
  {
    icon: Tag,
    title: '权限由模型强制约束',
    subtitle: 'Abilities: query / mutate / drop / transfer / freeze / copy / store / key',
    snippet: 'abilities: [query, mutate, drop, ...]',
    desc: '一个数据类型能否被删除、修改或转移，在定义时即已确定。不存在事后临时开后门或配置遗漏的风险。',
  },
  {
    icon: GitBranch,
    title: '业务状态合法可验证',
    subtitle: 'State Machine',
    snippet: 'Draft -> Active -> Frozen',
    desc: '任何状态变更必须符合预定义规则。Agent 无法把已归档合同改回草稿，也不能跳过审批直接生效。',
  },
  {
    icon: Network,
    title: '级联影响一目了然',
    subtitle: 'Ownership / Reference / Association',
    snippet: 'Ownership | Reference | Association',
    desc: '删除客户时，地址是否级联、订单是否断裂，由关系语义自动决定，Agent 不会误伤关联数据。',
  },
  {
    icon: Users,
    title: '统一安全校验链',
    subtitle: 'Actor → Role → Capability → Action',
    snippet: 'Actor -> Role -> Capability -> Action',
    desc: '人类、Agent、自动化工作流走同一套身份→角色→能力→审计链路，安全策略不再因使用者不同而分裂。',
  },
];

export function PrinciplesSection() {
  return (
    <Section id="principles" className="bg-stone-50">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">五条设计原则</h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          把安全从配置和提示词，前移到类型系统本身。
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
