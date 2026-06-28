import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';

export function AttributesPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950 overflow-auto">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Attributes</h1>
        <p className="text-xs text-gray-500 dark:text-gray-400">All fields declared across resource types</p>
      </div>
      <div className="p-6 max-w-4xl">
        {types.map(type => (
          <div key={type.name} className="mb-6 bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 overflow-hidden">
            <div className="px-4 py-2 border-b border-gray-100 dark:border-gray-800 bg-gray-50 dark:bg-gray-800/50">
              <h2 className="font-medium text-gray-900 dark:text-white">{type.name}</h2>
              <p className="text-xs text-gray-500 dark:text-gray-400">{type.fields.length} field{type.fields.length !== 1 ? 's' : ''}</p>
            </div>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 dark:border-gray-800">
                  <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Name</th>
                  <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Type</th>
                  <th className="text-left py-2 px-4 font-medium text-gray-600 dark:text-gray-300">Required</th>
                </tr>
              </thead>
              <tbody>
                {type.fields.map(field => (
                  <tr key={field.name} className="border-b border-gray-50 dark:border-gray-800 last:border-0">
                    <td className="py-2 px-4 text-gray-800 dark:text-gray-200 font-mono text-xs">{field.name}</td>
                    <td className="py-2 px-4">
                      <span className="px-1.5 py-0.5 text-xs rounded bg-indigo-50 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300">{field.type}</span>
                    </td>
                    <td className="py-2 px-4 text-gray-600 dark:text-gray-400">{field.required ? 'Yes' : 'No'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </div>
  );
}
