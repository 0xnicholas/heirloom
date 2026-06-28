import { useState } from 'react';
import type { Action } from '@/lib/types';

interface ActionListProps {
  actions: Action[];
  selected: string | null;
  onSelect: (name: string) => void;
  onNew: () => void;
}

export function ActionList({ actions, selected, onSelect, onNew }: ActionListProps) {
  const [search, setSearch] = useState('');

  const filtered = actions.filter(a =>
    a.name.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="flex flex-col h-full">
      <div className="p-3 border-b border-gray-200 dark:border-gray-700">
        <input
          type="text"
          placeholder="Search actions..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="w-full px-2 py-1.5 text-sm border border-gray-200 dark:border-gray-700 rounded focus:outline-none focus:ring-1 focus:ring-indigo-300 bg-white dark:bg-gray-800 text-gray-800 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500"
        />
      </div>
      <div className="flex-1 overflow-auto">
        {filtered.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">No actions found</p>
        )}
        {filtered.map(action => (
          <button
            key={action.name}
            onClick={() => onSelect(action.name)}
            className={`w-full text-left px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors ${
              selected === action.name
                ? 'bg-indigo-50 dark:bg-indigo-950/40 text-indigo-700 dark:text-indigo-300 font-medium'
                : 'text-gray-700 dark:text-gray-300'
            }`}
          >
            <div>{action.name}</div>
            <div className="text-xs text-gray-400 dark:text-gray-500">{action.targetType} · requires {action.requires}</div>
          </button>
        ))}
      </div>
      <div className="p-3 border-t border-gray-200 dark:border-gray-700">
        <button
          onClick={onNew}
          className="w-full py-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-950/30 rounded transition-colors"
        >
          + New Action
        </button>
      </div>
    </div>
  );
}
