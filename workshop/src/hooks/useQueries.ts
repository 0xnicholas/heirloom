import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { executeQuery } from '@/api/query';
import type { SavedQuery } from '@/lib/types';
import { notifyError, notifySuccess } from '@/lib/notifications';

async function fetchQueries(): Promise<SavedQuery[]> {
  const res = await fetch('/api/queries');
  if (!res.ok) throw new Error('Failed to fetch queries');
  return res.json();
}

async function saveQuery(query: SavedQuery): Promise<void> {
  const res = await fetch('/api/queries', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(query),
  });
  if (!res.ok) throw new Error('Failed to save query');
}

async function deleteQuery(id: string): Promise<void> {
  await fetch(`/api/queries/${id}`, { method: 'DELETE' });
}

export function useQueries() {
  const qc = useQueryClient();

  const queriesQuery = useQuery({
    queryKey: ['queries'],
    queryFn: fetchQueries,
  });

  const executeMutation = useMutation({
    mutationFn: executeQuery,
    onError: (err) => notifyError('Query execution failed', err),
  });

  const saveMutation = useMutation({
    mutationFn: saveQuery,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['queries'] });
      notifySuccess('Query saved');
    },
    onError: (err) => notifyError('Failed to save query', err),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteQuery,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['queries'] });
      notifySuccess('Query deleted');
    },
    onError: (err) => notifyError('Failed to delete query', err),
  });

  return { queriesQuery, executeMutation, saveMutation, deleteMutation };
}
