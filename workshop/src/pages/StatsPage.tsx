import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { ELI5Card } from '@/components/stats/ELI5Card';
import { MetricCards } from '@/components/stats/MetricCards';
import { ResourceTypePanel } from '@/components/stats/ResourceTypePanel';
import { RelationshipPanel } from '@/components/stats/RelationshipPanel';
import { OntologyGraph } from '@/components/stats/OntologyGraph';

export function StatsPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  const relationships = types.flatMap(t =>
    t.relationships.map(r => ({ source: t.name, ...r })),
  );

  return (
    <div className="h-full overflow-auto bg-gray-50 dark:bg-gray-950">
      <div className="max-w-7xl mx-auto p-6 space-y-6">
        <ELI5Card />
        <MetricCards types={types} relationships={relationships} />

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-1 space-y-6">
            <ResourceTypePanel types={types} />
            <RelationshipPanel relationships={relationships} />
          </div>
          <div className="lg:col-span-2">
            <OntologyGraph types={types} />
          </div>
        </div>
      </div>
    </div>
  );
}
