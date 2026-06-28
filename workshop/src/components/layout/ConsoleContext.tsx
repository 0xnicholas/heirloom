import { createContext, useContext } from 'react';

export const ConsoleContext = createContext<{
  activeType: string | null;
  setActiveType: (t: string | null) => void;
}>({ activeType: null, setActiveType: () => {} });

export function useConsoleContext() {
  return useContext(ConsoleContext);
}
