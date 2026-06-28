import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { ResourceType } from '@/lib/types';

interface ResourceTypePanelProps {
  types: ResourceType[];
}

export function ResourceTypePanel({ types }: ResourceTypePanelProps) {
  const [open, setOpen] = useState(true);

  return (
    <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 shadow-sm">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 text-sm font-semibold text-gray-800 dark:text-gray-100 hover:bg-gray-50 dark:hover:bg-gray-800 rounded-t-lg"
      >
        <span>Resource Types ({types.length})</span>
        <span className="text-gray-400 dark:text-gray-500">{open ? '−' : '+'}</span>
      </button>
      {open && (
        <ul className="border-t border-gray-100 dark:border-gray-800">
          {types.map(type => (
            <li key={type.name} className="border-b border-gray-50 dark:border-gray-800 last:border-0">
              <Link
                to={`/schema/${type.name}`}
                className="flex items-center justify-between px-4 py-2 text-sm text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
              >
                <span>{type.name}</span>
                <span className="text-xs text-gray-400 dark:text-gray-500">v{type.version}</span>
              </Link>
            </li>
          ))}
          {types.length === 0 && (
            <li className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">No types defined</li>
          )}
        </ul>
      )}
    </div>
  );
}
