import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ResourceType } from '@/lib/types';
import { notifyError, notifySuccess } from '@/lib/notifications';

const API = '/api/types';

async function fetchTypes(): Promise<ResourceType[]> {
  const res = await fetch(API);
  if (!res.ok) throw new Error('Failed to fetch types');
  return res.json();
}

async function saveType(type: ResourceType, isNew: boolean): Promise<void> {
  const url = isNew ? API : `${API}/${type.name}`;
  const method = isNew ? 'POST' : 'PUT';
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(type),
  });
  if (!res.ok) throw new Error('Failed to save type');
}

async function deleteType(name: string): Promise<void> {
  const res = await fetch(`${API}/${name}`, { method: 'DELETE' });
  if (!res.ok) throw new Error('Failed to delete type');
}

export function useSchemaRegistry() {
  const qc = useQueryClient();

  const typesQuery = useQuery({
    queryKey: ['types'],
    queryFn: fetchTypes,
  });

  const saveMutation = useMutation({
    mutationFn: ({ type, isNew }: { type: ResourceType; isNew: boolean }) =>
      saveType(type, isNew),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: ['types'] });
      notifySuccess(vars.isNew ? 'Type created' : 'Type updated');
    },
    onError: (err) => notifyError('Failed to save type', err),
  });

  const deleteMutation = useMutation({
    mutationFn: (name: string) => deleteType(name),
    onSuccess: (_data, name) => {
      qc.invalidateQueries({ queryKey: ['types'] });
      notifySuccess(`Type "${name}" deleted`);
    },
    onError: (err) => notifyError('Failed to delete type', err),
  });

  return { typesQuery, saveMutation, deleteMutation };
}
