import { useTheme } from '@/hooks/useTheme';
import { WORKSHOP_CONFIG } from '@/lib/config';

export function SettingsPage() {
  const { theme, setTheme } = useTheme();

  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950 overflow-auto">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Settings</h1>
        <p className="text-xs text-gray-500 dark:text-gray-400">Workshop preferences</p>
      </div>
      <div className="p-6 max-w-xl space-y-6">
        <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-4">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-white mb-3">Appearance</h2>
          <label className="block text-sm text-gray-600 dark:text-gray-300 mb-2">Theme</label>
          <select
            value={theme}
            onChange={e => setTheme(e.target.value as 'light' | 'dark')}
            className="px-3 py-2 text-sm border rounded-md bg-white dark:bg-gray-800 border-gray-200 dark:border-gray-700 text-gray-700 dark:text-gray-200"
          >
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
        </div>

        <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-4">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-white mb-3">Workspace</h2>
          <label className="block text-sm text-gray-600 dark:text-gray-300 mb-2">Active ontology</label>
          <select className="px-3 py-2 text-sm border rounded-md bg-white dark:bg-gray-800 border-gray-200 dark:border-gray-700 text-gray-700 dark:text-gray-200">
            {WORKSHOP_CONFIG.ontologies.map(name => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
        </div>

        <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-4">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-white mb-3">Documentation</h2>
          <a
            href={WORKSHOP_CONFIG.apiDocsUrl}
            target="_blank"
            rel="noreferrer"
            className="text-sm text-indigo-600 dark:text-indigo-400 hover:underline"
          >
            Open API Docs →
          </a>
        </div>
      </div>
    </div>
  );
}
