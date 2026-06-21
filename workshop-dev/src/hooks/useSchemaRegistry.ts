import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ResourceType } from '@/lib/types';

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
    onSuccess: () => qc.invalidateQueries({ queryKey: ['types'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (name: string) => deleteType(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['types'] }),
  });

  return { typesQuery, saveMutation, deleteMutation };
}
