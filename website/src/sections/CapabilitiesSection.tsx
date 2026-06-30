import { Boxes, Zap, ListChecks, Scissors, ScrollText } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const groups = [
  {
    icon: Boxes,
    title: '统一语义建模层',
    items: ['Resource Type', 'Property', 'Relationship', 'Abilities', 'State Machine', 'Role'],
  },
  {
    icon: Zap,
    title: '受控操作与计算',
    items: ['Action', 'Function'],
  },
  {
    icon: ListChecks,
    title: '全链路安全校验',
    items: [
      'Auth',
      'Role',
      'Capability',
      'Gate',
      'State',
      'Validate',
      'Execute',
      'Event',
      'Notify',
    ],
  },
  {
    icon: Scissors,
    title: '细粒度数据脱敏',
    items: ['字段级可见性', '按 Role 裁剪', '查询计划阶段注入'],
  },
  {
    icon: ScrollText,
    title: '不可变审计追溯',
    items: ['不可变追加', '成功 / 拒绝均记录', '时序回放'],
  },
];

export function CapabilitiesSection() {
  return (
    <Section id="capabilities" className="bg-white">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">核心能力</h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          Heirloom 让业务语义成为安全边界：任何操作在类型定义阶段即被约束。
        </p>
      </div>

      <div className="mt-14 grid gap-6 md:grid-cols-2 lg:grid-cols-3">
        {groups.map((g) => (
          <div
            key={g.title}
            className="rounded-xl border border-stone-200 bg-white p-7 shadow-sm border-t-4 border-t-indigo-700"
          >
            <div className="flex items-center gap-3">
              <div className="rounded-lg bg-indigo-50 p-2.5">
                <g.icon className="w-5 h-5 text-indigo-700" />
              </div>
              <h3 className="text-lg font-semibold text-stone-900">{g.title}</h3>
            </div>
            <ul className="mt-5 space-y-2">
              {g.items.map((item) => (
                <li key={item} className="flex items-center gap-2 text-sm text-stone-600">
                  <span className="w-1.5 h-1.5 rounded-full bg-indigo-700" />
                  <span className="font-mono text-stone-700">{item}</span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <p className="mt-12 text-center text-sm font-mono text-stone-500">
        业务语义即安全边界。
      </p>
    </Section>
  );
}
