import { useSecurity } from '@/hooks/useSecurity';
import { mockFunctions, mockResourceInstances } from '@/api/mock/data';
import type { ResourceType } from '@/lib/types';

interface MetricCardsProps {
  types: ResourceType[];
  relationships: { source: string; label: string; targetType: string; semantics: string }[];
}

export function MetricCards({ types, relationships }: MetricCardsProps) {
  const { actionsQuery, rolesQuery } = useSecurity();
  const actions = actionsQuery.data || [];
  const roles = rolesQuery.data || [];

  const metrics = [
    { label: 'Resource Types', value: types.length },
    { label: 'Resource Instances', value: mockResourceInstances.length },
    { label: 'Relationships', value: relationships.length },
    { label: 'Actions', value: actions.length },
    { label: 'Functions', value: mockFunctions.length },
    { label: 'Roles', value: roles.length },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
      {metrics.map(m => (
        <div
          key={m.label}
          data-testid={`metric-${m.label.toLowerCase().replace(/\s+/g, '-')}`}
          className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-4 shadow-sm"
        >
          <div className="text-2xl font-semibold text-gray-900 dark:text-white">{m.value}</div>
          <div className="text-xs text-gray-500 dark:text-gray-400">{m.label}</div>
        </div>
      ))}
    </div>
  );
}
