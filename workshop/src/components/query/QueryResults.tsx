import { useState } from 'react';
import { Tabs, Center, Text, Group, Table, Box, Code, Stack } from '@mantine/core';
import type { QueryResult } from '@/lib/types';

type ViewMode = 'table' | 'graph' | 'raw';

interface QueryResultsProps {
  result: QueryResult | null;
}

function GraphResultView({ rows }: { rows: QueryResult['rows'] }) {
  const seen = new Set<string>();
  const nodes: { id: string; label: string }[] = [];

  for (const row of rows) {
    const meta = row._meta;
    if (meta && !seen.has(meta.rid)) {
      seen.add(meta.rid);
      nodes.push({
        id: meta.rid,
        label: `${meta.type}: ${meta.rid.split('.').pop() ?? meta.rid}`,
      });
    }
  }

  const rids = [...seen];
  const edges: { id: string; source: string; target: string }[] = [];
  for (let i = 1; i < rids.length; i++) {
    edges.push({ id: `e-${i}`, source: rids[i - 1], target: rids[i] });
  }

  return (
    <Box p="md">
      <Text size="xs" c="dimmed" mb="sm">
        Graph: {nodes.length} node{nodes.length !== 1 ? 's' : ''},{' '}
        {edges.length} edge{edges.length !== 1 ? 's' : ''}
      </Text>
      <Stack gap="xs">
        {nodes.map((n) => (
          <Group key={n.id} gap="xs">
            <Text c="indigo" size="xs">⬤</Text>
            <Text size="xs" ff="monospace">{n.label}</Text>
          </Group>
        ))}
        {edges.length > 0 && (
          <Stack gap={2} mt="xs">
            {edges.map((e) => (
              <Text key={e.id} size="xs" c="dimmed" ff="monospace">
                {e.source.split('.').pop()} → {e.target.split('.').pop()}
              </Text>
            ))}
          </Stack>
        )}
      </Stack>
    </Box>
  );
}

export function QueryResults({ result }: QueryResultsProps) {
  const [mode, setMode] = useState<ViewMode>('table');

  if (!result) {
    return (
      <Center h="100%">
        <Text c="dimmed" size="sm">Run a query to see results</Text>
      </Center>
    );
  }

  if (result.rows.length === 0) {
    return (
      <Center h="100%">
        <Text c="dimmed" size="sm">No rows returned ({result.meta.query_ms}ms)</Text>
      </Center>
    );
  }

  const columns =
    result.rows.length > 0
      ? Object.keys(result.rows[0]).filter((k) => k !== '_meta')
      : [];

  return (
    <Stack gap={0} h="100%">
      {/* View toggle bar */}
      <Group justify="space-between" px="md" py="xs" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Tabs value={mode} onChange={(v) => v && setMode(v as ViewMode)} variant="pills">
          <Tabs.List>
            <Tabs.Tab value="table">Table</Tabs.Tab>
            <Tabs.Tab value="graph">Graph</Tabs.Tab>
            <Tabs.Tab value="raw">Raw JSON</Tabs.Tab>
          </Tabs.List>
        </Tabs>
        <Text size="xs" c="dimmed">
          {result.total} rows · {result.meta.query_ms}ms
        </Text>
      </Group>

      {/* View content */}
      <Box style={{ flex: 1, overflow: 'auto' }}>
        {mode === 'table' && (
          <Table striped highlightOnHover withTableBorder={false}>
            <Table.Thead style={{ position: 'sticky', top: 0, backgroundColor: 'var(--mantine-color-body)', zIndex: 1 }}>
              <Table.Tr>
                {columns.map((col) => (
                  <Table.Th key={col}>{col}</Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {result.rows.map((row, i) => (
                <Table.Tr key={i}>
                  {columns.map((col) => (
                    <Table.Td key={col}>
                      <Code fz="xs">{String(row[col] ?? '')}</Code>
                    </Table.Td>
                  ))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}

        {mode === 'graph' && <GraphResultView rows={result.rows} />}

        {mode === 'raw' && (
          <Box p="md">
            <Code block fz="xs">
              {JSON.stringify(result, null, 2)}
            </Code>
          </Box>
        )}
      </Box>
    </Stack>
  );
}
