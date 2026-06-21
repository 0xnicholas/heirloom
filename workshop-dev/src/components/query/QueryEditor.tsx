import { useRef, useCallback } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import type { SchemaRegistrySnapshot, Diagnostic } from '@/lib/types';
import { validateQuery } from '@/lib/validation/query-validator';
import type { QueryDSL } from '@/lib/types';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Monaco = any;

interface QueryEditorProps {
  value: string;
  onChange: (value: string) => void;
  snapshot: SchemaRegistrySnapshot;
  onDiagnostics: (diags: Diagnostic[]) => void;
}

export function QueryEditor({ value, onChange, snapshot, onDiagnostics }: QueryEditorProps) {
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

  return (
    <Editor
      height="100%"
      defaultLanguage="json"
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
  );
}
