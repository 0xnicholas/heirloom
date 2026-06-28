import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { Action } from '@/lib/types';

async function fetchActions(): Promise<Action[]> {
  const res = await fetch('/api/actions');
  if (!res.ok) throw new Error('Failed to fetch actions');
  return res.json();
}

async function saveAction(action: Action, isNew: boolean): Promise<void> {
  const url = isNew ? '/api/actions' : `/api/actions/${action.name}`;
  const res = await fetch(url, {
    method: isNew ? 'POST' : 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(action),
  });
  if (!res.ok) throw new Error('Failed to save action');
}

async function deleteAction(name: string): Promise<void> {
  await fetch(`/api/actions/${name}`, { method: 'DELETE' });
}

export function useActions() {
  const qc = useQueryClient();

  const actionsQuery = useQuery({
    queryKey: ['actions'],
    queryFn: fetchActions,
  });

  const saveActionMutation = useMutation({
    mutationFn: ({ action, isNew }: { action: Action; isNew: boolean }) =>
      saveAction(action, isNew),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['actions'] }),
  });

  const deleteActionMutation = useMutation({
    mutationFn: (name: string) => deleteAction(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['actions'] }),
  });

  return {
    actionsQuery,
    saveActionMutation,
    deleteActionMutation,
  };
}
