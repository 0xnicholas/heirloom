import { useCallback, useEffect, useMemo, useState } from 'react';
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
import { useComputedColorScheme, Paper, Group, Title, Button } from '@mantine/core';
import type { RelationshipSemantics, ResourceType } from '@/lib/types';

interface OntologyGraphProps {
  types: ResourceType[];
}

const SEMANTICS_STYLES: Record<RelationshipSemantics, { stroke: string; strokeDasharray?: string; markerEnd?: MarkerType }> = {
  Ownership: { stroke: '#ef4444', markerEnd: MarkerType.ArrowClosed },
  Reference: { stroke: '#3b82f6', strokeDasharray: '6 4', markerEnd: MarkerType.ArrowClosed },
  Association: { stroke: '#9ca3af', strokeDasharray: '2 3' },
};

function buildGraph(types: ResourceType[], isDark: boolean) {
  const nodeBg = isDark ? '#312e81' : '#eef2ff';
  const nodeBorder = '#818cf8';
  const nodeText = isDark ? '#e0e7ff' : '#4338ca';
  const labelBg = isDark ? '#1f2937' : '#fff';

  const nodes: Node[] = types.map((type, i) => ({
    id: type.name,
    data: { label: `${type.name}\nv${type.version}` },
    position: { x: 120 + (i % 3) * 220, y: 80 + Math.floor(i / 3) * 160 },
    style: {
      background: nodeBg,
      border: `1px solid ${nodeBorder}`,
      borderRadius: '8px',
      padding: '10px 16px',
      fontSize: '12px',
      fontWeight: 600,
      color: nodeText,
      textAlign: 'center',
      whiteSpace: 'pre-line',
    },
  }));

  const edges: Edge[] = [];
  types.forEach((source) => {
    source.relationships.forEach((rel, i) => {
      const style = SEMANTICS_STYLES[rel.semantics];
      edges.push({
        id: `e-${source.name}-${rel.targetType}-${i}`,
        source: source.name,
        target: rel.targetType,
        label: rel.label,
        animated: rel.semantics === 'Ownership',
        style: { stroke: style.stroke, strokeWidth: 2, strokeDasharray: style.strokeDasharray },
        labelStyle: { fill: style.stroke, fontSize: 10, fontWeight: 500 },
        labelBgStyle: { fill: labelBg, fillOpacity: 0.9 },
        markerEnd: style.markerEnd ? { type: style.markerEnd, color: style.stroke } : undefined,
      });
    });
  });

  return { nodes, edges };
}

export function OntologyGraph({ types }: OntologyGraphProps) {
  const computedColorScheme = useComputedColorScheme('light');
  const isDark = computedColorScheme === 'dark';
  const { nodes: initialNodes, edges: initialEdges } = useMemo(() => buildGraph(types, isDark), [types, isDark]);
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [spacious, setSpacious] = useState(false);

  useEffect(() => {
    const { nodes: nextNodes, edges: nextEdges } = buildGraph(types, isDark);
    const factor = spacious ? 1.5 : 1;
    setNodes(nextNodes.map((n) => ({
      ...n,
      position: { x: n.position.x * factor, y: n.position.y * factor },
    })));
    setEdges(nextEdges);
  }, [types, isDark, spacious, setNodes, setEdges]);

  const onLayout = useCallback(() => {
    const factor = spacious ? 1.5 : 1;
    const sampleStyle = buildGraph(types, isDark).nodes[0]?.style;
    setNodes((prev) => prev.map((n, i) => ({
      ...n,
      position: { x: (120 + (i % 3) * 220) * factor, y: (80 + Math.floor(i / 3) * 160) * factor },
      style: sampleStyle,
    })));
  }, [spacious, setNodes, types, isDark]);

  return (
    <Paper withBorder shadow="sm" radius="md" style={{ display: 'flex', flexDirection: 'column', minHeight: 400 }}>
      <Group justify="space-between" px="md" py="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={3} size="h4">Ontology Graph</Title>
        <Group gap="xs">
          <Button
            onClick={() => setSpacious((s) => !s)}
            size="xs"
            variant="default"
          >
            {spacious ? 'Compact' : 'Spacious'}
          </Button>
          <Button
            onClick={onLayout}
            size="xs"
            variant="default"
          >
            Reset Layout
          </Button>
        </Group>
      </Group>
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
    </Paper>
  );
}
