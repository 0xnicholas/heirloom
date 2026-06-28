import { mockFunctions } from '@/api/mock/data';

export function FunctionsPage() {
  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950 overflow-auto">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Functions</h1>
        <p className="text-xs text-gray-500 dark:text-gray-400">Reusable query and compute functions</p>
      </div>
      <div className="p-6 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {mockFunctions.map(fn => (
          <div key={fn.name} className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-4">
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-medium text-gray-900 dark:text-white font-mono text-sm">{fn.name}</h3>
              <span className="px-1.5 py-0.5 text-xs rounded bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300">{fn.language}</span>
            </div>
            <p className="text-sm text-gray-600 dark:text-gray-400 mb-3">{fn.description}</p>
            <div className="text-xs text-gray-500 dark:text-gray-500 font-mono bg-gray-50 dark:bg-gray-800 rounded p-2">
              {fn.signature}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
