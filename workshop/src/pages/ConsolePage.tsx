import { useState, useRef } from 'react';
import Editor from '@monaco-editor/react';
import { Box, Button, Code, Group, Paper, Text, Title } from '@mantine/core';
import { IconPlayerPlay } from '@tabler/icons-react';
import { useComputedColorScheme } from '@mantine/core';
import { executeQuery } from '@/api/query';
import type { QueryDSL, QueryResult } from '@/lib/types';

export function ConsolePage() {
  const computedColorScheme = useComputedColorScheme('light');
  const monacoTheme = computedColorScheme === 'dark' ? 'vs-dark' : 'vs';
  const [result, setResult] = useState<QueryResult | null>(null);
  const editorRef = useRef<{ getValue: () => string } | null>(null);

  const handleRun = async () => {
    const code = editorRef.current?.getValue();
    if (!code) return;
    try {
      const query: QueryDSL = JSON.parse(code);
      const data = await executeQuery(query);
      setResult(data);
    } catch {
      setResult({ rows: [], total: 0, meta: { query_ms: 0, plan: 'parse error' } });
    }
  };

  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Group justify="space-between" align="flex-start">
          <Box>
            <Title order={2}>Console</Title>
            <Text size="xs" c="dimmed">Interactive query console</Text>
          </Box>
          <Button onClick={handleRun} leftSection={<IconPlayerPlay size={14} />}>
            Run
          </Button>
        </Group>
      </Paper>
      <Box style={{ flex: 1, display: 'flex' }}>
        <Box style={{ flex: 1, borderRight: '1px solid var(--mantine-color-default-border)' }}>
          <Editor
            height="100%"
            defaultLanguage="json"
            theme={monacoTheme}
            defaultValue={`{\n  "from": "Customer",\n  "select": ["name"],\n  "limit": 10\n}`}
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
            <Code block fz="xs" p="md">
              {JSON.stringify(result, null, 2)}
            </Code>
          ) : (
            <Text size="sm" c="dimmed" p="md">Run a query to see results</Text>
          )}
        </Box>
      </Box>
    </Box>
  );
}
