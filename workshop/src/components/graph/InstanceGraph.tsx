import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  Paper, Group, Title, Text, TextInput, Button, Stack, Badge,
  useComputedColorScheme,
} from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

interface GraphEdge {
  id: number;
  sourceRid: string;
  targetRid: string;
  relationshipType: string;
  semantics: string;
}

async function fetchInstanceGraph(rid: string, depth: number): Promise<GraphEdge[]> {
  const res = await fetch(`/api/graph/traverse/${encodeURIComponent(rid)}?depth=${depth}`);
  if (!res.ok) throw new Error('Failed to fetch instance graph');
  return res.json();
}

const SEMANTICS_COLORS: Record<string, string> = {
  OWNERSHIP: '#ef4444',
  REFERENCE: '#3b82f6',
  ASSOCIATION: '#9ca3af',
};

const SEMANTICS_DASH: Record<string, string> = {
  OWNERSHIP: '',
  REFERENCE: '6 4',
  ASSOCIATION: '2 3',
};

export function InstanceGraph() {
  const computedColorScheme = useComputedColorScheme('light');
  const isDark = computedColorScheme === 'dark';
  const [rootRid, setRootRid] = useState('');
  const [depth] = useState(3);
  const [searchValue, setSearchValue] = useState('');

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['instance-graph', rootRid, depth],
    queryFn: () => fetchInstanceGraph(rootRid, depth),
    enabled: !!rootRid,
  });

  const edges: GraphEdge[] = data ?? [];

  // Collect unique RIDs from edges
  const allRids = new Set<string>();
  edges.forEach((e) => { allRids.add(e.sourceRid); allRids.add(e.targetRid); });
  const ridList = Array.from(allRids);
  const ridIndex = new Map<string, number>();
  ridList.forEach((r, i) => ridIndex.set(r, i));

  const reactFlowNodes: Node[] = ridList.map((rid, i) => ({
    id: rid,
    data: { label: rid.length > 24 ? rid.slice(0, 22) + '…' : rid },
    position: {
      x: 120 + (i % 4) * 220,
      y: 80 + Math.floor(i / 4) * 130,
    },
    style: {
      background: isDark ? '#312e81' : '#eef2ff',
      border: '1px solid #818cf8',
      borderRadius: '6px',
      padding: '6px 12px',
      fontSize: '10px',
      fontWeight: 500,
      color: isDark ? '#e0e7ff' : '#4338ca',
      textAlign: 'center',
      fontFamily: 'monospace',
    },
  }));

  const reactFlowEdges: Edge[] = edges.map((e, i) => {
    const color = SEMANTICS_COLORS[e.semantics] ?? '#6b7280';
    const dash = SEMANTICS_DASH[e.semantics] ?? '';
    return {
      id: `e-${i}`,
      source: e.sourceRid,
      target: e.targetRid,
      label: `${e.relationshipType} (${e.semantics})`,
      animated: e.semantics === 'OWNERSHIP',
      style: {
        stroke: color,
        strokeWidth: 1.5,
        strokeDasharray: dash || undefined,
      },
      labelStyle: { fill: color, fontSize: 8, fontWeight: 500 },
      markerEnd: { type: MarkerType.ArrowClosed, color },
    };
  });

  const [nodes, setNodes, onNodesChange] = useNodesState(reactFlowNodes);
  const [edges2, setEdges2, onEdgesChange] = useEdgesState(reactFlowEdges);

  useState(() => {
    setNodes(reactFlowNodes);
    setEdges2(reactFlowEdges);
  });

  const handleSearch = () => {
    if (searchValue.trim()) {
      setRootRid(searchValue.trim());
    }
  };

  return (
    <Paper withBorder shadow="sm" radius="md" style={{ display: 'flex', flexDirection: 'column', minHeight: 400 }}>
      <Group justify="space-between" px="md" py="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Group gap="xs">
          <Title order={3} size="h4">Instance Graph</Title>
          <Badge size="sm" variant="light" color="violet">Phase 2.5</Badge>
        </Group>
        <Group gap="xs">
          <TextInput
            placeholder="RID (e.g. default.Customer.abc123)"
            size="xs"
            value={searchValue}
            onChange={(e) => setSearchValue(e.currentTarget.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            leftSection={<IconSearch size={14} />}
          />
          <Button size="xs" variant="default" onClick={handleSearch} loading={isLoading}>
            Traverse
          </Button>
        </Group>
      </Group>
      {!rootRid ? (
        <Stack align="center" justify="center" style={{ flex: 1, minHeight: 300 }} gap="xs">
          <Text size="sm" c="dimmed">Enter a Resource RID and click Traverse</Text>
          <Text size="xs" c="dimmed">Edges colored by semantics: Red=Ownership, Blue=Reference, Gray=Association</Text>
        </Stack>
      ) : error ? (
        <Stack align="center" justify="center" style={{ flex: 1, minHeight: 300 }} gap="xs">
          <Text size="sm" c="red">Failed to load graph</Text>
          <Text size="xs" c="dimmed">{(error as Error)?.message}</Text>
        </Stack>
      ) : (
        <div style={{ flex: 1, minHeight: 0 }}>
          <ReactFlow
            nodes={nodes}
            edges={edges2}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            fitView
            attributionPosition="bottom-left"
          >
            <Controls />
            <Background gap={16} size={1} />
          </ReactFlow>
        </div>
      )}
    </Paper>
  );
}
