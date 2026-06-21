import { useState, useRef, useCallback, useEffect } from 'react';
import Editor from '@monaco-editor/react';
import type { QueryDSL, QueryResult } from '@/lib/types';

interface QueryConsoleProps {
  height: number;
  onHeightChange: (h: number) => void;
  onClose: () => void;
  defaultFrom?: string;
}

function MiniResults({ result }: { result: QueryResult }) {
  if (!result.rows.length) {
    return <p className="text-xs text-gray-400 p-4">No rows returned ({result.meta.query_ms}ms)</p>;
  }
  const cols = Object.keys(result.rows[0]).filter(k => k !== '_meta');
  return (
    <div className="overflow-auto p-2">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b">
            {cols.map(c => <th key={c} className="text-left py-1 px-2 font-medium text-gray-600">{c}</th>)}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row, i) => (
            <tr key={i} className="border-b border-gray-50 hover:bg-gray-50">
              {cols.map(c => <td key={c} className="py-1 px-2 text-gray-700">{String(row[c] ?? '')}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-gray-400 mt-1">{result.total} rows · {result.meta.query_ms}ms</p>
    </div>
  );
}

const RECENT_RUNS_KEY = 'heirloom_console_recent_runs';

function getRecentRuns(): string[] {
  try { return JSON.parse(localStorage.getItem(RECENT_RUNS_KEY) || '[]'); } catch { return []; }
}

function saveRecentRun(query: string) {
  const runs = [query, ...getRecentRuns().filter(r => r !== query)].slice(0, 5);
  localStorage.setItem(RECENT_RUNS_KEY, JSON.stringify(runs));
}

export function QueryConsole({ height, onHeightChange, onClose, defaultFrom }: QueryConsoleProps) {
  const [dragging, setDragging] = useState(false);
  const [result, setResult] = useState<QueryResult | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<{ getValue: () => string; setValue: (v: string) => void } | null>(null);

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
  }, []);

  const handleRun = async () => {
    const code = editorRef.current?.getValue();
    if (!code) return;
    try {
      const query: QueryDSL = JSON.parse(code);
      const res = await fetch('/api/query/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(query),
      });
      const data: QueryResult = await res.json();
      setResult(data);
      saveRecentRun(code);
    } catch {
      setResult({ rows: [], total: 0, meta: { query_ms: 0, plan: 'parse error' } });
    }
  };

  const defaultCode = defaultFrom
    ? JSON.stringify({ from: defaultFrom, select: [], limit: 10 }, null, 2)
    : '';

  return (
    <div ref={containerRef} className="border-t border-gray-300 bg-white" style={{ height: `${height}%` }}>
      {/* Resize handle */}
      <div
        className="h-1.5 bg-gray-200 hover:bg-indigo-400 cursor-row-resize transition-colors"
        onMouseDown={onMouseDown}
      />
      <div className="flex items-center justify-between px-4 py-1 border-b border-gray-100">
        <span className="text-xs font-medium text-gray-600">◆ Query Console</span>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-sm">&times;</button>
      </div>
      <div className="flex h-[calc(100%-3.5rem)]">
        {/* Editor pane */}
        <div className="flex-1 border-r border-gray-100">
          <Editor
            height="100%"
            defaultLanguage="json"
            defaultValue={defaultCode}
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
        {/* Results pane */}
        <div className="flex-1 overflow-auto">
          {result ? (
            <MiniResults result={result} />
          ) : (
            <p className="text-xs text-gray-400 p-4">Run query (Ctrl+Enter) to see results</p>
          )}
        </div>
      </div>
      {/* Recent Runs bar */}
      <div className="flex items-center gap-1 px-3 py-1 border-t border-gray-100 bg-gray-50 text-xs text-gray-400 overflow-x-auto h-7">
        <span className="shrink-0">Recent:</span>
        {getRecentRuns().map((q, i) => (
          <button
            key={i}
            onClick={() => editorRef.current?.setValue(q)}
            className="shrink-0 px-1.5 py-0.5 bg-white border rounded hover:bg-gray-100 font-mono truncate max-w-[200px]"
          >
            {q.length > 60 ? q.slice(0, 60) + '...' : q}
          </button>
        ))}
      </div>
    </div>
  );
}
