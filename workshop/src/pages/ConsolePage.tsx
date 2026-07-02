import { useState, useRef } from 'react';
import Editor from '@monaco-editor/react';
import { useComputedColorScheme } from '@mantine/core';
import { executeQuery } from '@/api/query';
import type { QueryDSL, QueryResult } from '@/lib/types';

export function ConsolePage() {
  const computedColorScheme = useComputedColorScheme('light');
  const theme = computedColorScheme === 'dark' ? 'vs-dark' : 'vs';
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
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-950">
      <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-gray-900 dark:text-white">Console</h1>
          <p className="text-xs text-gray-500 dark:text-gray-400">Interactive query console</p>
        </div>
        <button
          onClick={handleRun}
          className="px-4 py-1.5 text-sm font-medium text-white bg-indigo-600 rounded-md hover:bg-indigo-700 transition-colors"
        >
          Run
        </button>
      </div>
      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 border-r border-gray-200 dark:border-gray-700">
          <Editor
            height="100%"
            defaultLanguage="json"
            theme={theme}
            defaultValue={`{\n  "from": "Customer",\n  "select": ["name"],\n  "limit": 10\n}`}
            onMount={editor => { editorRef.current = editor; }}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              tabSize: 2,
            }}
          />
        </div>
        <div className="flex-1 overflow-auto bg-white dark:bg-gray-900">
          {result ? (
            <pre className="p-4 text-xs font-mono text-gray-700 dark:text-gray-300 whitespace-pre-wrap">
              {JSON.stringify(result, null, 2)}
            </pre>
          ) : (
            <p className="p-4 text-sm text-gray-400 dark:text-gray-500">Run a query to see results</p>
          )}
        </div>
      </div>
    </div>
  );
}
