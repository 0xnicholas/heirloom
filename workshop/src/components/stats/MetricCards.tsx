import { SimpleGrid, Card, Text } from '@mantine/core';
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
    <SimpleGrid cols={{ base: 2, md: 3, lg: 6 }} spacing="md">
      {metrics.map((m) => (
        <Card
          key={m.label}
          withBorder
          shadow="sm"
          radius="md"
          padding="md"
          data-testid={`metric-${m.label.toLowerCase().replace(/\s+/g, '-')}`}
        >
          <Text size="xl" fw={600}>{m.value}</Text>
          <Text size="xs" c="dimmed">{m.label}</Text>
        </Card>
      ))}
    </SimpleGrid>
  );
}
