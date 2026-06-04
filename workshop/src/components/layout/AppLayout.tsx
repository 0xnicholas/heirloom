import { useState, useCallback, useEffect, createContext, useContext } from 'react';
import { Outlet } from 'react-router-dom';
import { NavBar } from './NavBar';
import { QueryConsole } from './QueryConsole';

export const ConsoleContext = createContext<{
  activeType: string | null;
  setActiveType: (t: string | null) => void;
}>({ activeType: null, setActiveType: () => {} });

export function useConsoleContext() {
  return useContext(ConsoleContext);
}

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
    <div className="flex flex-col h-screen bg-gray-50">
      <NavBar />
      <main className="flex-1 overflow-hidden" style={{ height: consoleOpen ? `${100 - consoleHeight}%` : '100%' }}>
        <Outlet />
      </main>
      {consoleOpen && (
        <QueryConsole
          height={consoleHeight}
          onHeightChange={setConsoleHeight}
          onClose={() => setConsoleOpen(false)}
          defaultFrom={activeType ?? undefined}
        />
      )}
      <div className="flex items-center justify-between px-4 py-0.5 border-t border-gray-200 bg-white text-xs text-gray-500">
        <button onClick={toggleConsole} className="hover:text-indigo-600 transition-colors">
          ◆ Query Console
        </button>
        <span>Ctrl+`</span>
      </div>
    </div>
    </ConsoleContext.Provider>
  );
}
