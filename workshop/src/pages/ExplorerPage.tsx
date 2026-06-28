import { useState } from 'react';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { mockResourceInstances } from '@/api/mock/data';

export function ExplorerPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];
  const [selectedType, setSelectedType] = useState<string>('All');

  const typeNames = ['All', ...types.map(t => t.name)];
  const filtered = selectedType === 'All'
    ? mockResourceInstances
    : mockResourceInstances.filter(i => i.type === selectedType);

  const columns = filtered.length > 0 ? Object.keys(filtered[0].data) : [];

  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Explorer</h1>
        <p className="text-xs text-gray-500 dark:text-gray-400">Browse resource instances</p>
      </div>
      <div className="p-4">
        <div className="flex gap-2 mb-4">
          {typeNames.map(name => (
            <button
              key={name}
              onClick={() => setSelectedType(name)}
              className={`px-3 py-1 text-xs rounded-full border transition-colors ${
                selectedType === name
                  ? 'bg-indigo-100 dark:bg-indigo-900/40 border-indigo-200 dark:border-indigo-800 text-indigo-700 dark:text-indigo-300'
                  : 'bg-white dark:bg-gray-900 border-gray-200 dark:border-gray-700 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800'
              }`}
            >
              {name}
            </button>
          ))}
        </div>

        <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 dark:border-gray-800 bg-gray-50 dark:bg-gray-800/50">
                <th className="text-left py-2 px-3 font-medium text-gray-600 dark:text-gray-300">rid</th>
                <th className="text-left py-2 px-3 font-medium text-gray-600 dark:text-gray-300">type</th>
                <th className="text-left py-2 px-3 font-medium text-gray-600 dark:text-gray-300">state</th>
                {columns.map(col => (
                  <th key={col} className="text-left py-2 px-3 font-medium text-gray-600 dark:text-gray-300">{col}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map(instance => (
                <tr key={instance.rid} className="border-b border-gray-50 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800/50">
                  <td className="py-2 px-3 font-mono text-xs text-gray-500 dark:text-gray-400">{instance.rid}</td>
                  <td className="py-2 px-3 text-gray-700 dark:text-gray-200">{instance.type}</td>
                  <td className="py-2 px-3">
                    <span className="px-1.5 py-0.5 text-xs rounded bg-green-50 dark:bg-green-900/30 text-green-700 dark:text-green-300">{instance.state}</span>
                  </td>
                  {columns.map(col => (
                    <td key={col} className="py-2 px-3 text-gray-700 dark:text-gray-300">{String(instance.data[col] ?? '')}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {filtered.length === 0 && (
            <p className="p-6 text-sm text-gray-400 dark:text-gray-500 text-center">No instances found</p>
          )}
        </div>
      </div>
    </div>
  );
}
