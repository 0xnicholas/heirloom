import { mockEvents } from '@/api/mock/data';

export function EventsPage() {
  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950 overflow-auto">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Audit</h1>
        <p className="text-xs text-gray-500 dark:text-gray-400">Event log and change history</p>
      </div>
      <div className="p-6 max-w-4xl">
        <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 dark:border-gray-800 bg-gray-50 dark:bg-gray-800/50">
                <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Time</th>
                <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Actor</th>
                <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Action</th>
                <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Target</th>
              </tr>
            </thead>
            <tbody>
              {mockEvents.map(event => (
                <tr key={event.id} className="border-b border-gray-50 dark:border-gray-800 last:border-0">
                  <td className="py-2 px-4 text-xs text-gray-500 dark:text-gray-400 font-mono">{event.timestamp}</td>
                  <td className="py-2 px-4 text-gray-700 dark:text-gray-300">{event.actor}</td>
                  <td className="py-2 px-4">
                    <span className="px-1.5 py-0.5 text-xs rounded bg-indigo-50 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300">{event.action}</span>
                  </td>
                  <td className="py-2 px-4 text-gray-700 dark:text-gray-300 font-mono text-xs">{event.target}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
