import { useState, useCallback, useEffect } from 'react';
import { Outlet } from 'react-router-dom';
import { TopBar } from './TopBar';
import { SideNav } from './SideNav';
import { QueryConsole } from './QueryConsole';
import { ConsoleContext } from './ConsoleContext';

export function AppLayout() {
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [consoleHeight, setConsoleHeight] = useState(50);
  const [activeType, setActiveType] = useState<string | null>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === '`') {
        e.preventDefault();
        setConsoleOpen(prev => !prev);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const toggleConsole = useCallback(() => setConsoleOpen(prev => !prev), []);

  return (
    <ConsoleContext.Provider value={{ activeType, setActiveType }}>
      <div className="flex flex-col h-screen bg-gray-50 dark:bg-gray-950">
        <TopBar />
        <div className="flex flex-1 overflow-hidden">
          <SideNav />
          <main className="flex-1 overflow-hidden">
            <Outlet />
          </main>
        </div>
        {consoleOpen && (
          <QueryConsole
            height={consoleHeight}
            onHeightChange={setConsoleHeight}
            onClose={() => setConsoleOpen(false)}
            defaultFrom={activeType ?? undefined}
          />
        )}
        <div className="flex items-center justify-between px-4 py-0.5 border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 text-xs text-gray-500 dark:text-gray-400">
          <button onClick={toggleConsole} className="hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors">
            ◆ Query Console
          </button>
          <span>Ctrl+`</span>
        </div>
      </div>
    </ConsoleContext.Provider>
  );
}
