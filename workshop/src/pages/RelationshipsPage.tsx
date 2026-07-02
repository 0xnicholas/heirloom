import { Box, Group, Paper, SimpleGrid, Stack, Text, Title, Box as MBox } from '@mantine/core';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { OntologyGraph } from '@/components/stats/OntologyGraph';
import { RELATIONSHIP_SEMANTICS } from '@/lib/constants';

const SEMANTICS_COLORS: Record<string, { bg: string; dash: string }> = {
  Ownership: { bg: 'red', dash: 'solid' },
  Reference: { bg: 'blue', dash: 'dashed' },
  Association: { bg: 'gray', dash: 'dotted' },
};

function LegendLine({ dash }: { dash: string }) {
  const styles: Record<string, React.CSSProperties> = {
    solid: { borderTop: '2px solid currentColor' },
    dashed: { borderTop: '2px dashed currentColor' },
    dotted: { borderTop: '2px dotted currentColor' },
  };
  return (
    <MBox w={32} h={2} style={styles[dash]} />
  );
}

export function RelationshipsPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  const relationships = types.flatMap((t) =>
    t.relationships.map((r) => ({ source: t.name, ...r })),
  );

  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={2}>Relationships</Title>
        <Text size="xs" c="dimmed">
          Ownership, Reference, and Association links between types
        </Text>
      </Paper>
      <Box style={{ flex: 1, padding: 16, overflow: 'hidden' }}>
        <SimpleGrid cols={{ base: 1, lg: 4 }} spacing="md" style={{ height: '100%' }}>
          <Paper withBorder radius="md" p="md" style={{ overflow: 'auto' }}>
            <Stack gap="md">
              <Box>
                <Title order={4} size="h5" mb="xs">Legend</Title>
                <Stack gap="xs">
                  {RELATIONSHIP_SEMANTICS.map((s) => {
                    const cfg = SEMANTICS_COLORS[s] ?? { bg: 'gray', dash: 'solid' };
                    return (
                      <Group key={s} gap="xs" align="center" c={`${cfg.bg}.6`}>
                        <LegendLine dash={cfg.dash} />
                        <Text size="sm" c="inherit">{s}</Text>
                      </Group>
                    );
                  })}
                </Stack>
              </Box>
              <Box>
                <Title order={4} size="h5" mb="xs">Links ({relationships.length})</Title>
                <Stack gap={6}>
                  {relationships.map((rel, i) => (
                    <Text key={i} size="xs">
                      <Text component="span" fw={500}>{rel.source}</Text>{' '}
                      <Text component="span" c="dimmed">─[{rel.label}]─▶</Text>{' '}
                      <Text component="span" fw={500}>{rel.targetType}</Text>{' '}
                      <Text component="span" c="dimmed" size="xs">({rel.semantics})</Text>
                    </Text>
                  ))}
                  {relationships.length === 0 && (
                    <Text size="xs" c="dimmed">No relationships defined</Text>
                  )}
                </Stack>
              </Box>
            </Stack>
          </Paper>
          <Box style={{ gridColumn: 'span 3 / span 3' }}>
            <OntologyGraph types={types} />
          </Box>
        </SimpleGrid>
      </Box>
    </Box>
  );
}
