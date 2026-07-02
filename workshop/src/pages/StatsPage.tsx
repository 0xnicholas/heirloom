import { Box, Container, SimpleGrid, Stack } from '@mantine/core';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { ELI5Card } from '@/components/stats/ELI5Card';
import { MetricCards } from '@/components/stats/MetricCards';
import { ResourceTypePanel } from '@/components/stats/ResourceTypePanel';
import { RelationshipPanel } from '@/components/stats/RelationshipPanel';
import { OntologyGraph } from '@/components/stats/OntologyGraph';

export function StatsPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  const relationships = types.flatMap((t) =>
    t.relationships.map((r) => ({ source: t.name, ...r })),
  );

  return (
    <Box style={{ height: '100%', overflow: 'auto' }}>
      <Container size="xl" py="md">
        <Stack gap="md">
          <ELI5Card />
          <MetricCards types={types} relationships={relationships} />
          <SimpleGrid cols={{ base: 1, lg: 3 }} spacing="md">
            <Stack gap="md">
              <ResourceTypePanel types={types} />
              <RelationshipPanel relationships={relationships} />
            </Stack>
            <Box style={{ gridColumn: 'span 2 / span 2' }}>
              <OntologyGraph types={types} />
            </Box>
          </SimpleGrid>
        </Stack>
      </Container>
    </Box>
  );
}
