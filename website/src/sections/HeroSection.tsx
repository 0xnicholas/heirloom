import { ArrowDown, BookOpen } from 'lucide-react';

export function HeroSection() {
  return (
    <section className="relative pt-32 pb-24 px-4 sm:px-6 lg:px-8 overflow-hidden">
      <div className="absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_top_right,_rgba(139,92,246,0.18),_transparent_50%)]" />
      <div className="max-w-7xl mx-auto text-center">
        <div className="inline-flex items-center gap-2 rounded-full border border-violet-300 bg-violet-50 px-4 py-1.5 text-sm font-medium text-violet-600">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-violet-400 opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-violet-400" />
          </span>
          AI 原生的语义本体系统
        </div>

        <h1 className="mt-8 text-4xl sm:text-5xl lg:text-6xl font-semibold tracking-tight text-slate-900">
          Heirloom — AI 原生的
          <br />
          <span className="text-transparent bg-clip-text bg-gradient-to-r from-violet-400 to-amber-300">
            语义本体系统
          </span>
        </h1>

        <p className="mt-6 text-lg sm:text-xl text-slate-600 max-w-3xl mx-auto leading-relaxed">
          当 AI Agent 成为企业数据的一等消费者，让它在不被信任的前提下安全地理解和操作业务数据。
        </p>

        <div className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4">
          <a
            href="#architecture"
            className="inline-flex items-center gap-2 rounded-lg bg-violet-600 px-6 py-3 text-base font-medium text-white hover:bg-violet-500 transition-colors"
          >
            <ArrowDown className="w-5 h-5" />
            探索架构
          </a>
          <a
            href="/whitepapers/00-abstract-introduction.md"
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-6 py-3 text-base font-medium text-slate-800 hover:border-slate-500 hover:text-slate-900 transition-colors"
          >
            <BookOpen className="w-5 h-5" />
            查看白皮书
          </a>
        </div>
      </div>
    </section>
  );
}
