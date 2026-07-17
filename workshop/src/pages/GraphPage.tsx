import { useState } from 'react';
import { Box, Paper, Title, Text, Tabs, Group } from '@mantine';
import { IconShare, IconBooks, IconDatabase } from '@tabler/icons-react';
import { OntologyGraph } from '@/components/stats/OntologyGraph';
import { KnowledgeGraph } from '@/components/graph/KnowledgeGraph';
import { InstanceGraph } from '@/components/graph/InstanceGraph';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';

export function GraphPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={2}>Graph Visualizations</Title>
        <Text size="xs" c="dimmed">
          Schema dependencies, KnowledgeArticle references, and Resource instance relationships
        </Text>
      </Paper>
      <Box style={{ flex: 1, padding: 16, overflow: 'hidden' }}>
        <Tabs defaultValue="schema" style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <Tabs.List mb="sm">
            <Tabs.Tab value="schema" leftSection={<IconShare size={16} />}>
              Schema Graph
            </Tabs.Tab>
            <Tabs.Tab value="knowledge" leftSection={<IconBooks size={16} />}>
              Knowledge Graph
            </Tabs.Tab>
            <Tabs.Tab value="instance" leftSection={<IconDatabase size={16} />}>
              Instance Graph
            </Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="schema" style={{ flex: 1, minHeight: 0 }}>
            <Box style={{ height: '100%' }}>
              <OntologyGraph types={types} />
            </Box>
          </Tabs.Panel>

          <Tabs.Panel value="knowledge" style={{ flex: 1, minHeight: 0 }}>
            <Box style={{ height: '100%' }}>
              <KnowledgeGraph />
            </Box>
          </Tabs.Panel>

          <Tabs.Panel value="instance" style={{ flex: 1, minHeight: 0 }}>
            <Box style={{ height: '100%' }}>
              <InstanceGraph />
            </Box>
          </Tabs.Panel>
        </Tabs>
      </Box>
    </Box>
  );
}
