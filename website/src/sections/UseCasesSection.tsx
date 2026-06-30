import { Truck, UserRound, ShieldX } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const cases = [
  {
    icon: Truck,
    title: '供应链分析',
    desc: 'Agent 跨 PostgreSQL、REST API 和 Kafka 查询订单、库存与物流，统一语义查询让瓶颈定位不再依赖手写 SQL。',
  },
  {
    icon: UserRound,
    title: '客户 360 视角',
    desc: '客户、订单、合同、服务工单被建模为统一 Resource，Perspective Engine 按 Role 裁剪返回字段，防止过度授权。',
  },
  {
    icon: ShieldX,
    title: 'Agent 越权被拒绝',
    desc: '当 Agent 尝试调用需要 drop 能力的 Action 时，类型系统直接拒绝该请求，并在 Event Log 留下不可变的审计记录。',
  },
];

export function UseCasesSection() {
  return (
    <Section id="usecases" className="bg-white">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold text-slate-900">应用场景</h2>
        <p className="mt-4 text-slate-600">
          从只读分析到受控写入，Heirloom 为 AI Agent 提供一致的安全语义界面。
        </p>
      </div>

      <div className="mt-14 grid gap-6 md:grid-cols-3">
        {cases.map((c) => (
          <div
            key={c.title}
            className="rounded-2xl border border-slate-200 bg-white p-7"
          >
            <div className="rounded-lg bg-violet-100 w-fit p-3">
              <c.icon className="w-6 h-6 text-violet-400" />
            </div>
            <h3 className="mt-5 text-lg font-semibold text-slate-900">{c.title}</h3>
            <p className="mt-3 text-sm text-slate-600 leading-relaxed">{c.desc}</p>
          </div>
        ))}
      </div>
    </Section>
  );
}
