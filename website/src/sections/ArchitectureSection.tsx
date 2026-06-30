import { Database, BrainCircuit, Briefcase, Gavel, ArrowRight } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const layers = [
  {
    id: 'business',
    icon: Briefcase,
    title: '业务世界',
    items: ['AI Agent', 'Workshop', 'REST / GraphQL', 'BI'],
    accent: 'text-emerald-400',
    border: 'border-emerald-200',
    bg: 'bg-emerald-50',
  },
  {
    id: 'semantic',
    icon: BrainCircuit,
    title: '语义中枢',
    items: ['Schema Registry', 'Mapping Engine', 'Query Resolver', 'Perspective Engine'],
    accent: 'text-violet-400',
    border: 'border-violet-500/20',
    bg: 'bg-violet-50/60',
  },
  {
    id: 'data',
    icon: Database,
    title: '数据世界',
    items: ['PostgreSQL', 'REST API', 'Kafka', 'S3'],
    accent: 'text-sky-400',
    border: 'border-sky-200',
    bg: 'bg-sky-50',
  },
];

const governance = [
  'Proposals',
  'Branching',
  'Versioning',
  'Audit',
];

export function ArchitectureSection() {
  return (
    <Section id="architecture" className="bg-slate-50">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold text-slate-900">语义中枢架构</h2>
        <p className="mt-4 text-slate-600">
          语义层不是堆栈中的一层，而是连接数据世界与业务世界的唯一翻译器和安全边界。
        </p>
      </div>

      <div className="mt-14 flex flex-col lg:flex-row gap-8">
        <div className="flex-1 flex flex-col gap-6">
          {layers.map((layer) => (
            <div
              key={layer.id}
              className={`rounded-2xl border ${layer.border} ${layer.bg} p-6`}
            >
              <div className="flex items-center gap-3">
                <layer.icon className={`w-7 h-7 ${layer.accent}`} />
                <h3 className="text-xl font-semibold text-slate-900">{layer.title}</h3>
              </div>
              <div className="mt-4 flex flex-wrap gap-2">
                {layer.items.map((item) => (
                  <span
                    key={item}
                    className="rounded-full border border-slate-300 bg-slate-100 px-3 py-1 text-sm text-slate-700"
                  >
                    {item}
                  </span>
                ))}
              </div>
            </div>
          ))}

          <div className="hidden lg:flex items-center justify-between px-8 text-slate-500">
            <span className="text-sm">数据世界</span>
            <ArrowRight className="w-4 h-4" />
            <span className="text-sm text-violet-400">语义中枢</span>
            <ArrowRight className="w-4 h-4" />
            <span className="text-sm">业务世界</span>
          </div>
        </div>

        <div className="lg:w-72 rounded-2xl border border-amber-200 bg-amber-50 p-6">
          <div className="flex items-center gap-3">
            <Gavel className="w-6 h-6 text-amber-400" />
            <h3 className="text-lg font-semibold text-slate-900">治理纵轴</h3>
          </div>
          <ul className="mt-5 space-y-3">
            {governance.map((item) => (
              <li key={item} className="flex items-center gap-3 text-slate-700">
                <span className="w-1.5 h-1.5 rounded-full bg-amber-400" />
                {item}
              </li>
            ))}
          </ul>
          <p className="mt-6 text-sm text-slate-600">
            Schema 变更遵循 Proposal → Branch → Review → Merge 流程，治理操作本身也受 Abilities 模型约束。
          </p>
        </div>
      </div>
    </Section>
  );
}
