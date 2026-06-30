import type { ReactNode } from 'react';
import { Truck, UserRound, ShieldX } from 'lucide-react';
import { Section } from '../components/Section.tsx';

function Concept({ children }: { children: string }) {
  return <span className="font-mono text-indigo-700">{children}</span>;
}

const cases: { icon: typeof Truck; title: string; desc: ReactNode }[] = [
  {
    icon: Truck,
    title: '供应链分析',
    desc: 'Agent 跨 PostgreSQL、REST API 和 Kafka 查询订单、库存与物流，统一语义查询让瓶颈定位不再依赖手写 SQL。',
  },
  {
    icon: UserRound,
    title: '客户 360 视角',
    desc: (
      <>
        客户、订单、合同、服务工单被建模为统一 <Concept>Resource</Concept>，
        <Concept>Perspective Engine</Concept> 按 <Concept>Role</Concept> 裁剪返回字段，防止过度授权。
      </>
    ),
  },
  {
    icon: ShieldX,
    title: 'Agent 越权被拒绝',
    desc: (
      <>
        当 Agent 尝试调用需要 <Concept>drop</Concept> 能力的 <Concept>Action</Concept> 时，类型系统直接拒绝该请求，
        并在 <Concept>Event Log</Concept> 留下不可变的审计记录。
      </>
    ),
  },
];

export function UseCasesSection() {
  return (
    <Section id="usecases" className="bg-white">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">应用场景</h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          从只读分析到受控写入，Heirloom 为 AI Agent 提供一致的安全语义界面。
        </p>
      </div>

      <div className="mt-14 grid gap-6 md:grid-cols-3">
        {cases.map((c) => (
          <div
            key={c.title}
            className="rounded-xl border border-stone-200 bg-white p-7 shadow-sm"
          >
            <div className="rounded-lg bg-indigo-50 w-fit p-3">
              <c.icon className="w-6 h-6 text-indigo-700" />
            </div>
            <h3 className="mt-5 text-lg font-semibold text-stone-900">{c.title}</h3>
            <p className="mt-3 text-sm text-stone-600 leading-relaxed">{c.desc}</p>
          </div>
        ))}
      </div>
    </Section>
  );
}
