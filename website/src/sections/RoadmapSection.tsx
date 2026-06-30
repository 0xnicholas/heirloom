import { Section } from '../components/Section.tsx';

const phases = [
  {
    phase: 'Phase 0',
    title: '核心基础设施',
    desc: '搭建最小可用的类型系统和存储基础，能够定义 Resource Type 并执行基本的属性读写。',
  },
  {
    phase: 'Phase 1',
    title: '语义查询层',
    desc: '实现统一的只读语义查询层，验证「多源数据 → 统一语义查询」的核心价值。',
  },
  {
    phase: 'Phase 2',
    title: '安全操作层',
    desc: '通过 Action 层引入安全写入，完整的 Abilities + Role + Capability 模型上线。',
  },
  {
    phase: 'Phase 3',
    title: 'AI Agent 集成',
    desc: 'AI Agent 成为本体的正式消费者，通过专用 Role 获得有限操作能力。',
  },
  {
    phase: 'Phase 4',
    title: '治理与规模化',
    desc: '完整的 Ontology 治理、多 Agent 协作和条件式安全控制。',
  },
  {
    phase: 'Phase 5',
    title: '可信自治与生态',
    desc: 'Agent 在严格约束下实现自主操作，Heirloom 成为企业 AI Agent 的标准语义界面。',
  },
];

export function RoadmapSection() {
  return (
    <Section id="roadmap" className="bg-slate-50">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold text-slate-900">路线图</h2>
        <p className="mt-4 text-slate-600">
          从类型系统到可信自治，分阶段构建 AI 原生的语义本体系统。
        </p>
      </div>

      <div className="mt-14 relative">
        <div className="absolute left-4 md:left-1/2 top-0 bottom-0 w-px bg-slate-200 md:-translate-x-px" />
        <div className="space-y-8">
          {phases.map((p, idx) => (
            <div
              key={p.phase}
              className={`relative flex flex-col md:flex-row gap-8 ${idx % 2 === 0 ? 'md:flex-row-reverse' : ''}`}
            >
              <div className="flex-1 md:text-right md:px-8" />
              <div className="absolute left-4 md:left-1/2 -translate-x-1/2 w-4 h-4 rounded-full bg-violet-500 ring-4 ring-white" />
              <div className="flex-1 pl-12 md:pl-8 md:px-8">
                <div className="rounded-2xl border border-slate-200 bg-white p-6">
                  <span className="text-xs font-mono text-violet-400">{p.phase}</span>
                  <h3 className="mt-1 text-lg font-semibold text-slate-900">{p.title}</h3>
                  <p className="mt-2 text-sm text-slate-600 leading-relaxed">{p.desc}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </Section>
  );
}
