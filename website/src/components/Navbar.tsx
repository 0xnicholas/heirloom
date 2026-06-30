import { useState } from 'react';
import { Menu, X, Hexagon } from 'lucide-react';

const navLinks = [
  { label: '首页', href: '#' },
  { label: '能力', href: '#capabilities' },
  { label: '架构', href: '#architecture' },
  { label: '场景', href: '#usecases' },
];

export function Navbar() {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <header className="fixed top-0 inset-x-0 z-50 border-b border-slate-200 bg-white/80 backdrop-blur-md">
      <nav className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <a href="#" className="flex items-center gap-2 text-slate-900 font-semibold tracking-tight">
          <Hexagon className="w-7 h-7 text-violet-400" strokeWidth={2} />
          <span>Heirloom</span>
        </a>

        <ul className="hidden md:flex items-center gap-6 text-sm font-medium text-slate-700">
          {navLinks.map((link) => (
            <li key={link.href}>
              <a
                href={link.href}
                className="hover:text-violet-400 transition-colors"
              >
                {link.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="hidden md:flex items-center gap-3">
          <a
            href="/whitepapers/00-abstract-introduction.md"
            className="text-sm font-medium px-4 py-2 rounded-lg bg-violet-600 text-white hover:bg-violet-500 transition-colors"
          >
            白皮书
          </a>
        </div>

        <button
          type="button"
          className="md:hidden p-2 text-slate-700 hover:text-slate-900"
          onClick={() => setIsOpen((prev) => !prev)}
          aria-label={isOpen ? '关闭菜单' : '打开菜单'}
          aria-expanded={isOpen}
        >
          {isOpen ? <X className="w-6 h-6" /> : <Menu className="w-6 h-6" />}
        </button>
      </nav>

      {isOpen && (
        <div className="md:hidden border-t border-slate-200 bg-white">
          <ul className="px-4 py-4 space-y-3 text-slate-700">
            {navLinks.map((link) => (
              <li key={link.href}>
                <a
                  href={link.href}
                  className="block py-2 hover:text-violet-400 transition-colors"
                  onClick={() => setIsOpen(false)}
                >
                  {link.label}
                </a>
              </li>
            ))}
            <li>
              <a
                href="/whitepapers/00-abstract-introduction.md"
                className="block py-2 text-violet-400 hover:text-violet-600"
                onClick={() => setIsOpen(false)}
              >
                白皮书
              </a>
            </li>
          </ul>
        </div>
      )}
    </header>
  );
}
