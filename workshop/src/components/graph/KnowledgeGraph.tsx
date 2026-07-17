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
  Paper, Group, Title, Text, TextInput, Button, Stack,
  useComputedColorScheme, Badge,
} from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

interface KnowledgeNode {
  id: string;
  label: string;
  type: string;
}

interface KnowledgeEdge {
  source: string;
  target: string;
  label: string;
}

interface KnowledgeGraphData {
  nodes: KnowledgeNode[];
  edges: KnowledgeEdge[];
}

async function fetchKnowledgeGraph(root: string, depth: number): Promise<KnowledgeGraphData> {
  const res = await fetch(`/api/knowledge/graph/traverse?root=${encodeURIComponent(root)}&maxDepth=${depth}`);
  if (!res.ok) throw new Error('Failed to fetch knowledge graph');
  return res.json();
}

const NODE_COLORS: Record<string, string> = {
  article: '#3b82f6',
  concept: '#8b5cf6',
  metric: '#10b981',
  default: '#6b7280',
};

export function KnowledgeGraph() {
  const computedColorScheme = useComputedColorScheme('light');
  const isDark = computedColorScheme === 'dark';
  const [rootFqn, setRootFqn] = useState('');
  const [depth, setDepth] = useState(3);
  const [searchValue, setSearchValue] = useState('');

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['knowledge-graph', rootFqn, depth],
    queryFn: () => fetchKnowledgeGraph(rootFqn, depth),
    enabled: !!rootFqn,
  });

  const reactFlowNodes: Node[] = (data?.nodes ?? []).map((node, i) => ({
    id: node.id,
    data: { label: node.label },
    position: {
      x: 120 + (i % 4) * 200,
      y: 80 + Math.floor(i / 4) * 120,
    },
    style: {
      background: isDark ? '#1e3a5f' : '#eff6ff',
      border: `2px solid ${NODE_COLORS[node.type] ?? NODE_COLORS.default}`,
      borderRadius: '8px',
      padding: '8px 14px',
      fontSize: '11px',
      fontWeight: 600,
      color: isDark ? '#bfdbfe' : '#1e40af',
      textAlign: 'center',
    },
  }));

  const reactFlowEdges: Edge[] = (data?.edges ?? []).map((edge, i) => ({
    id: `e-${i}`,
    source: edge.source,
    target: edge.target,
    label: edge.label,
    style: { stroke: '#9ca3af', strokeWidth: 1.5 },
    labelStyle: { fontSize: 9, fill: '#6b7280' },
    markerEnd: { type: MarkerType.ArrowClosed, color: '#9ca3af' },
  }));

  const [nodes, setNodes, onNodesChange] = useNodesState(reactFlowNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(reactFlowEdges);

  // Sync data changes
  useState(() => {
    setNodes(reactFlowNodes);
    setEdges(reactFlowEdges);
  });

  const handleSearch = () => {
    if (searchValue.trim()) {
      setRootFqn(searchValue.trim());
    }
  };

  return (
    <Paper withBorder shadow="sm" radius="md" style={{ display: 'flex', flexDirection: 'column', minHeight: 400 }}>
      <Group justify="space-between" px="md" py="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Group gap="xs">
          <Title order={3} size="h4">Knowledge Graph</Title>
          <Badge size="sm" variant="light" color="grape">Phase 4.1</Badge>
        </Group>
        <Group gap="xs">
          <TextInput
            placeholder="Root FQN (e.g. sales.overview)"
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
      {!rootFqn ? (
        <Stack align="center" justify="center" style={{ flex: 1, minHeight: 300 }} gap="xs">
          <Text size="sm" c="dimmed">Enter a KnowledgeArticle FQN and click Traverse</Text>
          <Text size="xs" c="dimmed">Example: sales.forecast, hr.policies.remote-work</Text>
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
            edges={edges}
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
