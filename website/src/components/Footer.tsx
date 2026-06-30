import { Hexagon } from 'lucide-react';

export function Footer() {
  return (
    <footer className="border-t border-stone-200 bg-white py-12 px-4 sm:px-6 lg:px-8 mt-auto">
      <div className="max-w-7xl mx-auto grid gap-8 md:grid-cols-4">
        <div className="md:col-span-2">
          <a href="#" className="flex items-center gap-2 text-stone-900 font-semibold">
            <Hexagon className="w-6 h-6 text-indigo-700" strokeWidth={2} />
            <span>Heirloom</span>
          </a>
          <p className="mt-3 text-sm text-stone-600 max-w-sm leading-relaxed">
            AI 原生的语义本体系统。让 AI Agent 在不被信任的前提下，安全地理解和操作企业数据。
          </p>
        </div>

        <div>
          <h3 className="text-sm font-semibold text-stone-800">文档</h3>
          <ul className="mt-3 space-y-2 text-sm text-stone-600">
            <li><a href="/docs/core-concepts.md" className="hover:text-indigo-700 transition-colors">核心概念</a></li>
            <li><a href="/docs/ROADMAP.md" className="hover:text-indigo-700 transition-colors">路线图</a></li>
            <li><a href="/whitepapers/" className="hover:text-indigo-700 transition-colors">白皮书</a></li>
          </ul>
        </div>

        <div>
          <h3 className="text-sm font-semibold text-stone-800">项目</h3>
          <ul className="mt-3 space-y-2 text-sm text-stone-600">
            <li><a href="/workshop/" className="hover:text-indigo-700 transition-colors">Workshop</a></li>
            <li><a href="https://github.com/0xnicholas/heirloom" target="_blank" rel="noreferrer" className="hover:text-indigo-700 transition-colors">GitHub</a></li>
            <li><a href="mailto:hello@heirloom.dev" className="hover:text-indigo-700 transition-colors">联系我们</a></li>
          </ul>
        </div>
      </div>

      <div className="max-w-7xl mx-auto mt-10 pt-6 border-t border-stone-200 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-stone-500">
        <p>&copy; {new Date().getFullYear()} Heirloom Project. All rights reserved.</p>
        <p>License TBD · 构建中</p>
      </div>
    </footer>
  );
}
