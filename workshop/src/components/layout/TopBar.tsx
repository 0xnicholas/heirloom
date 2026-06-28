import { useTheme } from '@/hooks/useTheme';
import { WORKSHOP_CONFIG } from '@/lib/config';

export function TopBar() {
  const { theme, toggleTheme } = useTheme();

  return (
    <header className="flex items-center justify-between px-4 h-14 border-b border-gray-200 bg-white dark:bg-gray-900 dark:border-gray-700 shrink-0">
      <div className="flex items-center gap-3">
        <span className="flex items-center justify-center w-8 h-8 rounded-lg bg-indigo-600 text-white">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2L2 12l10 10 10-10L12 2z" />
          </svg>
        </span>
        <div className="flex items-baseline gap-2">
          <span className="font-semibold text-gray-900 dark:text-white">Heirloom</span>
          <span className="text-xs text-gray-500 dark:text-gray-400">Workshop</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 px-3 py-1.5 text-sm border rounded-md bg-white dark:bg-gray-800 border-gray-200 dark:border-gray-700 text-gray-700 dark:text-gray-200">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-gray-400">
            <ellipse cx="12" cy="5" rx="9" ry="3" />
            <path d="M3 5v14a9 3 0 0 0 18 0V5" />
          </svg>
          <span>{WORKSHOP_CONFIG.defaultOntology}</span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-gray-400">
            <path d="m6 9 6 6 6-6" />
          </svg>
        </div>

        <a
          href={WORKSHOP_CONFIG.apiDocsUrl}
          target="_blank"
          rel="noreferrer"
          className="text-sm text-gray-600 dark:text-gray-300 hover:text-indigo-600 dark:hover:text-indigo-400"
        >
          API Docs
        </a>

        <button
          onClick={toggleTheme}
          aria-label="Toggle theme"
          className="p-2 rounded-md text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
        >
          {theme === 'dark' ? (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="5" />
              <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
            </svg>
          ) : (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
            </svg>
          )}
        </button>
      </div>
    </header>
  );
}
