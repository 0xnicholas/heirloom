import { FileText, Map, BookOpen, Hammer, Code, Mail } from 'lucide-react';
import { Section } from '../components/Section.tsx';

const resources = [
  {
    icon: FileText,
    title: '核心概念',
    desc: 'Resource、Abilities、State Machine 等关键概念速查。',
    href: '/docs/core-concepts.md',
  },
  {
    icon: Map,
    title: '路线图',
    desc: 'Phase 0–5 的工程阶段与当前完成状态。',
    href: '/docs/ROADMAP.md',
  },
  {
    icon: BookOpen,
    title: '白皮书',
    desc: '设计哲学、技术架构、场景对比与局限性分析。',
    href: '/whitepapers/',
  },
  {
    icon: Hammer,
    title: 'Workshop',
    desc: '面向开发者的建模与调试工作台。',
    href: '/workshop/',
  },
  {
    icon: Code,
    title: 'GitHub',
    desc: '源码、Issue 与贡献指南。',
    href: 'https://github.com/0xnicholas/heirloom',
  },
  {
    icon: Mail,
    title: '联系我们',
    desc: '交流架构设计、合作与企业级部署。',
    href: 'mailto:hello@heirloom.dev',
  },
];

export function ResourcesSection() {
  return (
    <Section id="resources" className="bg-white">
      <div className="max-w-4xl mx-auto text-center">
        <h2 className="text-3xl sm:text-4xl font-semibold tracking-tight text-stone-900">资源</h2>
        <p className="mt-4 text-stone-600 leading-relaxed">
          深入了解 Heirloom 的设计理念、实现进展与参与方式。
        </p>
      </div>

      <div className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {resources.map((r) => (
          <a
            key={r.title}
            href={r.href}
            className="group rounded-xl border border-stone-200 bg-white p-6 shadow-sm hover:border-indigo-300 transition-colors"
          >
            <div className="flex items-center justify-between">
              <div className="rounded-lg bg-indigo-50 p-2.5">
                <r.icon className="w-5 h-5 text-indigo-700" />
              </div>
            </div>
            <h3 className="mt-4 text-lg font-semibold text-stone-900 group-hover:text-indigo-700 transition-colors">
              {r.title}
            </h3>
            <p className="mt-2 text-sm text-stone-600 leading-relaxed">{r.desc}</p>
          </a>
        ))}
      </div>

      <div className="mt-12 text-center">
        <a
          href="mailto:hello@heirloom.dev"
          className="inline-flex items-center gap-2 rounded-lg bg-indigo-700 px-6 py-3 text-base font-medium text-white hover:bg-indigo-800 transition-colors"
        >
          <Mail className="w-5 h-5" />
          与我们联系
        </a>
      </div>
    </Section>
  );
}
