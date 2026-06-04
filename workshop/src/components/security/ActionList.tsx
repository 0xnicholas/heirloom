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
      <div className="p-3 border-b border-gray-200">
        <input
          type="text"
          placeholder="Search actions..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="w-full px-2 py-1.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
        />
      </div>
      <div className="flex-1 overflow-auto">
        {filtered.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-400">No actions found</p>
        )}
        {filtered.map(action => (
          <button
            key={action.name}
            onClick={() => onSelect(action.name)}
            className={`w-full text-left px-4 py-2 text-sm hover:bg-gray-100 transition-colors ${
              selected === action.name ? 'bg-indigo-50 text-indigo-700 font-medium' : 'text-gray-700'
            }`}
          >
            <div>{action.name}</div>
            <div className="text-xs text-gray-400">{action.targetType} · requires {action.requires}</div>
          </button>
        ))}
      </div>
      <div className="p-3 border-t border-gray-200">
        <button
          onClick={onNew}
          className="w-full py-1.5 text-sm font-medium text-indigo-600 hover:bg-indigo-50 rounded transition-colors"
        >
          + New Action
        </button>
      </div>
    </div>
  );
}
