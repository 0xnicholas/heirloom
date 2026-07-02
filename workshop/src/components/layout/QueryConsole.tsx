import { useState, useRef, useCallback, useEffect } from 'react';
import Editor from '@monaco-editor/react';
import { useComputedColorScheme, Paper, Group, ActionIcon, Text, ScrollArea, Box } from '@mantine/core';
import { IconX, IconGripVertical } from '@tabler/icons-react';
import { executeQuery } from '@/api/query';
import type { QueryDSL, QueryResult } from '@/lib/types';

interface QueryConsoleProps {
  height: number;
  onHeightChange: (h: number) => void;
  onClose: () => void;
  defaultFrom?: string;
}

function MiniResults({ result }: { result: QueryResult }) {
  if (!result.rows.length) {
    return (
      <Text size="xs" c="dimmed" p="sm">
        No rows returned ({result.meta.query_ms}ms)
      </Text>
    );
  }
  const cols = Object.keys(result.rows[0]).filter((k) => k !== '_meta');
  return (
    <ScrollArea h="100%">
      <Box p="xs">
        <Box component="table" w="100%" style={{ fontSize: 12, borderCollapse: 'collapse' }}>
          <Box component="thead">
            <Box component="tr">
              {cols.map((c) => (
                <Box
                  component="th"
                  key={c}
                  style={{
                    textAlign: 'left',
                    padding: '4px 8px',
                    borderBottom: '1px solid var(--mantine-color-default-border)',
                    fontWeight: 500,
                  }}
                >
                  {c}
                </Box>
              ))}
            </Box>
          </Box>
          <Box component="tbody">
            {result.rows.map((row, i) => (
              <Box component="tr" key={i}>
                {cols.map((c) => (
                  <Box
                    component="td"
                    key={c}
                    style={{ padding: '4px 8px' }}
                  >
                    {String(row[c] ?? '')}
                  </Box>
                ))}
              </Box>
            ))}
          </Box>
        </Box>
        <Text size="xs" c="dimmed" mt={4}>
          {result.total} rows · {result.meta.query_ms}ms
        </Text>
      </Box>
    </ScrollArea>
  );
}

const RECENT_RUNS_KEY = 'heirloom_console_recent_runs';

function getRecentRuns(): string[] {
  try {
    return JSON.parse(localStorage.getItem(RECENT_RUNS_KEY) || '[]');
  } catch {
    return [];
  }
}

function saveRecentRun(query: string) {
  const runs = [query, ...getRecentRuns().filter((r) => r !== query)].slice(0, 5);
  localStorage.setItem(RECENT_RUNS_KEY, JSON.stringify(runs));
}

export function QueryConsole({ height, onHeightChange, onClose, defaultFrom }: QueryConsoleProps) {
  const [dragging, setDragging] = useState(false);
  const [result, setResult] = useState<QueryResult | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<{ getValue: () => string; setValue: (v: string) => void } | null>(null);
  const computedColorScheme = useComputedColorScheme('light');
  const monacoTheme = computedColorScheme === 'dark' ? 'vs-dark' : 'vs';

  // Drag-to-resize
  const onMouseDown = useCallback(() => setDragging(true), []);
  const onMouseUp = useCallback(() => setDragging(false), []);

  useEffect(() => {
    if (!dragging) return;
    const onMouseMove = (e: MouseEvent) => {
      const vh = window.innerHeight;
      const newHeight = Math.round(((vh - e.clientY) / vh) * 100);
      onHeightChange(Math.max(25, Math.min(75, newHeight)));
    };
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
  }, [dragging, onHeightChange, onMouseUp]);

  const handleRun = useCallback(async () => {
    const code = editorRef.current?.getValue();
    if (!code) return;
    try {
      const query: QueryDSL = JSON.parse(code);
      const data = await executeQuery(query);
      setResult(data);
      saveRecentRun(code);
    } catch {
      setResult({ rows: [], total: 0, meta: { query_ms: 0, plan: 'parse error' } });
    }
  }, []);

  // Ctrl+Enter to run
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === 'Enter') {
        e.preventDefault();
        handleRun();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [handleRun]);

  const defaultCode = defaultFrom
    ? JSON.stringify({ from: defaultFrom, select: [], limit: 10 }, null, 2)
    : '';

  return (
    <Paper
      ref={containerRef}
      withBorder
      radius={0}
      style={{ height: `${height}%`, display: 'flex', flexDirection: 'column' }}
    >
      {/* Resize handle */}
      <Box
        onMouseDown={onMouseDown}
        style={{
          height: 6,
          cursor: 'row-resize',
          backgroundColor: 'var(--mantine-color-gray-2)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <IconGripVertical size={12} color="var(--mantine-color-dimmed)" />
      </Box>

      {/* Header */}
      <Group justify="space-between" px="md" py={6} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Text size="xs" fw={500}>◆ Query Console</Text>
        <ActionIcon onClick={onClose} variant="subtle" size="sm" aria-label="Close console">
          <IconX size={14} />
        </ActionIcon>
      </Group>

      {/* Body: editor | results */}
      <Box style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <Box style={{ flex: 1, borderRight: '1px solid var(--mantine-color-default-border)' }}>
          <Editor
            height="100%"
            defaultLanguage="json"
            defaultValue={defaultCode}
            theme={monacoTheme}
            onMount={(editor) => { editorRef.current = editor; }}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              tabSize: 2,
            }}
          />
        </Box>
        <Box style={{ flex: 1, overflow: 'auto' }}>
          {result ? (
            <MiniResults result={result} />
          ) : (
            <Text size="xs" c="dimmed" p="sm">
              Run query (Ctrl+Enter) to see results
            </Text>
          )}
        </Box>
      </Box>

      {/* Recent Runs bar */}
      <Group
        gap={6}
        px="sm"
        py={4}
        style={{
          borderTop: '1px solid var(--mantine-color-default-border)',
          backgroundColor: 'var(--mantine-color-gray-0)',
          fontSize: 11,
          color: 'var(--mantine-color-dimmed)',
          overflowX: 'auto',
          minHeight: 28,
        }}
      >
        <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>Recent:</Text>
        {getRecentRuns().map((q, i) => (
          <button
            key={i}
            type="button"
            onClick={() => editorRef.current?.setValue(q)}
            style={{
              flexShrink: 0,
              padding: '2px 6px',
              background: 'var(--mantine-color-body)',
              border: '1px solid var(--mantine-color-default-border)',
              borderRadius: 4,
              fontFamily: 'monospace',
              fontSize: 11,
              maxWidth: 200,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              cursor: 'pointer',
            }}
          >
            {q.length > 60 ? `${q.slice(0, 60)}...` : q}
          </button>
        ))}
      </Group>
    </Paper>
  );
}
