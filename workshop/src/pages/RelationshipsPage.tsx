import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { OntologyGraph } from '@/components/stats/OntologyGraph';
import { RELATIONSHIP_SEMANTICS } from '@/lib/constants';

export function RelationshipsPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  const relationships = types.flatMap(t =>
    t.relationships.map(r => ({ source: t.name, ...r })),
  );

  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Relationships</h1>
        <p className="text-xs text-gray-500 dark:text-gray-400">Ownership, Reference, and Association links between types</p>
      </div>
      <div className="flex-1 p-4 overflow-hidden">
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 h-full">
          <div className="lg:col-span-1 bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 overflow-auto">
            <div className="p-3 border-b border-gray-100 dark:border-gray-800">
              <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-200">Legend</h2>
            </div>
            <div className="p-3 space-y-3">
              {RELATIONSHIP_SEMANTICS.map(s => {
                const base = s === 'Ownership' ? 'bg-red-500' : s === 'Reference' ? 'bg-blue-500' : 'bg-gray-400';
                const dash = s === 'Reference' ? 'border-t-2 border-dashed border-blue-500 bg-transparent h-0' : s === 'Association' ? 'border-t-2 border-dotted border-gray-400 bg-transparent h-0' : '';
                return (
                  <div key={s} className="flex items-center gap-2 text-sm">
                    <span className={`w-8 ${dash || `h-1 rounded ${base}`}`} />
                    <span className="text-gray-700 dark:text-gray-300">{s}</span>
                  </div>
                );
              })}
            </div>
            <div className="p-3 border-t border-gray-100 dark:border-gray-800">
              <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-200 mb-2">Links ({relationships.length})</h2>
              <ul className="space-y-1.5">
                {relationships.map((rel, i) => (
                  <li key={i} className="text-xs text-gray-600 dark:text-gray-400">
                    <span className="font-medium text-gray-800 dark:text-gray-200">{rel.source}</span>
                    {' '}<span className="text-gray-400">─[{rel.label}]─▶</span>{' '}
                    <span className="font-medium text-gray-800 dark:text-gray-200">{rel.targetType}</span>
                    <span className="ml-1 px-1 rounded bg-gray-100 dark:bg-gray-800 text-gray-500 dark:text-gray-400">{rel.semantics}</span>
                  </li>
                ))}
                {relationships.length === 0 && (
                  <li className="text-xs text-gray-400 dark:text-gray-500">No relationships defined</li>
                )}
              </ul>
            </div>
          </div>
          <div className="lg:col-span-3">
            <OntologyGraph types={types} />
          </div>
        </div>
      </div>
    </div>
  );
}
