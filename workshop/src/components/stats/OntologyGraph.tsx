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
import { useComputedColorScheme } from '@mantine/core';
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
  const nodeBorder = isDark ? '#818cf8' : '#818cf8';
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
  types.forEach(source => {
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
    setNodes(nextNodes.map(n => ({
      ...n,
      position: { x: n.position.x * factor, y: n.position.y * factor },
    })));
    setEdges(nextEdges);
  }, [types, isDark, spacious, setNodes, setEdges]);

  const onLayout = useCallback(() => {
    const factor = spacious ? 1.5 : 1;
    const sampleStyle = buildGraph(types, isDark).nodes[0]?.style;
    setNodes(prev => prev.map((n, i) => ({
      ...n,
      position: { x: (120 + (i % 3) * 220) * factor, y: (80 + Math.floor(i / 3) * 160) * factor },
      style: sampleStyle,
    })));
  }, [spacious, setNodes, types, isDark]);

  return (
    <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 shadow-sm h-full min-h-[400px] flex flex-col">
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-100 dark:border-gray-800">
        <h2 className="text-sm font-semibold text-gray-800 dark:text-gray-100">Ontology Graph</h2>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setSpacious(s => !s)}
            className="px-2 py-1 text-xs rounded border border-gray-200 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800 text-gray-600 dark:text-gray-300 transition-colors"
          >
            {spacious ? 'Compact' : 'Spacious'}
          </button>
          <button
            onClick={onLayout}
            className="px-2 py-1 text-xs rounded border border-gray-200 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800 text-gray-600 dark:text-gray-300 transition-colors"
          >
            Reset Layout
          </button>
        </div>
      </div>
      <div className="flex-1 min-h-0">
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
    </div>
  );
}
