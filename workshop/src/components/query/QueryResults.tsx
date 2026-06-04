import { useState } from 'react';
import type { QueryResult } from '@/lib/types';

type ViewMode = 'table' | 'graph' | 'raw';

interface QueryResultsProps {
  result: QueryResult | null;
}

function GraphResultView({ rows }: { rows: QueryResult['rows'] }) {
  const seen = new Set<string>();
  const nodes: { id: string; label: string }[] = [];

  for (const row of rows) {
    const meta = row._meta;
    if (meta && !seen.has(meta.rid)) {
      seen.add(meta.rid);
      nodes.push({
        id: meta.rid,
        label: `${meta.type}: ${meta.rid.split('.').pop() ?? meta.rid}`,
      });
    }
  }

  // Create edges from consecutive RIDs for basic rendering
  const rids = [...seen];
  const edges: { id: string; source: string; target: string }[] = [];
  for (let i = 1; i < rids.length; i++) {
    edges.push({ id: `e-${i}`, source: rids[i - 1], target: rids[i] });
  }

  return (
    <div className="p-4">
      <p className="text-xs text-gray-500 mb-3">
        Graph: {nodes.length} node{nodes.length !== 1 ? 's' : ''},{' '}
        {edges.length} edge{edges.length !== 1 ? 's' : ''}
      </p>
      <div className="text-xs font-mono text-gray-600 space-y-2">
        {nodes.map(n => (
          <div key={n.id} className="flex items-center gap-2">
            <span className="text-indigo-500">⬤</span>
            <span>{n.label}</span>
          </div>
        ))}
        {edges.length > 0 && (
          <div className="mt-3 text-gray-400">
            {edges.map(e => (
              <div key={e.id}>
                {e.source.split('.').pop()} → {e.target.split('.').pop()}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function QueryResults({ result }: QueryResultsProps) {
  const [mode, setMode] = useState<ViewMode>('table');

  if (!result) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400 text-sm">
        Run a query to see results
      </div>
    );
  }

  if (result.rows.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400 text-sm">
        No rows returned ({result.meta.query_ms}ms)
      </div>
    );
  }

  const columns =
    result.rows.length > 0
      ? Object.keys(result.rows[0]).filter(k => k !== '_meta')
      : [];

  return (
    <div className="flex flex-col h-full">
      {/* View toggle bar */}
      <div className="flex items-center gap-1 px-3 py-1.5 border-b bg-gray-50">
        {(['table', 'graph', 'raw'] as ViewMode[]).map(m => (
          <button
            key={m}
            onClick={() => setMode(m)}
            className={`px-3 py-0.5 text-xs rounded transition-colors ${
              mode === m
                ? 'bg-indigo-100 text-indigo-700 font-medium'
                : 'text-gray-500 hover:bg-gray-200'
            }`}
          >
            {m === 'table' ? 'Table' : m === 'graph' ? 'Graph' : 'Raw JSON'}
          </button>
        ))}
        <span className="ml-auto text-xs text-gray-400">
          {result.total} rows · {result.meta.query_ms}ms
        </span>
      </div>

      {/* View content */}
      <div className="flex-1 overflow-auto">
        {mode === 'table' && (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-gray-50 sticky top-0">
                {columns.map(col => (
                  <th
                    key={col}
                    className="text-left py-2 px-3 font-medium text-gray-600"
                  >
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {result.rows.map((row, i) => (
                <tr
                  key={i}
                  className="border-b border-gray-50 hover:bg-indigo-50/30"
                >
                  {columns.map(col => (
                    <td
                      key={col}
                      className="py-1.5 px-3 text-gray-700 font-mono text-xs"
                    >
                      {String(row[col] ?? '')}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {mode === 'graph' && <GraphResultView rows={result.rows} />}

        {mode === 'raw' && (
          <pre className="p-4 text-xs font-mono text-gray-700 whitespace-pre-wrap">
            {JSON.stringify(result, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}
