import { useState } from 'react';
import { Badge, Box, Button, Container, Group, Paper, Table, Text, Title } from '@mantine/core';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { mockResourceInstances } from '@/api/mock/data';

export function ExplorerPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];
  const [selectedType, setSelectedType] = useState<string>('All');

  const typeNames = ['All', ...types.map((t) => t.name)];
  const filtered = selectedType === 'All'
    ? mockResourceInstances
    : mockResourceInstances.filter((i) => i.type === selectedType);

  const columns = filtered.length > 0 ? Object.keys(filtered[0].data) : [];

  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={2}>Explorer</Title>
        <Text size="xs" c="dimmed">Browse resource instances</Text>
      </Paper>
      <Container fluid p="md">
        <Group gap="xs" mb="md">
          {typeNames.map((name) => (
            <Button
              key={name}
              onClick={() => setSelectedType(name)}
              size="xs"
              variant={selectedType === name ? 'filled' : 'default'}
            >
              {name}
            </Button>
          ))}
        </Group>
        <Paper withBorder radius="md" style={{ overflow: 'hidden' }}>
          {filtered.length > 0 ? (
            <Table verticalSpacing="xs" horizontalSpacing="md" striped highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>rid</Table.Th>
                  <Table.Th>type</Table.Th>
                  <Table.Th>state</Table.Th>
                  {columns.map((col) => (
                    <Table.Th key={col}>{col}</Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {filtered.map((instance) => (
                  <Table.Tr key={instance.rid}>
                    <Table.Td>
                      <Text size="xs" c="dimmed" ff="monospace">{instance.rid}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{instance.type}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Badge size="sm" color="green" variant="light">
                        {instance.state}
                      </Badge>
                    </Table.Td>
                    {columns.map((col) => (
                      <Table.Td key={col}>
                        <Text size="sm">{String(instance.data[col] ?? '')}</Text>
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : (
            <Box p="lg">
              <Text size="sm" c="dimmed" ta="center">No instances found</Text>
            </Box>
          )}
        </Paper>
      </Container>
    </Box>
  );
}
