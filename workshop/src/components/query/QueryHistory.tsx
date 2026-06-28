import { useState } from 'react';
import type { SavedQuery } from '@/lib/types';

interface QueryHistoryProps {
  queries: SavedQuery[];
  onSelect: (query: SavedQuery) => void;
  onDelete: (id: string) => void;
  onToggleFavorite: (id: string) => void;
}

export function QueryHistory({ queries, onSelect, onDelete, onToggleFavorite }: QueryHistoryProps) {
  const [search, setSearch] = useState('');

  const filtered = queries.filter(q =>
    q.name.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="flex flex-col h-full">
      <div className="p-3 border-b border-gray-200 dark:border-gray-700">
        <input
          type="text"
          placeholder="Search queries..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="w-full px-2 py-1.5 text-sm border border-gray-200 dark:border-gray-700 rounded focus:outline-none focus:ring-1 focus:ring-indigo-300 bg-white dark:bg-gray-800 text-gray-800 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500"
        />
      </div>
      <div className="flex-1 overflow-auto">
        {filtered.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">No saved queries</p>
        )}
        {filtered.map(q => (
          <div
            key={q.id}
            className="group px-4 py-3 border-b border-gray-50 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer"
            onClick={() => onSelect(q)}
          >
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-gray-700 dark:text-gray-200 truncate flex-1">{q.name}</span>
              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  onClick={e => { e.stopPropagation(); onToggleFavorite(q.id); }}
                  className={q.favorited ? 'text-yellow-500' : 'text-gray-300 dark:text-gray-600 hover:text-yellow-400'}
                  aria-label={q.favorited ? 'Unfavorite' : 'Favorite'}
                >
                  {q.favorited ? '★' : '☆'}
                </button>
                <button
                  onClick={e => { e.stopPropagation(); onDelete(q.id); }}
                  className="text-gray-300 dark:text-gray-600 hover:text-red-500"
                  aria-label="Delete query"
                >
                  🗑
                </button>
              </div>
            </div>
            <code className="text-xs text-gray-400 dark:text-gray-500 block truncate mt-1 font-mono">
              {JSON.stringify(q.query).slice(0, 80)}
            </code>
          </div>
        ))}
      </div>
    </div>
  );
}
