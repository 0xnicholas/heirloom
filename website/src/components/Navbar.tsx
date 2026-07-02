import { useState } from 'react';
import { Menu, X, Hexagon } from 'lucide-react';
import { WORKSHOP_URL } from '../config';

const navLinks = [
  { label: '首页', href: '#' },
  { label: '能力', href: '#capabilities' },
  { label: '架构', href: '#architecture' },
  { label: '场景', href: '#usecases' },
];

export function Navbar() {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <header className="fixed top-0 inset-x-0 z-50 border-b border-stone-200 bg-stone-50/90 backdrop-blur">
      <nav className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <a href="#" className="flex items-center gap-2 text-stone-900 font-semibold tracking-tight">
          <Hexagon className="w-7 h-7 text-indigo-700" strokeWidth={2} />
          <span>Heirloom</span>
        </a>

        <ul className="hidden md:flex items-center gap-6 text-sm font-medium text-stone-600">
          {navLinks.map((link) => (
            <li key={link.href}>
              <a
                href={link.href}
                className="hover:text-indigo-700 transition-colors"
              >
                {link.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="hidden md:flex items-center gap-3">
          <a
            href={WORKSHOP_URL}
            target="_blank"
            rel="noreferrer"
            className="text-sm font-medium px-4 py-2 rounded-lg bg-indigo-700 text-white hover:bg-indigo-800 transition-colors"
          >
            Workshop
          </a>
        </div>

        <button
          type="button"
          className="md:hidden p-2 text-stone-600 hover:text-stone-900"
          onClick={() => setIsOpen((prev) => !prev)}
          aria-label={isOpen ? '关闭菜单' : '打开菜单'}
          aria-expanded={isOpen}
        >
          {isOpen ? <X className="w-6 h-6" /> : <Menu className="w-6 h-6" />}
        </button>
      </nav>

      {isOpen && (
        <div className="md:hidden border-t border-stone-200 bg-stone-50">
          <ul className="px-4 py-4 space-y-3 text-stone-600">
            {navLinks.map((link) => (
              <li key={link.href}>
                <a
                  href={link.href}
                  className="block py-2 hover:text-indigo-700 transition-colors"
                  onClick={() => setIsOpen(false)}
                >
                  {link.label}
                </a>
              </li>
            ))}
            <li>
              <a
                href={WORKSHOP_URL}
                target="_blank"
                rel="noreferrer"
                className="block py-2 text-indigo-700 hover:text-indigo-800"
                onClick={() => setIsOpen(false)}
              >
                Workshop
              </a>
            </li>
          </ul>
        </div>
      )}
    </header>
  );
}
