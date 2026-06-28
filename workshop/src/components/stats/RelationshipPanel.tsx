import { useState } from 'react';
import type { Relationship, RelationshipSemantics } from '@/lib/types';

interface RelationshipPanelProps {
  relationships: (Relationship & { source: string })[];
}

const SEMANTICS_COLORS: Record<RelationshipSemantics, string> = {
  Ownership: 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/30',
  Reference: 'text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/30',
  Association: 'text-gray-600 dark:text-gray-400 bg-gray-100 dark:bg-gray-800',
};

export function RelationshipPanel({ relationships }: RelationshipPanelProps) {
  const [open, setOpen] = useState(true);

  return (
    <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 shadow-sm">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 text-sm font-semibold text-gray-800 dark:text-gray-100 hover:bg-gray-50 dark:hover:bg-gray-800 rounded-t-lg"
      >
        <span>Relationships ({relationships.length})</span>
        <span className="text-gray-400 dark:text-gray-500">{open ? '−' : '+'}</span>
      </button>
      {open && (
        <ul className="border-t border-gray-100 dark:border-gray-800">
          {relationships.map((rel, i) => (
            <li key={i} className="px-4 py-2 border-b border-gray-50 dark:border-gray-800 last:border-0">
              <div className="flex items-center justify-between text-sm">
                <span className="text-gray-700 dark:text-gray-300">
                  <span className="font-medium text-gray-900 dark:text-white">{rel.source}</span>
                  {' '}<span className="text-gray-400">→</span>{' '}
                  <span className="font-medium text-gray-900 dark:text-white">{rel.targetType}</span>
                </span>
                <span className={`px-1.5 py-0.5 text-[10px] rounded ${SEMANTICS_COLORS[rel.semantics]}`}>
                  {rel.semantics}
                </span>
              </div>
              <div className="text-xs text-gray-400 dark:text-gray-500 font-mono mt-0.5">{rel.label}</div>
            </li>
          ))}
          {relationships.length === 0 && (
            <li className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">No relationships defined</li>
          )}
        </ul>
      )}
    </div>
  );
}
