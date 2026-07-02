import { useRef, useCallback } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { useComputedColorScheme, Tabs, Box, Group, Button, Text, ScrollArea } from '@mantine/core';
import { IconPlayerPlay, IconDeviceFloppy } from '@tabler/icons-react';
import type { SchemaRegistrySnapshot, Diagnostic, QueryDSL } from '@/lib/types';
import { validateQuery } from '@/lib/validation/query-validator';
import { QUERY_TEMPLATES } from '@/lib/constants';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Monaco = any;

interface QueryEditorProps {
  value: string;
  onChange: (value: string) => void;
  snapshot: SchemaRegistrySnapshot;
  onDiagnostics: (diags: Diagnostic[]) => void;
  onRun?: () => void;
  onSave?: () => void;
}

export function QueryEditor({ value, onChange, snapshot, onDiagnostics, onRun, onSave }: QueryEditorProps) {
  const computedColorScheme = useComputedColorScheme('light');
  const monacoTheme = computedColorScheme === 'dark' ? 'vs-dark' : 'vs';
  const editorRef = useRef<Monaco>(null);
  const monacoRef = useRef<Monaco>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  const handleMount: OnMount = (editor, monaco) => {
    editorRef.current = editor;
    monacoRef.current = monaco;

    monaco.languages.registerCompletionItemProvider('json', {
      provideCompletionItems: (model: Monaco, position: Monaco) => {
        const word = model.getWordUntilPosition(position);
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn,
        };
        const suggestions: Monaco[] = [];

        const textBefore = model.getValueInRange({
          startLineNumber: 1,
          startColumn: 1,
          endLineNumber: position.lineNumber,
          endColumn: position.column,
        });

        if (textBefore.includes('"from"')) {
          for (const [name] of snapshot.types) {
            suggestions.push({
              label: name,
              kind: monaco.languages.CompletionItemKind.Class,
              insertText: name,
              range,
            });
          }
        }

        if (textBefore.includes('"select"')) {
          const match = textBefore.match(/"from"\s*:\s*"(\w+)"/);
          if (match) {
            const fromType = snapshot.types.get(match[1]);
            if (fromType) {
              for (const field of fromType.fields) {
                suggestions.push({
                  label: field.name,
                  kind: monaco.languages.CompletionItemKind.Field,
                  insertText: field.name,
                  range,
                  detail: field.type,
                });
              }
            }
          }
        }

        if (textBefore.includes('--[')) {
          for (const [, type] of snapshot.types) {
            for (const rel of type.relationships) {
              suggestions.push({
                label: rel.label,
                kind: monaco.languages.CompletionItemKind.Reference,
                insertText: rel.label,
                range,
                detail: `${type.name} → ${rel.targetType}`,
              });
            }
          }
        }

        for (const fn of ['$count', '$sum', '$avg', '$max', '$min']) {
          suggestions.push({
            label: fn,
            kind: monaco.languages.CompletionItemKind.Function,
            insertText: fn,
            range,
          });
        }

        return { suggestions };
      },
    });
  };

  const handleChange = useCallback(
    (val: string | undefined) => {
      const code = val || '';
      onChange(code);

      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        try {
          const query: QueryDSL = JSON.parse(code);
          const diags = validateQuery(query, snapshot);
          onDiagnostics(diags);

          const m = monacoRef.current;
          const ed = editorRef.current;
          if (m && ed) {
            const model = ed.getModel();
            if (model) {
              m.editor.setModelMarkers(
                model,
                'query-validator',
                diags.map((d: Diagnostic) => ({
                  startLineNumber: 1,
                  startColumn: 1,
                  endLineNumber: 1,
                  endColumn: 1,
                  message: d.message,
                  severity:
                    d.severity === 'error'
                      ? m.MarkerSeverity.Error
                      : d.severity === 'warning'
                        ? m.MarkerSeverity.Warning
                        : m.MarkerSeverity.Info,
                }))
              );
            }
          }
        } catch {
          // JSON parse error — let Monaco's built-in validation handle it
        }
      }, 500);
    },
    [onChange, snapshot, onDiagnostics]
  );

  const handleTemplateClick = (template: string) => {
    onChange(template);
    editorRef.current?.setValue?.(template);
  };

  return (
    <Box h="100%" style={{ display: 'flex', flexDirection: 'column' }}>
      {/* Action bar */}
      <Group justify="space-between" px="md" py="xs" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Text size="sm" fw={500}>Query</Text>
        <Group gap="xs">
          {onRun && (
            <Button onClick={onRun} size="xs" leftSection={<IconPlayerPlay size={14} />}>
              Run
            </Button>
          )}
          {onSave && (
            <Button onClick={onSave} size="xs" variant="default" leftSection={<IconDeviceFloppy size={14} />}>
              Save
            </Button>
          )}
        </Group>
      </Group>

      {/* Tabs: Editor / Templates */}
      <Tabs defaultValue="editor" style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <Tabs.List>
          <Tabs.Tab value="editor">Editor</Tabs.Tab>
          <Tabs.Tab value="templates">Templates</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="editor" style={{ flex: 1, minHeight: 0 }}>
          <Editor
            height="100%"
            defaultLanguage="json"
            theme={monacoTheme}
            value={value}
            onChange={handleChange}
            onMount={handleMount}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              tabSize: 2,
            }}
          />
        </Tabs.Panel>
        <Tabs.Panel value="templates" style={{ flex: 1, overflow: 'auto', padding: '12px' }}>
          <ScrollArea h="100%">
            <Tabs defaultValue="basic" orientation="vertical" variant="pills">
              <Tabs.List>
                <Tabs.Tab value="basic">Basic</Tabs.Tab>
                <Tabs.Tab value="traverse">Traverse</Tabs.Tab>
                <Tabs.Tab value="aggregate">Aggregate</Tabs.Tab>
                <Tabs.Tab value="search">Search</Tabs.Tab>
              </Tabs.List>
              <Tabs.Panel value="basic" pt="xs">
                <Box
                  component="pre"
                  onClick={() => handleTemplateClick(QUERY_TEMPLATES.basic)}
                  style={{
                    cursor: 'pointer',
                    padding: 12,
                    backgroundColor: 'var(--mantine-color-gray-0)',
                    borderRadius: 4,
                    fontSize: 12,
                    fontFamily: 'monospace',
                  }}
                >
                  {QUERY_TEMPLATES.basic}
                </Box>
              </Tabs.Panel>
              <Tabs.Panel value="traverse" pt="xs">
                <Box
                  component="pre"
                  onClick={() => handleTemplateClick(QUERY_TEMPLATES.traverse)}
                  style={{
                    cursor: 'pointer',
                    padding: 12,
                    backgroundColor: 'var(--mantine-color-gray-0)',
                    borderRadius: 4,
                    fontSize: 12,
                    fontFamily: 'monospace',
                  }}
                >
                  {QUERY_TEMPLATES.traverse}
                </Box>
              </Tabs.Panel>
              <Tabs.Panel value="aggregate" pt="xs">
                <Box
                  component="pre"
                  onClick={() => handleTemplateClick(QUERY_TEMPLATES.aggregate)}
                  style={{
                    cursor: 'pointer',
                    padding: 12,
                    backgroundColor: 'var(--mantine-color-gray-0)',
                    borderRadius: 4,
                    fontSize: 12,
                    fontFamily: 'monospace',
                  }}
                >
                  {QUERY_TEMPLATES.aggregate}
                </Box>
              </Tabs.Panel>
              <Tabs.Panel value="search" pt="xs">
                <Box
                  component="pre"
                  onClick={() => handleTemplateClick(QUERY_TEMPLATES.search)}
                  style={{
                    cursor: 'pointer',
                    padding: 12,
                    backgroundColor: 'var(--mantine-color-gray-0)',
                    borderRadius: 4,
                    fontSize: 12,
                    fontFamily: 'monospace',
                  }}
                >
                  {QUERY_TEMPLATES.search}
                </Box>
              </Tabs.Panel>
            </Tabs>
          </ScrollArea>
        </Tabs.Panel>
      </Tabs>
    </Box>
  );
}
