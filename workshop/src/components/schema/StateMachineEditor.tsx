import { useCallback, useEffect } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  addEdge,
  type Connection,
  type Node,
  type Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Box, Paper, Text } from '@mantine/core';
import type { StateTransition } from '@/lib/types';

interface StateMachineEditorProps {
  transitions: StateTransition[];
  onChange: (transitions: StateTransition[]) => void;
}

function transitionsToFlow(transitions: StateTransition[]): { nodes: Node[]; edges: Edge[] } {
  const stateSet = new Set<string>();
  for (const t of transitions) {
    stateSet.add(t.from);
    stateSet.add(t.to);
  }

  const nodes: Node[] = [...stateSet].map((state, i) => ({
    id: state,
    data: { label: state },
    position: { x: 150 + (i % 3) * 200, y: 50 + Math.floor(i / 3) * 120 },
    style: {
      background: 'var(--mantine-color-indigo-0)',
      border: '1px solid var(--mantine-color-indigo-4)',
      borderRadius: '8px',
      padding: '8px 20px',
      fontSize: '13px',
      fontWeight: 600,
      color: 'var(--mantine-color-indigo-7)',
    },
  }));

  const edges: Edge[] = transitions.map((t, i) => ({
    id: `e-${t.from}-${t.to}-${i}`,
    source: t.from,
    target: t.to,
    label: t.label || '',
    animated: true,
    style: { stroke: 'var(--mantine-color-indigo-3)' },
    labelStyle: { fontSize: '10px', fill: 'var(--mantine-color-indigo-6)' },
    labelBgStyle: { fill: 'var(--mantine-color-body)', fillOpacity: 0.9 },
  }));

  return { nodes, edges };
}

function flowToTransitions(_nodes: Node[], edges: Edge[]): StateTransition[] {
  return edges.map((e) => ({
    from: e.source,
    to: e.target,
    label: (e.label as string) || undefined,
  }));
}

export function StateMachineEditor({ transitions, onChange }: StateMachineEditorProps) {
  const { nodes: initialNodes, edges: initialEdges } = transitionsToFlow(transitions);
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  // Re-sync when transitions prop changes
  useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = transitionsToFlow(transitions);
    setNodes(newNodes);
    setEdges(newEdges);
  }, [transitions, setNodes, setEdges]);

  const onConnect = useCallback(
    (connection: Connection) => {
      const newEdges = addEdge(connection, edges);
      setEdges(newEdges);
      onChange(flowToTransitions(nodes, newEdges));
    },
    [edges, nodes, setEdges, onChange],
  );

  return (
    <Box>
      <Text size="sm" fw={600} mb="xs">State Machine</Text>
      <Paper withBorder radius="md" style={{ height: 192, overflow: 'hidden' }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          fitView
          attributionPosition="bottom-left"
        >
          <Controls />
          <Background />
        </ReactFlow>
      </Paper>
    </Box>
  );
}
