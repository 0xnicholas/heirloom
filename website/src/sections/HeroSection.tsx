import { ArrowDown, BookOpen } from 'lucide-react';
import { Specimen } from '../components/Specimen.tsx';

export function HeroSection() {
  return (
    <section className="relative pt-32 pb-24 px-4 sm:px-6 lg:px-8 overflow-hidden">
      <div className="absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_top_right,_rgba(67,56,202,0.08),_transparent_50%)]" />
      <div className="max-w-7xl mx-auto grid gap-12 lg:grid-cols-2 items-center">
        <div>
          <div className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50/60 px-4 py-1.5 text-sm font-medium text-indigo-700">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-indigo-400 opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-indigo-700" />
            </span>
            AI 原生的语义本体系统
          </div>

          <h1 className="mt-8 text-4xl sm:text-5xl lg:text-6xl font-semibold tracking-tight text-stone-900">
            Heirloom — AI 原生的
            <br />
            <span className="text-indigo-700">语义本体系统</span>
          </h1>

          <p className="mt-6 text-lg sm:text-xl text-stone-600 max-w-2xl leading-relaxed">
            当 AI Agent 成为企业数据的一等消费者，让它在不被信任的前提下安全地理解和操作业务数据。
          </p>

          <div className="mt-10 flex flex-col sm:flex-row items-start gap-4">
            <a
              href="#architecture"
              className="inline-flex items-center gap-2 rounded-lg bg-indigo-700 px-6 py-3 text-base font-medium text-white hover:bg-indigo-800 transition-colors"
            >
              <ArrowDown className="w-5 h-5" />
              探索架构
            </a>
            <a
              href="/whitepapers/00-abstract-introduction.md"
              className="inline-flex items-center gap-2 rounded-lg border border-stone-300 bg-white px-6 py-3 text-base font-medium text-stone-700 hover:border-stone-400 hover:text-stone-900 transition-colors"
            >
              <BookOpen className="w-5 h-5" />
              查看白皮书
            </a>
          </div>
        </div>

        <Specimen
          annotation="// 未声明 drop → 删除 Customer 在类型层即不可表达"
        >
          <pre className="text-sm font-mono text-stone-800 leading-relaxed overflow-x-auto">
            <code>{`type Customer {
  abilities: [query, mutate, transfer]
  stateMachine: Draft -> Active -> Frozen
  relationships: [owns Order, references Contract]
}`}</code>
          </pre>
        </Specimen>
      </div>
    </section>
  );
}
