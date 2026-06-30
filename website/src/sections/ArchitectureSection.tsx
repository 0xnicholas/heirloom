import { Database, BrainCircuit, Briefcase, Gavel, ChevronDown } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const layers = [
  {
    id: 'business',
    icon: Briefcase,
    title: '业务世界',
    items: ['AI Agent', 'Workshop', 'REST / GraphQL', 'BI'],
    accent: 'text-emerald-700',
    border: 'border-emerald-200',
    edge: 'border-l-emerald-700',
  },
  {
    id: 'semantic',
    icon: BrainCircuit,
    title: '语义中枢',
    items: ['Schema Registry', 'Mapping Engine', 'Query Resolver', 'Perspective Engine'],
    accent: 'text-indigo-700',
    border: 'border-indigo-200',
    edge: 'border-l-indigo-700',
  },
  {
    id: 'data',
    icon: Database,
    title: '数据世界',
    items: ['PostgreSQL', 'REST API', 'Kafka', 'S3'],
    accent: 'text-sky-700',
    border: 'border-sky-200',
    edge: 'border-l-sky-700',
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
    <Section id="architecture" className="bg-stone-50">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">语义中枢架构</h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          语义层不是堆栈中的一层，而是连接数据世界与业务世界的唯一翻译器和安全边界。
        </p>
      </div>

      <div className="mt-14 flex flex-col lg:flex-row gap-8 max-w-6xl mx-auto">
        <aside className="lg:w-56 shrink-0">
          <div className="relative rounded-xl border border-amber-200 bg-white p-6 shadow-sm h-full">
            <div className="absolute left-6 top-16 bottom-8 w-px border-l border-dashed border-amber-200" />
            <div className="flex items-center gap-3 relative">
              <Gavel className="w-6 h-6 text-amber-700" />
              <h3 className="text-lg font-semibold text-stone-900">治理纵轴</h3>
            </div>
            <ul className="mt-6 space-y-4 relative">
              {governance.map((item) => (
                <li key={item} className="flex items-center gap-3 text-stone-700 pl-1">
                  <span className="w-2 h-2 rounded-full bg-amber-700 ring-4 ring-white" />
                  <span className="font-mono text-sm">{item}</span>
                </li>
              ))}
            </ul>
            <p className="mt-6 text-sm text-stone-600 leading-relaxed">
              Schema 变更遵循 Proposal → Branch → Review → Merge 流程，治理操作本身也受 Abilities 模型约束。
            </p>
          </div>
        </aside>

        <div className="flex-1 flex flex-col gap-4">
          {layers.map((layer) => (
            <div key={layer.id}>
              <div
                className={`rounded-xl border ${layer.border} ${layer.edge} border-l-4 bg-white p-6 shadow-sm`}
              >
                <div className="flex items-center gap-3">
                  <layer.icon className={`w-7 h-7 ${layer.accent}`} />
                  <h3 className="text-xl font-semibold text-stone-900">{layer.title}</h3>
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  {layer.items.map((item) => (
                    <span
                      key={item}
                      className="rounded-md border border-stone-200 bg-stone-50 px-3 py-1 text-sm font-mono text-stone-700"
                    >
                      {item}
                    </span>
                  ))}
                </div>
              </div>
              {layer.id !== 'data' && (
                <div className="flex justify-center py-2 text-stone-400">
                  <ChevronDown className="w-6 h-6" />
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </Section>
  );
}
