import type { QueryDSL, QueryResult } from '@/lib/types';

export async function executeQuery(query: QueryDSL): Promise<QueryResult> {
  const res = await fetch('/api/query/execute', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(query),
  });
  if (!res.ok) throw new Error('Query execution failed');
  return res.json();
}
