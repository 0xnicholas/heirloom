import { useState, useCallback, useRef, useEffect } from 'react';
import { Box, Button, Group, Text } from '@mantine/core';
import { IconPlayerPlay, IconDeviceFloppy } from '@tabler/icons-react';
import { QueryHistory } from '@/components/query/QueryHistory';
import { QueryEditor } from '@/components/query/QueryEditor';
import { QueryResults } from '@/components/query/QueryResults';
import { useQueries } from '@/hooks/useQueries';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { useRegistrySnapshot } from '@/hooks/useValidation';
import { QUERY_TEMPLATES } from '@/lib/constants';
import type { SavedQuery, QueryDSL, QueryResult, Diagnostic } from '@/lib/types';

export function QueryPage() {
  const { typesQuery } = useSchemaRegistry();
  const { queriesQuery, executeMutation, saveMutation, deleteMutation } = useQueries();
  const types = typesQuery.data || [];
  const snapshot = useRegistrySnapshot(types, []);

  const [editorValue, setEditorValue] = useState(
    JSON.stringify({ from: 'Customer', select: ['name'], limit: 10 }, null, 2),
  );
  const [result, setResult] = useState<QueryResult | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const [splitPercent, setSplitPercent] = useState(40);
  const containerRef = useRef<HTMLDivElement>(null);
  const draggingRef = useRef(false);

  const handleRun = async () => {
    try {
      const query: QueryDSL = JSON.parse(editorValue);
      const res = await executeMutation.mutateAsync(query);
      setResult(res);
    } catch {
      // JSON parse error — Monaco diagnostics will handle it
    }
  };

  const handleSelectQuery = (q: SavedQuery) => {
    setEditorValue(JSON.stringify(q.query, null, 2));
    setResult(null);
  };

  const handleSaveQuery = async () => {
    const id = crypto.randomUUID();
    const name = window.prompt('Query name:') || `Query ${new Date().toLocaleTimeString()}`;
    const query: QueryDSL = JSON.parse(editorValue);
    await saveMutation.mutateAsync({
      id,
      name,
      query,
      createdAt: new Date().toISOString(),
      favorited: false,
    });
  };

  // Resizable divider logic
  const onMouseDown = useCallback(() => {
    draggingRef.current = true;
  }, []);

  useEffect(() => {
    const onMouseMove = (e: MouseEvent) => {
      if (!draggingRef.current || !containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      setSplitPercent(Math.round(((e.clientY - rect.top) / rect.height) * 100));
    };
    const onMouseUp = () => {
      draggingRef.current = false;
    };
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
  }, []);

  return (
    <Box style={{ display: 'flex', height: '100%' }}>
      <Box w={260} style={{ borderRight: '1px solid var(--mantine-color-default-border)', overflow: 'auto', flexShrink: 0 }}>
        <QueryHistory
          queries={queriesQuery.data || []}
          onSelect={handleSelectQuery}
          onDelete={(id) => deleteMutation.mutate(id)}
          onToggleFavorite={() => {
            // TODO: optimistic update on favorited flag
          }}
        />
      </Box>
      <Box ref={containerRef} style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        {/* Toolbar */}
        <Group gap="xs" px="md" py={6} style={{ borderBottom: '1px solid var(--mantine-color-default-border)', flexShrink: 0 }}>
          <Button onClick={handleRun} size="xs" leftSection={<IconPlayerPlay size={14} />}>
            Run
          </Button>
          <Button
            onClick={handleSaveQuery}
            size="xs"
            variant="default"
            leftSection={<IconDeviceFloppy size={14} />}
          >
            Save
          </Button>
          <Text size="xs" c="dimmed" ml="sm">Snippets:</Text>
          {(['basic', 'traverse', 'aggregate', 'search'] as const).map((k) => (
            <Button
              key={k}
              onClick={() => setEditorValue(QUERY_TEMPLATES[k])}
              size="compact-xs"
              variant="default"
            >
              {k}
            </Button>
          ))}
          {diagnostics.length > 0 && (
            <Text size="xs" c="red" ml="auto">
              {diagnostics.filter((d) => d.severity === 'error').length} errors
            </Text>
          )}
        </Group>
        {/* Editor (top, percentage-based height) */}
        <Box style={{ height: `${splitPercent}%` }}>
          <QueryEditor
            value={editorValue}
            onChange={setEditorValue}
            snapshot={snapshot}
            onDiagnostics={setDiagnostics}
            onRun={handleRun}
            onSave={handleSaveQuery}
          />
        </Box>
        {/* Draggable divider */}
        <Box
          onMouseDown={onMouseDown}
          style={{
            height: 4,
            backgroundColor: 'var(--mantine-color-gray-2)',
            cursor: 'row-resize',
            flexShrink: 0,
          }}
        />
        {/* Results (bottom) */}
        <Box style={{ flex: 1, overflow: 'hidden' }}>
          <QueryResults result={result} />
        </Box>
      </Box>
    </Box>
  );
}
