import { useState, useCallback, useRef, useEffect } from 'react';
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
    const name = prompt('Query name:') || `Query ${new Date().toLocaleTimeString()}`;
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
    <div className="flex h-full bg-gray-50 dark:bg-gray-950">
      <div className="w-[260px] border-r border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 overflow-auto shrink-0">
        <QueryHistory
          queries={queriesQuery.data || []}
          onSelect={handleSelectQuery}
          onDelete={id => deleteMutation.mutate(id)}
          onToggleFavorite={() => {
            // TODO: optimistic update on favorited flag
          }}
        />
      </div>
      <div ref={containerRef} className="flex-1 flex flex-col">
        {/* Toolbar */}
        <div className="flex items-center gap-2 px-4 py-1.5 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 shrink-0">
          <button
            onClick={handleRun}
            className="px-3 py-1 text-sm font-medium text-white bg-indigo-600 rounded hover:bg-indigo-700"
          >
            Run
          </button>
          <button
            onClick={handleSaveQuery}
            className="px-3 py-1 text-sm text-gray-600 dark:text-gray-300 border border-gray-200 dark:border-gray-700 rounded hover:bg-gray-50 dark:hover:bg-gray-800"
          >
            Save
          </button>
          <span className="ml-2 text-xs text-gray-400 dark:text-gray-500">Snippets:</span>
          {(['basic', 'traverse', 'aggregate', 'search'] as const).map(k => (
            <button
              key={k}
              onClick={() => setEditorValue(QUERY_TEMPLATES[k])}
              className="px-2 py-0.5 text-xs text-gray-500 dark:text-gray-400 border border-gray-200 dark:border-gray-700 rounded hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              {k}
            </button>
          ))}
          {diagnostics.length > 0 && (
            <span className="ml-auto text-xs text-red-500">
              {diagnostics.filter(d => d.severity === 'error').length} errors
            </span>
          )}
        </div>
        {/* Editor (top, percentage-based height) */}
        <div style={{ height: `${splitPercent}%` }}>
          <QueryEditor
            value={editorValue}
            onChange={setEditorValue}
            snapshot={snapshot}
            onDiagnostics={setDiagnostics}
          />
        </div>
        {/* Draggable divider */}
        <div
          className="h-1 bg-gray-200 dark:bg-gray-700 hover:bg-indigo-400 cursor-row-resize transition-colors shrink-0"
          onMouseDown={onMouseDown}
        />
        {/* Results (bottom) */}
        <div className="flex-1 overflow-hidden">
          <QueryResults result={result} />
        </div>
      </div>
    </div>
  );
}
